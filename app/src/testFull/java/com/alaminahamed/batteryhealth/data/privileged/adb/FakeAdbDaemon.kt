package com.alaminahamed.batteryhealth.data.privileged.adb

import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A JVM-side stand-in for adbd that genuinely speaks the wire protocol, rather than a mock
 * that only records calls. The bugs this suite exists to catch -- an unacked WRTE stalling
 * a dump, a signature adbd would reject, a stream never closed -- are all invisible to a
 * mock and all reproducible here without a device.
 */
class FakeAdbDaemon(
    private val knownPublicKeyLine: String? = null,
    private val shellResponses: Map<String, ByteArray> = emptyMap(),
    private val writeChunkSize: Int = 8,
    private val socketTimeoutMs: Int = 5_000,
    private val withholdCnxnAfterPublicKey: Boolean = false,
    private val dropConnectionAfterCnxn: Boolean = false,
    /** Simulates a genuinely wedged shell, as opposed to a torn-down connection: OKAY is
     * sent for the OPEN as normal, then nothing further ever arrives -- no WRTE, no CLSE,
     * and the socket stays open. The only thing that can end a client read waiting on this
     * is that client's own socket timeout, which is the point ([AdbShellTest]'s
     * `runDumpGivesUpAtItsOwnBoundNotTheLongerHandshakeDefault` regression test). */
    private val withholdShellCompletion: Boolean = false,
    /** When set, every shell OPEN this daemon serves is immediately followed by one A_WRTE
     * addressed to this *other*, unrelated stream id -- simulating a second, genuinely
     * overlapping stream's traffic already live on the same connection (e.g. residue from
     * an earlier call the client abandoned without a CLSE). A correct receive loop must
     * drain and ignore it rather than fold it into the stream it is actually reading for. */
    private val noiseForForeignStreamId: Int? = null,
) {
    private val server = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()

    /** Every socket accept() has handed back, so stop() can force every one of them closed. */
    private val connections: MutableList<Socket> = Collections.synchronizedList(mutableListOf())

    /** A snapshot of [connections] in acceptance order -- lets a test confirm a specific
     * connection (e.g. "the first one issued") was actually closed by the client, rather
     * than merely superseded by a second one the client opened without closing the first. */
    val acceptedSockets: List<Socket> get() = synchronized(connections) { connections.toList() }

    /** Set once the client answered a TOKEN with a signature this daemon accepted. */
    @Volatile var authorized: Boolean = false; private set

    /** Set once the client offered its public key -- the "Allow USB debugging?" path. */
    @Volatile var receivedPublicKey: String? = null; private set

    /** Every ack the client sent, so a test can assert flow control was honoured. */
    val acks: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** The client's local id from every A_CLSE this daemon received while mid-way through
     * writing a shell body -- i.e. the client tore the stream down instead of abandoning it.
     * Populated asynchronously to whatever coroutine triggered it, on this daemon's own
     * connection-serving thread -- see [FakeAdbDaemon]'s own class doc for why. */
    val closedStreamLocalIds: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /**
     * Whatever serve() threw, including a require() tripped by a protocol violation.
     * pool.execute() swallows exceptions on its own, so without this a client that stalls
     * an ack or otherwise breaks protocol is only visible indirectly, via a client-side
     * timeout -- exactly the kind of thing a protocol-speaking fake exists to surface
     * directly instead of a mock.
     */
    @Volatile var failure: Throwable? = null; private set

    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                connections += socket
                pool.execute { runCatching { serve(socket) }.onFailure { failure = it } }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        synchronized(connections) { connections.forEach { runCatching { it.close() } } }
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        // Every accepted socket gets the daemon's own read timeout, not just the listening
        // socket -- otherwise a client that goes silent mid-protocol (e.g. never acking a
        // WRTE) blocks this thread and leaks the socket forever instead of tripping the
        // require() below, which is the whole point of this fixture.
        socket.soTimeout = socketTimeoutMs
        // Mirrors AdbConnection's own tcpNoDelay: this is a lockstep protocol on both ends,
        // and without it Nagle/delayed-ACK inflate every WRTE round trip by tens of
        // milliseconds -- multiplied across a chunked test body, real test time.
        socket.tcpNoDelay = true
        try {
            serveConnected(socket)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveConnected(socket: Socket) {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()

        fun send(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) {
            output.write(AdbMessage(command, arg0, arg1, payload).header())
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()
        }

        fun read(): Pair<AdbHeader, ByteArray> {
            val headerBytes = ByteArray(ADB_HEADER_BYTES)
            input.readFully(headerBytes)
            val header = AdbMessage.parseHeader(headerBytes)
            val payload = ByteArray(header.length)
            if (header.length > 0) input.readFully(payload)
            return header to payload
        }

        val token = ByteArray(20) { it.toByte() }
        val (first, _) = read()
        require(first.command == A_CNXN) { "expected CNXN first, got ${first.command}" }
        // A way to simulate a device that hangs up mid-handshake -- adbd doing this looks
        // like an EOF to the client, not a timeout, which drives AdbConnectResult.Failed
        // and otherwise has no test coverage.
        if (dropConnectionAfterCnxn) return
        send(A_AUTH, ADB_AUTH_TOKEN, 0, token)

        while (true) {
            val (header, payload) = read()
            when {
                header.command == A_AUTH && header.arg0 == ADB_AUTH_SIGNATURE -> {
                    if (knownPublicKeyLine != null && verifies(knownPublicKeyLine, token, payload)) {
                        authorized = true
                        send(A_CNXN, 0x01000000, 256 * 1024, "device::features=cmd\u0000".toByteArray())
                    } else {
                        send(A_AUTH, ADB_AUTH_TOKEN, 0, token)
                    }
                }
                header.command == A_AUTH && header.arg0 == ADB_AUTH_RSAPUBLICKEY -> {
                    receivedPublicKey = String(payload).trimEnd('\u0000')
                    authorized = true
                    // Withholding CNXN here is what a real device does while its "Allow USB
                    // debugging?" dialog is still up: the client is left waiting on its own
                    // soTimeoutMs rather than being told yes or no either way.
                    if (!withholdCnxnAfterPublicKey) {
                        send(A_CNXN, 0x01000000, 256 * 1024, "device::features=cmd\u0000".toByteArray())
                    }
                }
                header.command == A_OPEN -> {
                    val destination = String(payload).trimEnd('\u0000')
                    val remoteId = 1
                    val localId = header.arg0
                    send(A_OKAY, remoteId, localId)
                    if (noiseForForeignStreamId != null) {
                        send(A_WRTE, remoteId, noiseForForeignStreamId, "POISON".toByteArray())
                    }
                    if (withholdShellCompletion) {
                        // Deliberately never returns from serveConnected on this path: the
                        // socket stays open and silent rather than closing (which would
                        // hand the client a clean EOF/Failed instead of a genuine stall).
                        // Interrupted by pool.shutdownNow() in stop(), so this does not
                        // outlive the test that started it.
                        Thread.sleep(60_000)
                        return
                    }
                    val body = shellResponses[destination] ?: ByteArray(0)
                    // Chunked on purpose: a client that does not ack each WRTE stalls here
                    // rather than silently passing on a single-chunk payload. A `for` loop,
                    // not `forEach`, so the client closing the stream mid-transfer can
                    // actually break out instead of merely skipping one iteration.
                    var closedByClient = false
                    for (chunk in body.toList().chunked(writeChunkSize)) {
                        send(A_WRTE, remoteId, localId, chunk.toByteArray())
                        val (ack, _) = read()
                        if (ack.command == A_CLSE) {
                            // The client tore the stream down early (e.g. the maxBytes
                            // bailout) instead of abandoning it -- exactly what that code
                            // path is required to do. Not folded into `acks`: that list is
                            // for real flow-control acknowledgements only.
                            closedStreamLocalIds += ack.arg0
                            closedByClient = true
                            break
                        }
                        acks += ack.command
                        require(ack.command == A_OKAY) { "client did not ack WRTE" }
                    }
                    if (!closedByClient) send(A_CLSE, remoteId, localId)
                }
                header.command == A_CLSE -> return
            }
        }
    }

    private fun verifies(publicKeyLine: String, token: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val verifier = Signature.getInstance("NONEwithRSA")
            verifier.initVerify(publicKeyFromLine(publicKeyLine))
            verifier.update(adbSignatureBlob(token))
            verifier.verify(signature)
        }.getOrDefault(false)

    companion object {
        /** A host-side signer, since AndroidKeystore is unavailable on the JVM. */
        fun signer(): Pair<AdbSigner, String> {
            val pair = KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
            val rsa = pair.public as RSAPublicKey
            val line = encodeAndroidPublicKey(rsa.modulus, rsa.publicExponent, "test@host")
            val signer = object : AdbSigner {
                override fun publicKeyLine() = line
                override fun sign(token: ByteArray): ByteArray =
                    Signature.getInstance("NONEwithRSA").run {
                        initSign(pair.private)
                        update(adbSignatureBlob(token))
                        sign()
                    }
            }
            return signer to line
        }

        /** Reverses [encodeAndroidPublicKey] so this daemon can verify like adbd does. */
        private fun publicKeyFromLine(line: String): PublicKey {
            val struct = Base64.getDecoder().decode(line.substringBefore(' '))
            val buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
            val words = buffer.int
            buffer.int // n0inv, not needed to verify
            var modulus = BigInteger.ZERO
            for (index in 0 until words) {
                val word = BigInteger.valueOf(buffer.int.toLong() and 0xFFFFFFFFL)
                modulus = modulus.or(word.shiftLeft(index * 32))
            }
            repeat(words) { buffer.int } // rr, not needed to verify
            val exponent = BigInteger.valueOf(buffer.int.toLong() and 0xFFFFFFFFL)
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        }
    }
}

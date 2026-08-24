package com.mralaminahamed.batteryhealth.data.privileged.adb

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
) {
    private val server = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()

    /** Set once the client answered a TOKEN with a signature this daemon accepted. */
    @Volatile var authorized: Boolean = false; private set

    /** Set once the client offered its public key -- the "Allow USB debugging?" path. */
    @Volatile var receivedPublicKey: String? = null; private set

    /** Every ack the client sent, so a test can assert flow control was honoured. */
    val acks: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    val port: Int get() = server.localPort

    fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                pool.execute { runCatching { serve(socket) } }
            }
        }
    }

    fun stop() {
        runCatching { server.close() }
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
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
                    send(A_CNXN, 0x01000000, 256 * 1024, "device::features=cmd\u0000".toByteArray())
                }
                header.command == A_OPEN -> {
                    val destination = String(payload).trimEnd('\u0000')
                    val remoteId = 1
                    val localId = header.arg0
                    send(A_OKAY, remoteId, localId)
                    val body = shellResponses[destination] ?: ByteArray(0)
                    // Chunked on purpose: a client that does not ack each WRTE stalls here
                    // rather than silently passing on a single-chunk payload.
                    body.toList().chunked(writeChunkSize).forEach { chunk ->
                        send(A_WRTE, remoteId, localId, chunk.toByteArray())
                        val (ack, _) = read()
                        acks += ack.command
                        require(ack.command == A_OKAY) { "client did not ack WRTE" }
                    }
                    send(A_CLSE, remoteId, localId)
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

package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbConnectionTest {

    private var daemon: FakeAdbDaemon? = null

    @After fun tearDown() { daemon?.stop() }

    @Test
    fun connectsWhenTheDaemonAlreadyKnowsTheKey() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = line).also { daemon = it; it.start() }
        val connection = AdbConnection(port = fake.port, signer = signer, soTimeoutMs = 2_000)

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        assertEquals(AdbConnectResult.Connected, result)
        assertTrue(fake.authorized)
    }

    @Test
    fun offersThePublicKeyWhenTheDaemonDoesNotKnowIt() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = null).also { daemon = it; it.start() }
        val connection = AdbConnection(port = fake.port, signer = signer, soTimeoutMs = 2_000)

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        // The path that raises "Allow USB debugging?" on a real device. This fake grants
        // the connection immediately after receiving the key, so the client reaches
        // Connected here -- the "dialog never answers" case is covered separately below.
        assertEquals(AdbConnectResult.Connected, result)
        assertEquals(line, fake.receivedPublicKey)
    }

    @Test
    fun reportsUnreachableWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()
        val connection = AdbConnection(
            port = port, signer = FakeAdbDaemon.signer().first, soTimeoutMs = 500,
        )

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        assertEquals(AdbConnectResult.Unreachable, result)
    }

    @Test
    fun reportsFailedWhenTheDaemonHangsUpMidHandshake() = runTest {
        val fake = FakeAdbDaemon(dropConnectionAfterCnxn = true).also { daemon = it; it.start() }
        val connection = AdbConnection(
            port = fake.port, signer = FakeAdbDaemon.signer().first, soTimeoutMs = 2_000,
        )

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        assertEquals(AdbConnectResult.Failed, result)
    }

    @Test
    fun reportsAwaitingAuthorizationWhenTheDialogNeverAnswers() = runTest {
        val (signer, _) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(withholdCnxnAfterPublicKey = true).also { daemon = it; it.start() }
        // Short on purpose: this test's whole point is to actually hit the client's own
        // read timeout while waiting for a CNXN this daemon has deliberately withheld.
        val connection = AdbConnection(port = fake.port, signer = signer, soTimeoutMs = 300)

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        assertEquals(AdbConnectResult.AwaitingAuthorization, result)
    }

    /**
     * Regression test for C2: [AdbConnection.read] used to do `ByteArray(header.length)`
     * with no bound check on a value that came straight off the wire. A negative length
     * throws `NegativeArraySizeException` -- not an `IOException` -- so it used to escape
     * both of `connect()`'s `catch (e: IOException)` blocks entirely and crash the caller
     * instead of degrading to [AdbConnectResult.Failed]. A plain [FakeAdbDaemon] cannot
     * produce this -- it only ever speaks well-formed protocol -- so this test runs a raw,
     * deliberately corrupt/hostile peer by hand.
     */
    @Test
    fun connectFailsCleanlyWhenTheDaemonLiesAboutPayloadLength() = runTest {
        val server = ServerSocket(0)
        val serverThread = thread {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            socket.soTimeout = 2_000
            runCatching {
                val input = DataInputStream(socket.getInputStream())
                val output = socket.getOutputStream()

                // Read (and discard) the client's initial A_CNXN so the exchange stays in
                // sync, then respond with a header that lies about its own payload length.
                val headerBytes = ByteArray(ADB_HEADER_BYTES)
                input.readFully(headerBytes)
                val clientHeader = AdbMessage.parseHeader(headerBytes)
                if (clientHeader.length > 0) input.readFully(ByteArray(clientHeader.length))

                val malformed = ByteBuffer.allocate(ADB_HEADER_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(A_AUTH)
                    .putInt(ADB_AUTH_TOKEN)
                    .putInt(0)
                    .putInt(-1) // the lie: a negative payload length
                    .putInt(0)
                    .putInt(A_AUTH xor -1)
                    .array()
                output.write(malformed)
                output.flush()
                Thread.sleep(1_000)
            }
            runCatching { socket.close() }
        }

        val connection = AdbConnection(
            port = server.localPort,
            signer = FakeAdbDaemon.signer().first,
            soTimeoutMs = 2_000,
        )

        val result = try {
            connection.connect()
        } finally {
            connection.close()
            runCatching { server.close() }
            serverThread.join(2_000)
        }

        assertEquals(AdbConnectResult.Failed, result)
    }

    /**
     * Regression test for M1: the outer `try { send(A_CNXN...); handshake() } catch (e:
     * IOException)` in [AdbConnection.connect] used to leave the socket open on a declared
     * failure, unlike the three catches around the initial TCP connect a few lines above it,
     * which all `runCatching { newSocket.close() }`. An idle loopback connection to adbd is
     * left open after every handshake failure until this leaks.
     *
     * The server here deliberately never responds after the client's initial A_CNXN and
     * never closes its own side either -- the client's handshake read blocks until its own
     * `soTimeoutMs` elapses and throws `SocketTimeoutException` (an `IOException`), driving
     * [AdbConnectResult.Failed]. Asserted from the still-open server side: if the client
     * closed its own socket as this fix requires, this side's next read sees a prompt EOF;
     * if the client leaked it, no FIN is ever sent and this side just keeps timing out.
     */
    @Test
    fun connectClosesTheSocketWhenTheHandshakeThrows() = runTest {
        val server = ServerSocket(0)
        var serverSideSocket: Socket? = null
        val serverThread = thread {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            serverSideSocket = socket
            socket.soTimeout = 5_000
            runCatching {
                val input = DataInputStream(socket.getInputStream())
                input.readFully(ByteArray(ADB_HEADER_BYTES)) // the client's A_CNXN
                // Deliberately no response and no close from this side -- the whole point
                // is observing whether the CLIENT closed its own socket.
            }
        }

        val connection = AdbConnection(
            port = server.localPort, signer = FakeAdbDaemon.signer().first, soTimeoutMs = 500,
        )
        val result = connection.connect()
        serverThread.join(2_000)

        try {
            assertEquals(AdbConnectResult.Failed, result)
            val accepted = requireNotNull(serverSideSocket) { "server never accepted a connection" }
            accepted.soTimeout = 50
            assertTrue(
                "expected the client to have closed its socket, causing this side to see EOF",
                awaitUntilTrue(timeoutMs = 2_000) {
                    runCatching { accepted.getInputStream().read() == -1 }.getOrDefault(false)
                },
            )
        } finally {
            runCatching { server.close() }
            runCatching { serverSideSocket?.close() }
        }
    }
}

/** Polls [condition] until it is true or [timeoutMs] elapses, for asserting on an effect (the
 * peer noticing the client closed its socket) that lands asynchronously to the calling
 * coroutine, on a real background thread. A plain blocking loop, not
 * `kotlinx.coroutines.delay`: under `runTest`, `delay` resolves against the test dispatcher's
 * virtual clock, which is wrong for a condition that depends on real wall-clock time here. */
private fun awaitUntilTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadlineMs = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadlineMs) {
        if (condition()) return true
    }
    return condition()
}

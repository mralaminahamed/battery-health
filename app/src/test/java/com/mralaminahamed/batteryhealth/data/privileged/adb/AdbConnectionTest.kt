package com.mralaminahamed.batteryhealth.data.privileged.adb

import java.io.DataInputStream
import java.net.ServerSocket
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
}

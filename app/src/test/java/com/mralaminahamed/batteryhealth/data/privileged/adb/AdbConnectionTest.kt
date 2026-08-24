package com.mralaminahamed.batteryhealth.data.privileged.adb

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

        val result = AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000).connect()

        assertEquals(AdbConnectResult.Connected, result)
        assertTrue(fake.authorized)
    }

    @Test
    fun offersThePublicKeyWhenTheDaemonDoesNotKnowIt() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = null).also { daemon = it; it.start() }

        AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000).connect()

        // The path that raises "Allow USB debugging?" on a real device.
        assertEquals(line, fake.receivedPublicKey)
    }

    @Test
    fun reportsUnreachableWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()

        val result = AdbConnection(
            "127.0.0.1", port, FakeAdbDaemon.signer().first, soTimeoutMs = 500,
        ).connect()

        assertEquals(AdbConnectResult.Unreachable, result)
    }
}

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
        val connection = AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000)

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
        val connection = AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 2_000)

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
            "127.0.0.1", port, FakeAdbDaemon.signer().first, soTimeoutMs = 500,
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
            "127.0.0.1", fake.port, FakeAdbDaemon.signer().first, soTimeoutMs = 2_000,
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
        val connection = AdbConnection("127.0.0.1", fake.port, signer, soTimeoutMs = 300)

        val result = try {
            connection.connect()
        } finally {
            connection.close()
        }

        assertEquals(AdbConnectResult.AwaitingAuthorization, result)
    }
}

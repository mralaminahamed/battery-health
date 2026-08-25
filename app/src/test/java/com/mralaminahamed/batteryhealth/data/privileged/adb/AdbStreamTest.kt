package com.mralaminahamed.batteryhealth.data.privileged.adb

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbStreamTest {

    private var daemon: FakeAdbDaemon? = null

    @After fun tearDown() { daemon?.stop() }

    private suspend fun connected(responses: Map<String, ByteArray>, chunk: Int = 8): AdbConnection {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(line, responses, writeChunkSize = chunk)
            .also { daemon = it; it.start() }
        return AdbConnection(port = fake.port, signer = signer, soTimeoutMs = 2_000)
            .also { it.connect() }
    }

    @Test
    fun readsAChunkedDumpCompletely() = runTest {
        val body = "Current Battery Service state:\n  level: 84\n  temperature: 298\n"
        val connection = connected(mapOf("shell:dumpsys battery" to body.toByteArray()), chunk = 8)

        try {
            assertEquals(body, connection.shell("dumpsys battery"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun acknowledgesEveryWriteSoTheDaemonNeverStalls() = runTest {
        // 200 bytes at 8 per chunk is 25 WRTEs. A client that acks only the first hangs
        // here, which is why the daemon requires the ack rather than the test counting
        // them after the fact.
        val body = "x".repeat(200)
        val connection = connected(mapOf("shell:dumpsys battery" to body.toByteArray()), chunk = 8)

        try {
            assertEquals(body, connection.shell("dumpsys battery"))
            assertTrue(daemon!!.acks.size >= 25)
            assertTrue(daemon!!.acks.all { it == A_OKAY })
        } finally {
            connection.close()
        }
    }

    @Test
    fun returnsNullWhenThePayloadExceedsTheCap() = runTest {
        val body = ByteArray(4096) { 'y'.code.toByte() }
        val connection = connected(mapOf("shell:dumpsys battery" to body))

        try {
            assertNull(connection.shell("dumpsys battery", maxBytes = 1024))
            // At 8 bytes/chunk, chunk 129 is the first to push the running total (1032)
            // past the 1024 cap -- so exactly 129 acks must have gone out before the abort.
            // This is the regression test for the ack-before-size-check ordering: swap that
            // order and the abort fires without acking chunk 129, so this drops to 128.
            assertEquals(129, daemon!!.acks.size)
            assertTrue(daemon!!.acks.all { it == A_OKAY })
        } finally {
            connection.close()
        }
    }

    @Test
    fun returnsNullForAnEmptyResponse() = runTest {
        val connection = connected(mapOf("shell:dumpsys battery" to ByteArray(0)))

        try {
            assertNull(connection.shell("dumpsys battery"))
        } finally {
            connection.close()
        }
    }

    @Test
    fun returnsNullWhenTheConnectionDiesDuringTheExchange() = runTest {
        val body = "x".repeat(200)
        val connection = connected(mapOf("shell:dumpsys battery" to body.toByteArray()), chunk = 8)

        try {
            // stop() over a race that kills the daemon mid-WRTE: it closes every accepted
            // socket unconditionally and immediately, so shell()'s very first send() or
            // read() is guaranteed to hit a torn-down connection -- no timing window where
            // the exchange might sneak in and complete before the failure lands, which a
            // "let it get partway through 25 chunks, then kill it" approach would have.
            daemon!!.stop()

            assertNull(connection.shell("dumpsys battery"))
        } finally {
            connection.close()
        }
    }
}

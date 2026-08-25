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

    /**
     * Regression test for C1's stream-id filtering: [AdbConnection.shell]'s receive loop
     * used to fold in whatever arrived regardless of which stream it was addressed to. Here
     * the daemon interleaves one A_WRTE for a second, unrelated stream id -- exactly what
     * residue from an earlier, improperly-abandoned call (or, absent the serializing Mutex
     * this defect also adds, a genuinely concurrent call) looks like on the wire -- with the
     * real body. A correct receive loop drains and ignores it; the buggy one appends it,
     * producing "POISON" + the real body instead of the real body alone.
     */
    @Test
    fun doesNotCrossAttributeBytesFromAnOverlappingStream() = runTest {
        val body = "Current Battery Service state:\n  level: 84\n"
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(
            knownPublicKeyLine = line,
            shellResponses = mapOf("shell:dumpsys battery" to body.toByteArray()),
            noiseForForeignStreamId = 999_001,
        ).also { daemon = it; it.start() }
        val connection = AdbConnection(port = fake.port, signer = signer, soTimeoutMs = 2_000)
            .also { it.connect() }

        try {
            assertEquals(body, connection.shell("dumpsys battery"))
        } finally {
            connection.close()
        }
    }

    /**
     * Regression test for C1's third part: the maxBytes bailout used to `return@withContext
     * null` without ever sending A_CLSE, abandoning the stream -- adbd would keep writing
     * for it, and the *next* shell() call on the same connection would consume that residue
     * as its own output. Asserted from the daemon's side, which only learns the stream was
     * closed once the OS actually delivers those bytes -- asynchronous to this coroutine
     * even over loopback, hence the short poll rather than an immediate assertion.
     */
    @Test
    fun bailingOutOverTheCapClosesTheStreamInsteadOfAbandoningIt() = runTest {
        val body = ByteArray(4096) { 'y'.code.toByte() }
        val connection = connected(mapOf("shell:dumpsys battery" to body))

        try {
            assertNull(connection.shell("dumpsys battery", maxBytes = 1024))
            assertTrue(
                "expected the daemon to receive an A_CLSE for the abandoned stream",
                awaitTrue(timeoutMs = 2_000) { daemon!!.closedStreamLocalIds.isNotEmpty() },
            )
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

/** Polls [condition] until it is true or [timeoutMs] elapses, for asserting on an effect (the
 * daemon noticing a message on its own connection-serving thread) that lands asynchronously
 * to the calling coroutine, rather than synchronously with the call that caused it. A plain
 * blocking loop, not `kotlinx.coroutines.delay`: under `runTest`, `delay` resolves against the
 * test dispatcher's virtual clock, which is wrong for a condition that depends on real
 * wall-clock time on a real background thread -- mirrors [AdbShellTest]'s own `awaitTrue`. */
private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadlineMs = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadlineMs) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

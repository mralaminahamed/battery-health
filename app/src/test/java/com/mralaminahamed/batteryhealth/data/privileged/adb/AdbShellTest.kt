package com.mralaminahamed.batteryhealth.data.privileged.adb

import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_BATTERY
import com.mralaminahamed.batteryhealth.data.privileged.CMD_DUMP_CHECKIN
import com.mralaminahamed.batteryhealth.data.privileged.TransportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellTest {

    private var daemon: FakeAdbDaemon? = null

    // close() is on the concrete class, not PrivilegedShell -- see AdbShell's own doc for
    // why. Without this, every test here leaks the client-side socket even after the fake
    // daemon it talked to is torn down; matching how AdbConnectionTest/AdbStreamTest close
    // the AdbConnection they construct directly.
    private var adbShell: AdbShell? = null

    @After
    fun tearDown() {
        adbShell?.close()
        daemon?.stop()
    }

    @Test
    fun runsBothAllowlistedCommands() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(
            knownPublicKeyLine = line,
            shellResponses = mapOf(
                "shell:$CMD_DUMP_BATTERY" to "level: 84\n".toByteArray(),
                "shell:$CMD_DUMP_CHECKIN" to "9,0,i,vers,36\n".toByteArray(),
            ),
        ).also { daemon = it; it.start() }

        val shell = AdbShell(port = fake.port, signer = signer).also { adbShell = it }
        shell.connect()

        assertEquals(TransportState.Ready, shell.state.value)
        assertEquals("level: 84\n", shell.runDump())
        assertEquals("9,0,i,vers,36\n", shell.runCheckin())
    }

    @Test
    fun isUnavailableAndReturnsNullWhenNothingIsListening() = runTest {
        val closed = FakeAdbDaemon().also { it.start() }
        val port = closed.port
        closed.stop()

        val shell = AdbShell(port = port, signer = FakeAdbDaemon.signer().first).also { adbShell = it }
        shell.connect()

        assertEquals(TransportState.Unavailable, shell.state.value)
        assertNull(shell.runDump())
    }

    /**
     * Regression test for C1's first part: `AdbShell` reuses one [AdbConnection] for every
     * call, and nothing used to serialize them -- `BatteryRepository`'s two independent
     * flows (`snapshots`/`appPower`) both resolve to this one shell, and a ready-transition
     * or redump fires both `runDump()`/`runCheckin()` together. Without a Mutex, two
     * coroutines sit in `shell()` on one unsynchronized socket, and can read each other's
     * headers -- `dumpsys battery` output parsed as checkin data or vice versa. Fired via
     * `async(Dispatchers.IO)` rather than the test dispatcher precisely so the two calls can
     * genuinely race for the shared connection the way two ViewModels' collectors would.
     */
    @Test
    fun concurrentDumpAndCheckinCallsDoNotCrossAttributeResults() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(
            knownPublicKeyLine = line,
            shellResponses = mapOf(
                "shell:$CMD_DUMP_BATTERY" to "level: 84\n".toByteArray(),
                "shell:$CMD_DUMP_CHECKIN" to "9,0,i,vers,36\n".toByteArray(),
            ),
        ).also { daemon = it; it.start() }
        val shell = AdbShell(port = fake.port, signer = signer).also { adbShell = it }
        shell.connect()
        assertEquals(TransportState.Ready, shell.state.value)

        val dumpResult = async(Dispatchers.IO) { shell.runDump() }
        val checkinResult = async(Dispatchers.IO) { shell.runCheckin() }

        assertEquals("level: 84\n", dumpResult.await())
        assertEquals("9,0,i,vers,36\n", checkinResult.await())
    }

    /**
     * Regression test for the close-before-reconnect line in [AdbShell.connect] -- the one
     * commented "Do not 'simplify' this away". Without it, this exact sequence (two
     * `connect()` calls on one `AdbShell`, the shape `refresh()` produces on every
     * `ON_RESUME`) opens a second socket while the first is still open on the daemon's
     * side, which is precisely the per-resume leak that line exists to prevent -- deleting
     * it should turn this test red.
     *
     * Asserted from the daemon's side, not by reaching into [AdbShell]'s private
     * `connection` field: the daemon only notices its first accepted socket died once the
     * OS actually delivers the FIN the client's `close()` call causes, which is
     * asynchronous to this coroutine even over loopback -- hence the short poll rather
     * than an immediate assertion.
     */
    @Test
    fun connectClosesThePreviousConnectionBeforeOpeningTheNext() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = line).also { daemon = it; it.start() }
        val shell = AdbShell(port = fake.port, signer = signer).also { adbShell = it }

        shell.connect()
        assertEquals(TransportState.Ready, shell.state.value)

        shell.connect()
        assertEquals(TransportState.Ready, shell.state.value)

        val accepted = fake.acceptedSockets
        assertEquals(2, accepted.size)
        assertTrue(
            "expected the first connection to be closed once the second connect() finished",
            awaitTrue(timeoutMs = 2_000) { accepted[0].isClosed },
        )
    }

    /**
     * Regression test for Important-1: this project shipped exactly the bug this guards
     * against once already -- `runDump()` wrapped `current.shell(command)` in
     * `withTimeoutOrNull(3_000)`, but `send`/`read` are plain blocking socket calls with no
     * suspension point, so that coroutine-level timeout could not preempt a stalled read.
     * The call was actually bounded by [AdbConnection]'s connection-wide default (this
     * suite's own handshake timeout), roughly 3x the promised bound. Fixed by narrowing the
     * socket's own read timeout per call via [AdbConnection.withSoTimeout] instead.
     *
     * The daemon here sends OKAY for the shell OPEN and then goes silent -- no WRTE, no
     * CLSE, socket left open -- so the only thing that can end this call is the client's own
     * read timeout. If that regresses to the connection-wide default again, this call takes
     * several times longer than the window asserted below.
     */
    @Test
    fun runDumpGivesUpAtItsOwnBoundNotTheConnectionsLongerDefault() = runTest {
        val (signer, line) = FakeAdbDaemon.signer()
        val fake = FakeAdbDaemon(knownPublicKeyLine = line, withholdShellCompletion = true)
            .also { daemon = it; it.start() }
        val shell = AdbShell(port = fake.port, signer = signer).also { adbShell = it }
        shell.connect()
        assertEquals(TransportState.Ready, shell.state.value)

        val startedAtMs = System.currentTimeMillis()
        val result = shell.runDump()
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertNull(result)
        assertEquals(TransportState.Unavailable, shell.state.value)
        // The dump's own bound is 3s; the connection's handshake-sized default this bug
        // used to fall back to is more than 3x that. A window well clear of both edges:
        // comfortably above the 3s bound for host scheduling jitter, comfortably below
        // where the old, wrong behavior would land.
        assertTrue("expected roughly a 3s bound, took ${elapsedMs}ms", elapsedMs in 2_500..6_000)
    }
}

/** Polls [condition] until it is true or [timeoutMs] elapses, for asserting on an effect
 * (a peer socket noticing its connection died) that lands on another thread, asynchronously
 * to the calling coroutine, rather than synchronously with the call that caused it. A plain
 * blocking loop, not `kotlinx.coroutines.delay`: under `runTest`, `delay` resolves against
 * the test dispatcher's virtual clock, which free-runs instantly once nothing else is
 * scheduled on it -- exactly the trap [AdbShell]'s own `withSoTimeout` fix exists to avoid
 * for socket reads, and just as wrong here for a condition that depends on real wall-clock
 * time on a real background thread. */
private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadlineMs = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadlineMs) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

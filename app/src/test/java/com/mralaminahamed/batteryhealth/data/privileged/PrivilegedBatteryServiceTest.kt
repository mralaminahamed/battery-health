package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runShellCommandWithTimeout] is pure `ProcessBuilder`/`Process` -- no Android type
 * anywhere in it -- so this runs directly on the JVM test host, substituting a real shell
 * command for the hardcoded `dumpsys battery` [PrivilegedBatteryService.dumpBattery]
 * always passes in production. That substitution is the only reason the function takes
 * the command as a parameter at all.
 *
 * [PrivilegedBatteryService] itself is deliberately never constructed here: it extends
 * `IUserService.Stub`, whose constructor calls `Binder.attachInterface`, which throws
 * immediately against the unmocked `android.jar` this module's unit tests compile and run
 * against (`RuntimeException`, confirmed directly -- an earlier draft of this file
 * constructed it and every single test failed on that line, before any assertion ran).
 * Robolectric would paper over that but is off the table per this task's constraints;
 * routing the actual logic through a free function is what makes it JVM-testable at all
 * without it.
 */
class PrivilegedBatteryServiceTest {

    @Test
    fun aQuickCommandReturnsItsOutputWithinTheTimeout() {
        val result = runShellCommandWithTimeout(listOf("sh", "-c", "printf hello"))

        assertEquals("hello", result)
    }

    /**
     * RED without Important 2's fix (the old `process.waitFor()` with no timeout, called
     * after an unbounded stream read): this hangs for as long as the sleep below, proving
     * the wait really was unbounded, rather than failing fast. GREEN with it: bounded by
     * [DUMP_TIMEOUT_SECONDS] (3s), well under the 30-second sleep this deliberately
     * outlasts -- the only way to pass the elapsed-time assertion below is for
     * `waitFor(timeout, unit)` to have actually fired and `destroyForcibly()` to have
     * actually killed the process rather than merely being scheduled to.
     */
    @Test
    fun aHangingCommandIsKilledAndReturnsEmptyRatherThanBlockingForever() {
        val startedAtMs = System.currentTimeMillis()

        val result = runShellCommandWithTimeout(listOf("sh", "-c", "sleep 30"))

        val elapsedMs = System.currentTimeMillis() - startedAtMs
        assertEquals("", result)
        // Generous margin above the 3-second shell-side timeout for CI/host scheduling
        // jitter, but nowhere near the 30-second sleep the command actually asked for.
        assertTrue(
            "expected well under the 30s sleep to elapse, but took ${elapsedMs}ms",
            elapsedMs < 15_000,
        )
    }

    @Test
    fun aCommandThatFailsToStartReturnsEmptyRatherThanThrowing() {
        val result = runShellCommandWithTimeout(listOf("this-executable-does-not-exist-anywhere-xyz"))

        assertEquals("", result)
    }

    @Test
    fun aNonZeroExitStillReturnsWhateverWasWrittenToStdout() {
        // redirectErrorStream(true) means stderr is folded into the same stream --
        // dumpBattery's real caller (DumpsysBatteryParser) never sees exit codes at all,
        // only text, so a command that writes output and then fails must still return
        // that output rather than discarding it because of the non-zero exit.
        val result = runShellCommandWithTimeout(listOf("sh", "-c", "printf partial; exit 1"))

        assertEquals("partial", result)
    }
}

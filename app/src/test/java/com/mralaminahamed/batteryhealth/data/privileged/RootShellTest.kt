package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [runRootCommand] is pure `ProcessBuilder`/`Process` -- no Android type anywhere in it, and
 * no hardcoded `su` either -- so this drives it directly with `sh`, the same JVM-testable
 * substitution this codebase uses anywhere a subprocess needs a stand-in for a hardcoded,
 * device-only executable. These five cases cover: happy path, the timeout/`destroyForcibly`
 * path, a missing executable, a non-zero exit, and the exact pipe-deadlock this pattern
 * exists to prevent.
 *
 * [RootShell] itself is never constructed or exercised here: every one of its own methods
 * hardcodes `su`, and `su`'s Magisk-dialog behavior (the `AwaitingAuthorization`/`Denied`
 * mapping in [RootShell.probe]) is genuinely device-only -- there is nothing on a JVM test
 * host to answer that dialog either way. What is tested here is the process-management
 * mechanism underneath it, which is argv-agnostic and identical whether the first element
 * of that argv is `su` or `sh`.
 */
class RootShellTest {

    @Test
    fun aQuickCommandReturnsItsOutputWithinTheTimeout() {
        val result = runRootCommand(listOf("sh", "-c", "printf hello"), timeoutSeconds = 5)

        assertEquals(RootExecResult.Success("hello"), result)
    }

    /**
     * RED without the timeout/`destroyForcibly` fix (a blocking no-arg `waitFor()`): this
     * hangs for as long as the sleep below, proving the wait really was unbounded rather
     * than failing fast. GREEN with it: bounded by the 2-second `timeoutSeconds` passed
     * here, well under the 30-second sleep this deliberately outlasts -- the only way to
     * pass the elapsed-time assertion below is for `waitFor(timeout, unit)` to have
     * actually fired and `destroyForcibly()` to have actually killed the process rather
     * than merely being scheduled to.
     */
    @Test
    fun aHangingCommandIsKilledAndReturnsTimedOutRatherThanBlockingForever() {
        val startedAtMs = System.currentTimeMillis()

        val result = runRootCommand(listOf("sh", "-c", "sleep 30"), timeoutSeconds = 2)

        val elapsedMs = System.currentTimeMillis() - startedAtMs
        assertEquals(RootExecResult.TimedOut, result)
        // Generous margin above the 2-second bound for CI/host scheduling jitter, but
        // nowhere near the 30-second sleep the command actually asked for.
        assertTrue(
            "expected well under the 30s sleep to elapse, but took ${elapsedMs}ms",
            elapsedMs < 15_000,
        )
    }

    @Test
    fun aCommandThatFailsToStartReturnsUnavailableRatherThanThrowing() {
        val result = runRootCommand(listOf("this-executable-does-not-exist-anywhere-xyz"), timeoutSeconds = 5)

        assertEquals(RootExecResult.Unavailable, result)
    }

    /**
     * `redirectErrorStream(true)` folds stderr into the same drained stream, and a caller
     * (RootShell.probe's own Denied mapping) cares about the exit code, not just the text
     * -- so unlike a shell runner with no exit code to report back to a caller, this keeps
     * the two facts separate rather than collapsing a failing command down to only its
     * output. What this
     * test proves is narrower and just as load-bearing: the reader thread actually
     * captured "partial" before the non-zero exit was detected, not merely that a non-zero
     * exit was noticed.
     */
    @Test
    fun aNonZeroExitStillCarriesWhateverWasWrittenToStdout() {
        val result = runRootCommand(listOf("sh", "-c", "printf partial; exit 1"), timeoutSeconds = 5)

        assertEquals(RootExecResult.NonZeroExit("partial"), result)
    }

    /**
     * The bug this pattern exists to prevent: reading the stream only *after* `waitFor()`
     * succeeds deadlocks once a command's output exceeds the OS pipe buffer between the
     * child process and this one -- the child's own `write()` blocks once that buffer
     * fills, and this thread is simultaneously blocked in `waitFor()` waiting for the
     * child to *exit* before it will ever read anything to drain it. Reproduced here with
     * a command that writes 300,000 bytes, comfortably past every mainstream platform's
     * default pipe buffer (64KB on Linux).
     */
    @Test
    fun aCommandWritingMoreThanThePipeBufferCompletesWithoutDeadlocking() {
        val expectedByteCount = 300_000

        val result = runRootCommand(listOf("sh", "-c", "yes | head -c $expectedByteCount"), timeoutSeconds = 5)

        check(result is RootExecResult.Success) { "expected Success, got $result" }
        assertEquals(expectedByteCount, result.output.length)
    }
}

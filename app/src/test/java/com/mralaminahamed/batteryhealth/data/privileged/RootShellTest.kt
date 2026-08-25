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

    /**
     * `Thread.join(millis)` returns for two different reasons -- the thread finished, or the
     * budget simply ran out -- and nothing at the call site distinguishes them. This
     * constructs the second case deterministically rather than hoping to catch a real GC or
     * scheduler stall: `sh` backgrounds `sleep 5` (inheriting the same merged stdout pipe
     * this reader drains) and exits immediately after printing "partial", so
     * `process.waitFor` succeeds almost instantly -- but the pipe's write end stays open,
     * held by the still-running background process, so the reader thread's blocking `read()`
     * has nothing to return and no EOF to see. It is therefore still alive, unable to say
     * whether "partial" is the whole story, when the 2-second drain budget expires.
     *
     * The sleep must outlast the drain budget by a wide margin, and 60s is not arbitrary
     * caution. This test's outcome hinges on the background process still holding the pipe
     * at the moment `reader.join` gives up, so the real requirement is
     * `sleep > timeToReachJoin + READER_DRAIN_TIMEOUT_MS`. At `sleep 5` that left only a
     * three-second allowance for everything before the join, which a loaded machine can
     * exhaust through JIT, GC or CPU contention -- and did, once, in a full-suite run: the
     * sleep exited first, the reader saw EOF, and this failed with
     * `expected:<DrainTimedOut> but was:<Success(output=partial)>`. Do not shrink it back.
     *
     * RED without the fix: `reader.join` returning (for either reason) is read as "drained",
     * so this returns `Success("partial")` -- a partial dump reported as complete. GREEN
     * with it: `reader.isAlive` after the join catches the still-blocked thread and this
     * returns a distinct non-success result instead.
     */
    @Test
    fun aReaderThreadStillDrainingWhenTheProcessExitsIsNotReportedAsSuccess() {
        val result = runRootCommand(listOf("sh", "-c", "sleep 60 & printf partial"), timeoutSeconds = 5)

        assertEquals(RootExecResult.DrainTimedOut, result)
    }
}

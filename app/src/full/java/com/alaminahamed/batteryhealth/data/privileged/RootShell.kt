package com.alaminahamed.batteryhealth.data.privileged

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * How long [connect]'s `su -c id` probe waits for Magisk's own grant dialog before giving
 * up on this attempt. Generous rather than tight -- this is exactly the user-paced wait
 * [TransportState.AwaitingAuthorization] documents, not a hung-process bound -- but still
 * finite so a device where Magisk itself is wedged cannot block this coroutine forever.
 */
private const val PROBE_TIMEOUT_SECONDS = 10L

// Named distinctly from AdbShell's own DUMP_TIMEOUT_MS/CHECKIN_TIMEOUT_MS (same values,
// different unit and a different package) even though the numbers match -- those bound a
// socket read, these bound a `su` subprocess this class starts directly; same rationale,
// different call site.

/** A small, fast dump gets a short, generous-but-bounded budget -- same value and rationale
 * as `AdbShell`'s own `DUMP_TIMEOUT_MS`. */
private const val ROOT_DUMP_TIMEOUT_SECONDS = 3L

/** This payload runs roughly 50x larger than the plain dump's, so it gets proportionally
 * more room -- same value and rationale as `AdbShell`'s own `CHECKIN_TIMEOUT_MS`. */
private const val ROOT_CHECKIN_TIMEOUT_SECONDS = 8L

/** How long a reader thread gets to finish draining output that was already fully written
 * before the child exited -- see [runRootCommand]'s own doc for why this is a thread, not a
 * second command timeout. */
private const val READER_DRAIN_TIMEOUT_MS = 2_000L

/**
 * The root transport: every command is `su -c "<command>"`, Magisk's own gate, with no
 * daemon and no socket -- a fresh subprocess per call, unlike [AdbShell] which keeps one
 * connection alive across calls.
 *
 * [connect] is what raises Magisk's "Grant root access?" dialog, by literally running `su`.
 * That makes when this gets called a real product decision, not an implementation detail:
 * **never call it from init.** Doing so would pop that dialog the instant this class is
 * constructed, before the user has asked this app for anything privileged -- exactly the
 * kind of unprompted, hostile-feeling permission grab this app's own design explicitly
 * avoids elsewhere -- [AdbShell.connect] is likewise never called from its own init, only
 * from a caller that has decided this is the moment to establish the transport.
 * The gateway that fronts both transports owns deciding when [connect] should run.
 */
class RootShell : PrivilegedShell {

    private val _state = MutableStateFlow<TransportState>(TransportState.Unavailable)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    // Mirrors AdbShell's own connectInFlight: one `su -c id` probe in flight at a time, so
    // two overlapping calls to connect() cannot each spawn their own subprocess and race
    // Magisk's dialog against itself.
    private val connectInFlight = AtomicBoolean(false)

    override suspend fun connect() {
        if (!connectInFlight.compareAndSet(false, true)) return
        try {
            // Set before the probe runs, not after it returns: this state describes right
            // now, while Magisk's dialog is up (or about to be) -- the same instant
            // AdbConnection's own handshake documents as "the device is currently deciding".
            _state.value = TransportState.AwaitingAuthorization
            _state.value = probe()
        } finally {
            connectInFlight.set(false)
        }
    }

    private suspend fun probe(): TransportState =
        when (
            val result = withContext(Dispatchers.IO) {
                runRootCommand(listOf("su", "-c", "id"), PROBE_TIMEOUT_SECONDS)
            }
        ) {
            is RootExecResult.Success -> TransportState.Ready
            // Magisk answered and refused; the user can still change their mind later, so
            // this is Denied, not Unavailable -- see TransportState.Denied's own doc. The
            // probe's own output (just `id`'s text, if any) is irrelevant here.
            is RootExecResult.NonZeroExit -> TransportState.Denied
            // No answer arrived before PROBE_TIMEOUT_SECONDS elapsed. Read the same way
            // AdbConnection reads its own handshake timeout: the dialog is still up, this
            // is not a refusal, so stay in AwaitingAuthorization rather than dropping to
            // Denied or Unavailable.
            RootExecResult.TimedOut -> TransportState.AwaitingAuthorization
            // `su -c id` itself already exited by the time this fired -- the dialog may
            // already be resolved one way or the other -- but the reader thread never
            // confirmed it, so there is nothing here safe to read as a grant or a refusal.
            // Mapped the same as TimedOut, not because the two situations are the same
            // (see RootExecResult.DrainTimedOut's own doc), but because both boil down to
            // "cannot confirm the outcome of this attempt, safe to retry."
            RootExecResult.DrainTimedOut -> TransportState.AwaitingAuthorization
            // su itself could not be started -- there is no root to grant on this device.
            RootExecResult.Unavailable -> TransportState.Unavailable
        }

    /**
     * Deliberately a no-op. [PrivilegedBatterySource]-style `refresh()` calls elsewhere in
     * this package re-check facts with no user-facing side effect -- `AdbShell.refresh()`
     * safely re-dials its socket without raising any prompt, because a rejected TCP
     * connection is silent. There is no equivalently cheap way to re-check root: the
     * only way to know is to run `su`, and running `su` is exactly the unprompted-dialog
     * problem [connect]'s own doc says this class must never trigger on its own. Wiring
     * this to call [connect] would reintroduce that problem on every single `ON_RESUME`.
     * The gateway that decides when [connect] should run decides that directly, not
     * through this method.
     */
    override fun refresh() = Unit

    override suspend fun runDump(): String? = run(CMD_DUMP_BATTERY, ROOT_DUMP_TIMEOUT_SECONDS)

    override suspend fun runCheckin(): String? = run(CMD_DUMP_CHECKIN, ROOT_CHECKIN_TIMEOUT_SECONDS)

    private suspend fun run(command: String, timeoutSeconds: Long): String? {
        // Gated on Ready for the same reason AdbShell gates on it: a transport that was
        // never authorized (or was denied) has no business spawning `su` just because a
        // caller asked for a dump -- that would be another unprompted-dialog surprise.
        if (_state.value != TransportState.Ready) return null
        return when (
            val result = withContext(Dispatchers.IO) {
                runRootCommand(listOf("su", "-c", command), timeoutSeconds)
            }
        ) {
            is RootExecResult.Success -> result.output
            else -> {
                // Live degradation: a Ready transport that just failed must reach `state`
                // with no exception anywhere downstream, so every privileged reading
                // degrades on the repository's next emission rather than on a crash. This
                // treats a non-zero exit the same as a timeout or a missing `su`: this
                // transport has a live TransportState to protect, unlike a stateless shell
                // runner that could safely hand back whatever text a failing command wrote,
                // so it errs toward distrusting a command that did not exit cleanly.
                _state.value = TransportState.Unavailable
                null
            }
        }
    }
}

/**
 * `internal`, not `private`: [runRootCommand] takes an arbitrary argv rather than hardcoding
 * `su`, purely so [RootShellTest] can drive it with `sh` -- the same argv-injection seam
 * this codebase uses anywhere a subprocess needs a JVM-testable substitute for a
 * hardcoded, device-only executable. `su`'s own Magisk-dialog behavior stays untestable off
 * a rooted device either way; only the process-management mechanism below is what gets
 * exercised by proxy.
 */
internal sealed interface RootExecResult {
    data class Success(val output: String) : RootExecResult

    /** Carries whatever the process wrote to its merged stdout/stderr before exiting
     * non-zero -- [probe] discards it (a failed `su -c id` has nothing worth keeping), but
     * a test can assert on it directly to prove the reader thread actually captured output
     * that arrived before a non-zero exit, not just detected the exit code. */
    data class NonZeroExit(val output: String) : RootExecResult
    data object TimedOut : RootExecResult

    /**
     * The process itself exited -- `waitFor` succeeded, so [Success] or [NonZeroExit] would
     * otherwise apply -- but the reader thread had not finished draining the pipe when
     * `READER_DRAIN_TIMEOUT_MS` ran out. `Thread.join(millis)` returning does not mean the
     * thread finished; it can just as easily mean the budget did, and nothing distinguishes
     * those two outcomes without checking `isAlive` afterward. Whatever text is sitting in
     * `output` at that point may be short whatever the process actually wrote, so this
     * carries no output at all rather than risk a caller treating a partial string as
     * complete -- see [runRootCommand]'s own doc for how this is constructed. Deliberately
     * not reusing [TimedOut]: that case means the *process* never exited (Magisk's dialog
     * may still be up); this means the process is already gone and only the reader lagged,
     * a materially different situation that [RootShell.probe] maps the same way today only
     * because both are equally "can't confirm, safe to retry."
     */
    data object DrainTimedOut : RootExecResult
    data object Unavailable : RootExecResult
}

/**
 * Runs [argv] with `ProcessBuilder`, not `Runtime.exec`: `exec` leaves stderr on its own
 * pipe, and `dumpsys batterystats --checkin` writing enough to stderr to fill that pipe
 * while only stdout is drained would block the child forever -- `redirectErrorStream`
 * merges the two so one reader drains everything, matching the shell-protocol-v1 semantics
 * this app's own parsers already assume (a single merged stdout/stderr stream).
 *
 * The reader runs on its own thread, concurrently with [Process.waitFor], rather than
 * reading only after `waitFor` returns: `dumpsys batterystats --checkin`'s output (up to
 * 525KB on the fixture this app was verified against) can exceed the OS pipe buffer
 * between the child and this process (64KB on Linux by default) before the child exits.
 * Waiting for exit before reading anything then deadlocks -- the child blocked on a full
 * pipe it cannot write past, this thread blocked on an exit that will never come while
 * nobody drains that pipe. Two threads, one per blocking call, is what lets both proceed
 * independently; do not collapse
 * this back into a single "waitFor, then read" thread. `waitFor(timeout, unit)`, not the
 * blocking no-arg `waitFor()`, plus `destroyForcibly()` on timeout, is what makes the bound
 * real and guarantees no process survives this call.
 */
internal fun runRootCommand(argv: List<String>, timeoutSeconds: Long): RootExecResult = try {
    val process = ProcessBuilder(argv).redirectErrorStream(true).start()

    val output = StringBuilder()
    val reader = Thread({
        // A failed read (stream closed by destroyForcibly() below, an interrupt) just ends
        // this thread early with whatever was captured so far -- there is no separate error
        // channel back to the caller for this thread's own failures.
        runCatching {
            process.inputStream.bufferedReader().use { streamReader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = streamReader.read(buffer)
                    if (read == -1) break
                    synchronized(output) { output.append(buffer, 0, read) }
                }
            }
        }
    }, "RootShell-reader").apply { isDaemon = true; start() }

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        reader.join(READER_DRAIN_TIMEOUT_MS)
        return RootExecResult.TimedOut
    }
    // The process exiting does not guarantee the reader thread has drained every byte
    // already sitting in the pipe yet -- join, not an immediate read of `output`, is what
    // gives the last chunk a chance to land before this decides success or failure. But
    // `join(millis)` returning is ambiguous by itself: it means either the thread finished
    // or the budget ran out, and only `isAlive` afterward tells those two apart. Skipping
    // that check and reading `output` regardless is exactly how a timed-out join turns a
    // partial dump into a reported `Success`.
    reader.join(READER_DRAIN_TIMEOUT_MS)
    if (reader.isAlive) {
        return RootExecResult.DrainTimedOut
    }
    val text = synchronized(output) { output.toString() }
    if (process.exitValue() != 0) {
        RootExecResult.NonZeroExit(text)
    } else {
        RootExecResult.Success(text)
    }
} catch (e: IOException) {
    // The executable does not exist, or could not be started -- e.g. no `su` binary at
    // all, meaning this device is not rooted.
    RootExecResult.Unavailable
}

package com.mralaminahamed.batteryhealth.data.privileged

import android.os.Process
import java.util.concurrent.TimeUnit

/**
 * The whole reason this app needs Shizuku: `dumpsys battery` requires nothing more than
 * running as the shell UID, but a normal app process is refused before the command ever
 * runs (verified on this device -- see the task report). Shizuku's shell-UID process is
 * exactly what closes that gap.
 *
 * Instantiated by Shizuku itself, by reflection, inside the process it starts under the
 * shell UID -- **never** constructed by this app's own Hilt graph, which is why this has
 * a public no-arg constructor rather than an `@Inject` one. `ComponentName`-only identity
 * (see [ShizukuGateway]'s bind call) is how Shizuku knows which class to load out of this
 * app's own APK once it is running there; there is deliberately no `<service>` entry in
 * AndroidManifest.xml, because the platform's own service manager never starts this.
 */
class PrivilegedBatteryService : IUserService.Stub() {

    /**
     * Runs entirely inside the shell-UID process Shizuku started, so failures here are
     * this process's own -- a crash would kill the privileged tier's only usable process,
     * not just this call. Every failure mode (subprocess spawn refused, stream read
     * interrupted, a hung `dumpsys` that never returns) collapses to `""` for that
     * reason, never a thrown exception and never an unbounded block crossing the Binder
     * call. [DumpsysBatteryParser] treats an empty string the same as any other dump it
     * cannot find its fields in: field-by-field absence, not a crash.
     *
     * Delegates to the top-level [runShellCommandWithTimeout] rather than doing this
     * inline: that function takes no `Binder`/AIDL state and is a free function purely so
     * a JVM test can call it directly. Constructing *this* class outside a real Android
     * runtime -- e.g. from a plain JVM unit test, Robolectric being off the table per this
     * task's own constraints -- throws immediately, because `IUserService.Stub`'s
     * constructor calls `Binder.attachInterface`, and the unmocked `android.jar` unit
     * tests compile against stubs every method body with `throw RuntimeException(...)`.
     * Confirmed the hard way: an earlier version of this fix's own test instantiated
     * `PrivilegedBatteryService()` directly and every test failed on that line before any
     * assertion ran.
     */
    override fun dumpBattery(): String = runShellCommandWithTimeout(listOf("dumpsys", "battery"))

    /**
     * `dumpsys batterystats --checkin`'s own output on the device this was verified
     * against ran ~50x larger than `dumpsys battery`'s (a committed real capture is
     * 525KB, against `dumpsys battery`'s ~11KB -- see [CHECKIN_TIMEOUT_SECONDS]'s own doc
     * for why that difference in size earns a longer, but still bounded, shell-side
     * timeout rather than reusing [DUMP_TIMEOUT_SECONDS] unchanged).
     */
    override fun dumpBatteryStatsCheckin(): String =
        runShellCommandWithTimeout(listOf("dumpsys", "batterystats", "--checkin"), CHECKIN_TIMEOUT_SECONDS)

    /** Shizuku does not reuse a UserService process after this; ending it is safe. */
    override fun destroy() {
        Process.killProcess(Process.myPid())
    }
}

/**
 * `dumpsys battery` on the device this was verified against returns its full ~11KB
 * output in well under a second (see the committed fixture) -- this is generous headroom
 * above that for a slow shell spawn under load, while still bounding how long a
 * genuinely hung `system_server` Binder call can block [PrivilegedBatteryService]'s only
 * usable thread. [ShizukuGateway]'s own timeout on the client side of the Binder call is
 * set comfortably above this one -- see its doc for why the two are not the same bound
 * protecting against the same thing.
 */
internal const val DUMP_TIMEOUT_SECONDS = 3L

/**
 * `dumpsys batterystats --checkin`, called from *inside* this app's own privileged
 * `UserService` process the same way [dumpBattery] is (not over `adb shell`, which is a
 * different process with its own pty and scheduling), measured directly on the device
 * this was verified against: 207-323ms for a live capture of ~94KB -- consistent with,
 * not slower than, the sub-half-second `adb shell` timings this task also measured
 * separately. (An earlier draft of this constant reasoned from the `adb shell` numbers
 * alone and picked 9s, then -- when every real attempt still timed out -- 45s; both were
 * wrong for a completely different reason than payload size, see
 * [runShellCommandWithTimeout]'s own doc for the actual bug those two numbers were
 * papering over rather than fixing.)
 *
 * The committed fixture is ~5.5x larger (525KB) than the live capture actually measured
 * (~94KB, this device's history having pruned itself further by the time of that
 * measurement) -- even a pessimistic linear scaling of the slower 323ms sample gives
 * ~1.8s for 525KB, well short of this bound. **8 seconds** is roughly 4x that
 * extrapolation: headroom for a device with a much longer uptime and proportionally more
 * per-uid/per-package rows than either capture, plus the same slow-shell-spawn-under-load
 * margin [DUMP_TIMEOUT_SECONDS] already budgets for -- not "3x [DUMP_TIMEOUT_SECONDS]"
 * for its own sake, since the two commands' costs do not scale by the same factor as
 * their payload sizes (see the paragraph above: `--checkin`'s cost is dominated by how
 * much in-memory accounting state `system_server` has to walk and serialise, not
 * wall-clock-per-byte).
 */
internal const val CHECKIN_TIMEOUT_SECONDS = 8L

/**
 * How long [runShellCommandWithTimeout] gives a reader thread to finish draining output
 * that was already fully written before the process exited -- the process is already
 * gone by the time this join runs, so this is not a second command timeout, just a bound
 * on how long copying already-produced bytes out of an OS pipe into a `StringBuilder`
 * should ever take. Generous for even the 525KB checkin payload without adding a
 * meaningful delay to the common, much-smaller case.
 */
private const val READER_DRAIN_TIMEOUT_MS = 2_000L

/**
 * `waitFor(timeout, unit)` bounds this call's *overall* wait for the process to exit, but
 * -- unlike an earlier version of this function -- does **not** run before all reading has
 * started, because that ordering has its own, opposite failure mode this task's real
 * on-device verification actually hit: `dumpsys batterystats --checkin`'s output (up to
 * 525KB on the fixture this app was built against) comfortably exceeds the OS pipe
 * buffer between the child process and this one (64KB on Linux by default; confirmed the
 * same failure shape on macOS in this function's own JVM tests). If nothing reads that
 * pipe while this thread only calls `waitFor()`, the child's own `write()` blocks once the
 * buffer fills -- and since this thread is simultaneously waiting for the child to *exit*
 * before it will read anything, neither side can make progress. That is a genuine
 * deadlock, not merely a slow path: confirmed for real running this app on-device, where
 * every single attempt at `dumpsys batterystats --checkin` timed out at whatever bound
 * was configured (9s, then 45s) despite the same command completing in well under half a
 * second every time over a plain `adb shell`, and reproduced deterministically on the JVM
 * test host below by asking a child to write 300,000 bytes and confirming that waiting
 * for exit with nothing draining the pipe times out even on a 5-second bound.
 *
 * The fix is to read concurrently with waiting, on a dedicated thread, so the pipe never
 * has a chance to back up regardless of payload size -- not to raise the timeout, which
 * would have papered over a bug that no timeout value could actually fix (see
 * [CHECKIN_TIMEOUT_SECONDS]'s own doc for the wrong number this bug produced before it
 * was found and fixed here instead). `destroyForcibly()` on a genuine timeout still makes
 * the child's exit certain rather than merely likely, and closes its streams, which is
 * what lets the reader thread's own `read()` return end-of-stream promptly rather than
 * blocking forever on a process that no longer exists.
 *
 * Every failure (the executable does not exist, the timeout fires, the stream read is
 * interrupted) collapses to `""` -- never a thrown exception -- so
 * [PrivilegedBatteryService] never needs its own `try`/`catch` around this.
 *
 * [timeoutSeconds] defaults to [DUMP_TIMEOUT_SECONDS] so every existing call site (and
 * [PrivilegedBatteryServiceTest]'s existing tests, all written against the small dump)
 * keeps its original bound unchanged; [PrivilegedBatteryService.dumpBatteryStatsCheckin]
 * is the one caller that passes [CHECKIN_TIMEOUT_SECONDS] explicitly.
 */
internal fun runShellCommandWithTimeout(
    command: List<String>,
    timeoutSeconds: Long = DUMP_TIMEOUT_SECONDS,
): String = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()

    // A dedicated thread, not "read a chunk, then poll waitFor, then read another
    // chunk" on this thread: a blocking read() and a blocking waitFor() cannot both run
    // on one thread at the same time, and alternating between them on a fixed schedule
    // would just move the same deadlock to whichever one happens to be waiting when the
    // pipe fills. Two threads, one per blocking call, is what actually lets both make
    // progress independently.
    val output = StringBuilder()
    val readerThread = Thread({
        // A failed read (stream closed underneath it by destroyForcibly() below, an
        // interrupt) just ends this thread early with whatever was captured so far --
        // there is no separate error channel back to the caller for this thread's own
        // failures, matching this function's existing every-failure-collapses-to-"" (or,
        // here, to whatever partial text is already in `output`) contract.
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read == -1) break
                    synchronized(output) { output.append(buffer, 0, read) }
                }
            }
        }
    }, "runShellCommandWithTimeout-reader")
    readerThread.isDaemon = true
    readerThread.start()

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        readerThread.join(READER_DRAIN_TIMEOUT_MS)
        return@runCatching ""
    }
    // The process exiting does not guarantee the reader thread has drained every byte
    // already sitting in the pipe yet -- join, not an immediate read of `output`, is what
    // ensures the last chunk is actually in hand before this returns.
    readerThread.join(READER_DRAIN_TIMEOUT_MS)
    synchronized(output) { output.toString() }
}.getOrDefault("")

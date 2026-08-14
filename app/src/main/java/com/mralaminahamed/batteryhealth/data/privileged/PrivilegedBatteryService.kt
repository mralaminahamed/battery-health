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
 * `waitFor(timeout, unit)` runs *before* the read, not after: reading the stream first
 * (as this used to) blocks on the command producing output or closing its stream, which a
 * `dumpsys` wedged on a hung `system_server` Binder call may never do -- that read would
 * have been the actually-unbounded wait, not the `waitFor()` that used to run after it.
 * Timing out the wait, not the read, closes that gap. `destroyForcibly()` on timeout is
 * what makes the shell process's own exit (and this call returning `""`) certain rather
 * than merely likely.
 *
 * Every failure (the executable does not exist, the timeout fires, the stream read is
 * interrupted) collapses to `""` -- never a thrown exception -- so
 * [PrivilegedBatteryService.dumpBattery] never needs its own `try`/`catch` around this.
 */
internal fun runShellCommandWithTimeout(command: List<String>): String = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(DUMP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching ""
    }
    process.inputStream.bufferedReader().use { it.readText() }
}.getOrDefault("")

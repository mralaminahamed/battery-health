package com.mralaminahamed.batteryhealth.data.privileged

import android.os.Process

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
     * interrupted) collapses to `""` for that reason, never a thrown exception crossing
     * the Binder call. [DumpsysBatteryParser] treats an empty string the same as any
     * other dump it cannot find its fields in: field-by-field absence, not a crash.
     */
    override fun dumpBattery(): String = runCatching {
        ProcessBuilder("dumpsys", "battery")
            .redirectErrorStream(true)
            .start()
            .let { process ->
                val text = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                text
            }
    }.getOrDefault("")

    /** Shizuku does not reuse a UserService process after this; ending it is safe. */
    override fun destroy() {
        Process.killProcess(Process.myPid())
    }
}

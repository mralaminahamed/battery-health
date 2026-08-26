package com.alaminahamed.batteryhealth.data.apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.health.HealthStats
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import com.alaminahamed.batteryhealth.domain.AppBucket
import com.alaminahamed.batteryhealth.domain.AppCpuTime
import com.alaminahamed.batteryhealth.domain.UidKind
import com.alaminahamed.batteryhealth.domain.Reading
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-uid CPU time from `SystemHealthManager`, with no shell involved.
 *
 * This is the only public route to per-app usage. `BatteryStatsManager`,
 * `BatteryUsageStats` and `UidBatteryConsumer` are absent from the public SDK entirely --
 * checked against the API 37 `android.jar`, not assumed -- so the shell's
 * `dumpsys batterystats` has no public equivalent.
 *
 * ## Time, not power, and that is a finding rather than a shortcut
 *
 * `takeUidSnapshot` does define power buckets, and on real hardware they are empty:
 * `MEASUREMENT_CPU_POWER_MAMS` reads 0 for every uid and the wifi, mobile and bluetooth
 * equivalents are absent. The CPU time buckets are populated and genuinely differentiated.
 * So this reports time and says so; converting it to mAh with `power_profile.xml`
 * coefficients -- which is exactly what Android's own "estimated power use" does -- would
 * put a model next to measurements without the difference being visible.
 *
 * ## What it needs
 *
 * `BATTERY_STATS` for any uid but this app's own. Verified by control: with the permission
 * granted the call returns data for other uids; revoked, the same call throws
 * `SecurityException: Neither user nor current process has android.permission.BATTERY_STATS`.
 */
@Singleton
class UidCpuTimeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val health: SystemHealthManager?
        get() = context.getSystemService(SystemHealthManager::class.java)

    private val granted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.BATTERY_STATS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One entry per visible uid that reported any CPU time.
     *
     * [Reading.NeedsPrivilegedAccess] when the permission is absent, which is accurate
     * rather than merely hopeful: granting it genuinely produces this data, and the app
     * can tell the user the exact command.
     *
     * The uid list comes from `PackageManager`, so it is bounded by package visibility.
     * Without `QUERY_ALL_PACKAGES` -- which Play does not approve for a battery tool --
     * that is a fraction of what is installed, and the shares this produces are of the
     * visible rows rather than of the device. The alternative would be presenting a
     * partial list as complete.
     */
    fun cpuTimes(): Reading<List<AppCpuTime>> {
        if (!granted) return Reading.NeedsPrivilegedAccess
        val manager = health ?: return Reading.Unsupported

        val packagesByUid = try {
            context.packageManager.getInstalledApplications(0)
                .groupBy({ it.uid }, { it.packageName })
        } catch (t: Throwable) {
            return Reading.Unsupported
        }

        val entries = packagesByUid.mapNotNull { (uid, packages) ->
            // Each uid is read independently: one refusal or one vendor implementation
            // throwing must not cost the whole list, which is the same reasoning the
            // discovery sweep applies per property.
            val stats = runCatching { manager.takeUidSnapshot(uid) }.getOrNull() ?: return@mapNotNull null
            val user = stats.measurementOrZero(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS)
            val system = stats.measurementOrZero(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS)
            if (user <= 0L && system <= 0L) return@mapNotNull null
            val kind = UidKind.of(uid)
            AppCpuTime(
                uid = uid,
                userCpuMs = user,
                systemCpuMs = system,
                kind = kind,
                packages = packages,
                bucket = AppBucket.of(kind, hasLauncherEntry = packages.any(::isLaunchable)),
            )
        }
        return if (entries.isEmpty()) Reading.NotYetMeasured else Reading.Available(entries, com.alaminahamed.batteryhealth.domain.Source.Framework)
    }

    /**
     * `getMeasurement` throws for a key the device does not populate, so presence is
     * checked first. Absent is reported as zero rather than propagated: a bucket this
     * device does not keep contributes nothing to a total, and treating it as a failure
     * would discard the buckets it does keep.
     */
    /**
     * Whether a package can be opened from the launcher.
     *
     * `getLaunchIntentForPackage` needs the package to be visible to this app, which it
     * is: the name came from `getInstalledApplications`, so anything it returns has
     * already passed the same visibility filter. A throw or a null both mean "not
     * launchable", which is the answer either way.
     */
    private fun isLaunchable(packageName: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
            .getOrDefault(false)

    private fun HealthStats.measurementOrZero(key: Int): Long =
        runCatching { if (hasMeasurement(key)) getMeasurement(key) else 0L }.getOrDefault(0L)
}

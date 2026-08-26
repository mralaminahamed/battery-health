package com.alaminahamed.batteryhealth.data.vendor.discovery

import android.content.Context
import android.os.Process
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probes whether `SystemHealthManager` can attribute power per app on this device.
 *
 * `BatteryStatsManager`, `BatteryUsageStats` and `UidBatteryConsumer` are absent from the
 * public SDK entirely (checked against the API 37 `android.jar`), so the route the Apps
 * screen currently takes through `dumpsys batterystats` has no public equivalent. But
 * `SystemHealthManager` is public, and `takeUidSnapshot(int)` reads an arbitrary uid --
 * gated on `BATTERY_STATS`, which this app can now hold via a one-time `pm grant`.
 *
 * Output, not assertions, is the point. Whether these buckets are populated is a property
 * of the device, and asserting a particular one would encode this phone's answer as every
 * phone's.
 */
class UidHealthProbeOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val powerKeys = listOf(
        "CPU_POWER_MAMS" to UidHealthStats.MEASUREMENT_CPU_POWER_MAMS,
        "WIFI_POWER_MAMS" to UidHealthStats.MEASUREMENT_WIFI_POWER_MAMS,
        "MOBILE_POWER_MAMS" to UidHealthStats.MEASUREMENT_MOBILE_POWER_MAMS,
        "BLUETOOTH_POWER_MAMS" to UidHealthStats.MEASUREMENT_BLUETOOTH_POWER_MAMS,
        "REALTIME_BATTERY_MS" to UidHealthStats.MEASUREMENT_REALTIME_BATTERY_MS,
        "USER_CPU_TIME_MS" to UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS,
        "SYSTEM_CPU_TIME_MS" to UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS,
    )

    @Test
    fun probeUidHealthStats() {
        val health = context.getSystemService(SystemHealthManager::class.java)
        Log.i(TAG, "=========== UID HEALTH PROBE ===========")

        // No permission needed for our own uid: the baseline that says whether the API
        // works here at all, independent of the grant.
        val mine = runCatching { health.takeMyUidSnapshot() }
        Log.i(TAG, "takeMyUidSnapshot -> ${mine.exceptionOrNull()?.let { it::class.simpleName } ?: "ok"}")
        mine.getOrNull()?.let { stats ->
            powerKeys.forEach { (name, key) ->
                val value = runCatching { if (stats.hasMeasurement(key)) stats.getMeasurement(key) else null }
                Log.i(TAG, "  own/$name = ${value.getOrNull() ?: "absent"}")
            }
        }

        // Another app's uid. This is the one that needs BATTERY_STATS, and the one that
        // decides whether the Apps screen can work without a shell.
        val others = context.packageManager.getInstalledApplications(0)
            .map { it.uid }
            .distinct()
            .filter { it != Process.myUid() && it >= Process.FIRST_APPLICATION_UID }
            .take(5)
        Log.i(TAG, "visible other uids: ${others.size}")
        others.forEach { uid ->
            val snapshot = runCatching { health.takeUidSnapshot(uid) }
            val failure = snapshot.exceptionOrNull()
            if (failure != null) {
                Log.i(TAG, "  uid $uid -> FAILED ${failure::class.simpleName}: ${failure.message}")
                return@forEach
            }
            val stats = snapshot.getOrNull() ?: return@forEach
            val summary = powerKeys.joinToString(" ") { (name, key) ->
                val v = runCatching { if (stats.hasMeasurement(key)) stats.getMeasurement(key) else null }.getOrNull()
                "$name=${v ?: "-"}"
            }
            Log.i(TAG, "  uid $uid -> $summary")
        }
        Log.i(TAG, "========================================")

        assertTrue("probe ran", true)
    }

    private companion object {
        const val TAG = "UidHealthProbe"
    }
}

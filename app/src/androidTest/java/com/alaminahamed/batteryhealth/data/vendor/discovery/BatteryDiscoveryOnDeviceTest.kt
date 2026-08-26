package com.alaminahamed.batteryhealth.data.vendor.discovery

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.PowerProfileReader
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the discovery sweep on whatever device is attached and prints the whole report.
 *
 * This is the only test in the suite whose value is its *output* rather than its
 * assertions. Everything else about the sweep is proved on the JVM with injected readers;
 * what no JVM test can answer is what a real platform actually returns — whether this
 * phone withholds state of health or hands it over, which battery extras its vendor adds
 * beyond AOSP's, and whether its power profile was filled in.
 *
 * Read it with:
 *
 * ```
 * adb logcat -s BatteryDiscovery
 * ```
 *
 * Assertions are deliberately minimal and device-independent. Asserting that a particular
 * property is readable would encode one phone's answer as the expected behaviour of every
 * phone, which is the assumption this whole feature exists to stop making.
 */
class BatteryDiscoveryOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sweepThisDeviceAndPrintTheReport() {
        val identity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
        )
        val discovery = BatteryDiscovery(
            context = context,
            batteryManager = context.getSystemService(BatteryManager::class.java),
            powerManager = context.getSystemService(PowerManager::class.java),
            identity = identity,
        )

        val report = discovery.sweep()

        Log.i(TAG, "================ BATTERY DISCOVERY ================")
        Log.i(TAG, "manufacturer=${identity.manufacturer} brand=${identity.brand}")
        Log.i(TAG, "model=${identity.model} device=${identity.device}")
        Log.i(TAG, "vendor=${identity.vendor} sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
        Log.i(TAG, "powerProfileCapacityMah=${PowerProfileReader(context).batteryCapacityMah()}")

        for (channel in ProbeChannel.entries) {
            val rows = report.of(channel)
            Log.i(TAG, "---- $channel (${rows.size}) ----")
            for (row in rows) {
                Log.i(TAG, "  ${row.key} = ${render(row.outcome)}")
            }
        }

        val soh = report.of(ProbeChannel.Property)
            .firstOrNull { it.key == BatteryPropertyId.StateOfHealth.name }
        Log.i(TAG, "==== STATE OF HEALTH: ${soh?.outcome?.let(::render)} ====")
        Log.i(TAG, "denied=${report.denied.map { it.key }}")
        Log.i(TAG, "===================================================")

        // The sweep must produce something on any device: the six public properties are
        // always asked, whatever they answer.
        assertTrue("sweep returned nothing at all", report.results.isNotEmpty())
    }

    private fun render(outcome: ProbeOutcome): String = when (outcome) {
        is ProbeOutcome.Value -> outcome.raw
        ProbeOutcome.Absent -> "<absent>"
        ProbeOutcome.Denied -> "<denied: needs BATTERY_STATS>"
        is ProbeOutcome.Failed -> "<failed: ${outcome.reason}>"
    }

    private companion object {
        const val TAG = "BatteryDiscovery"
    }
}

package com.alaminahamed.batteryhealth.data.vendor.discovery

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sweeps a wide range of `BATTERY_PROPERTY` ids looking for anything AOSP does not define.
 *
 * AOSP defines 1..15 and none of them is a cycle count. A vendor is free to answer ids
 * outside that range from its own health HAL, and this app has never asked -- so "the
 * system does not expose a cycle count" was a claim about AOSP, not about this phone.
 *
 * Output, not assertions. A vendor id that answers here is a finding about one device and
 * must not become an assumption about others.
 */
class WidePropertySweepOnDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sweepEveryPropertyIdTheVendorMightAnswer() {
        val bm = context.getSystemService(BatteryManager::class.java)
        val known = BatteryPropertyId.entries.associate { it.id to it.name }
        Log.i(TAG, "=========== WIDE PROPERTY SWEEP ===========")

        for (id in 0..200) {
            val numeric = runCatching { bm.getLongProperty(id) }
            val text = runCatching { bm.getStringProperty(id) }

            val n = numeric.getOrNull()
            val t = text.getOrNull()
            val interesting = (n != null && n != Long.MIN_VALUE && n != -1L) ||
                (t != null && t.isNotBlank())
            if (!interesting) continue

            val label = known[id] ?: "UNDOCUMENTED"
            Log.i(TAG, "id=$id [$label] long=${n ?: "-"} string=${t ?: "-"}")
        }
        Log.i(TAG, "==========================================")
        assertTrue("sweep ran", true)
    }

    private companion object {
        const val TAG = "WideSweep"
    }
}

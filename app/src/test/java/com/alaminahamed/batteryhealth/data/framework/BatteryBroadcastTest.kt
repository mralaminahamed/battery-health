package com.alaminahamed.batteryhealth.data.framework

import android.os.BatteryManager
import com.alaminahamed.batteryhealth.domain.ChargeState
import com.alaminahamed.batteryhealth.domain.PlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryBroadcastTest {

    private fun broadcast(
        level: Int = 41,
        scale: Int = 100,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
        plugged: Int = BatteryManager.BATTERY_PLUGGED_USB,
        voltageMv: Int = 3955,
        temperatureDeciC: Int = 371,
        technology: String? = "Li-ion",
        present: Boolean = true,
        cycleCount: Int = 0,
    ) = BatteryBroadcast.fromExtras(
        level, scale, status, plugged, voltageMv, temperatureDeciC, technology, present, cycleCount,
    )

    @Test
    fun levelIsScaledToPercent() {
        assertEquals(41, broadcast(level = 41, scale = 100).levelPct)
        assertEquals(50, broadcast(level = 128, scale = 255).levelPct)
    }

    @Test
    fun missingLevelOrScaleYieldsNoLevel() {
        assertNull(broadcast(level = -1).levelPct)
        assertNull(broadcast(scale = 0).levelPct)
        assertNull(broadcast(scale = -1).levelPct)
    }

    @Test
    fun statusMapsToChargeState() {
        assertEquals(ChargeState.Charging, broadcast(status = BatteryManager.BATTERY_STATUS_CHARGING).chargeState)
        assertEquals(ChargeState.Discharging, broadcast(status = BatteryManager.BATTERY_STATUS_DISCHARGING).chargeState)
        assertEquals(ChargeState.Full, broadcast(status = BatteryManager.BATTERY_STATUS_FULL).chargeState)
        assertEquals(ChargeState.NotCharging, broadcast(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING).chargeState)
        assertEquals(ChargeState.Unknown, broadcast(status = BatteryManager.BATTERY_STATUS_UNKNOWN).chargeState)
    }

    @Test
    fun plugFlagMapsToPlugType() {
        assertEquals(PlugType.None, broadcast(plugged = 0).plugType)
        assertEquals(PlugType.Ac, broadcast(plugged = BatteryManager.BATTERY_PLUGGED_AC).plugType)
        assertEquals(PlugType.Usb, broadcast(plugged = BatteryManager.BATTERY_PLUGGED_USB).plugType)
        assertEquals(PlugType.Wireless, broadcast(plugged = BatteryManager.BATTERY_PLUGGED_WIRELESS).plugType)
    }

    @Test
    fun invalidVoltageAndTemperatureBecomeNull() {
        assertNull(broadcast(voltageMv = -1).voltageMv)
        assertNull(broadcast(voltageMv = 0).voltageMv)
        assertNull(broadcast(temperatureDeciC = Int.MIN_VALUE).temperatureDeciC)
    }

    @Test
    fun zeroCycleCountIsTreatedAsUnknown() {
        // The A35 reports cycle_count:0 in every broadcast, which means "not tracked"
        // rather than "a brand new battery". Reporting 0 cycles would be a lie.
        assertNull(broadcast(cycleCount = 0).cycleCount)
        assertEquals(142, broadcast(cycleCount = 142).cycleCount)
    }
}

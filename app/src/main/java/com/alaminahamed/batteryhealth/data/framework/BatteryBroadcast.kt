package com.alaminahamed.batteryhealth.data.framework

import android.os.BatteryManager
import com.alaminahamed.batteryhealth.domain.ChargeState
import com.alaminahamed.batteryhealth.domain.PlugType

/**
 * The sticky ACTION_BATTERY_CHANGED payload, already sanitised. Extraction from the
 * Intent lives in [BatteryBroadcastSource]; everything interpretive is here so it can
 * be tested on the JVM.
 */
data class BatteryBroadcast(
    val levelPct: Int?,
    val chargeState: ChargeState,
    val plugType: PlugType,
    val voltageMv: Int?,
    val temperatureDeciC: Int?,
    val technology: String?,
    val present: Boolean,
    val cycleCount: Int?,
) {
    companion object {
        fun fromExtras(
            level: Int,
            scale: Int,
            status: Int,
            plugged: Int,
            voltageMv: Int,
            temperatureDeciC: Int,
            technology: String?,
            present: Boolean,
            cycleCount: Int,
        ): BatteryBroadcast = BatteryBroadcast(
            levelPct = if (level < 0 || scale <= 0) null else level * 100 / scale,
            chargeState = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.Charging
                BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeState.Discharging
                BatteryManager.BATTERY_STATUS_FULL -> ChargeState.Full
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeState.NotCharging
                else -> ChargeState.Unknown
            },
            plugType = when {
                plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.Ac
                plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.Usb
                plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.Wireless
                plugged and PLUGGED_DOCK != 0 -> PlugType.Dock
                else -> PlugType.None
            },
            voltageMv = voltageMv.takeIf { it > 0 },
            temperatureDeciC = temperatureDeciC.takeIf { it != Int.MIN_VALUE && it > -900 },
            technology = technology?.takeIf { it.isNotBlank() },
            present = present,
            // A reported 0 means the platform does not track cycles on this device,
            // not that the battery has never been charged.
            cycleCount = cycleCount.takeIf { it > 0 },
        )

        /** BatteryManager.BATTERY_PLUGGED_DOCK, inlined because it is API 33+. */
        private const val PLUGGED_DOCK = 8
    }
}

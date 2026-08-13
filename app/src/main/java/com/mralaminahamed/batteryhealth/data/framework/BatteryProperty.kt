package com.mralaminahamed.batteryhealth.data.framework

import android.os.BatteryManager

/** Abstracts BatteryManager.getIntProperty so the probe is unit-testable. */
fun interface IntPropertyReader {
    fun read(propertyId: Int): Int
}

/**
 * Each integer property with the API level that introduced it. Values are compile-time
 * constants, so referencing them from JVM tests is safe.
 *
 * StateOfHealth, ManufacturingDate and FirstUsageDate are inlined as int literals rather
 * than referencing the BatteryManager constants directly: this project's compileSdk 37
 * android.jar does not expose BATTERY_PROPERTY_STATE_OF_HEALTH, and
 * BATTERY_PROPERTY_MANUFACTURING_DATE / BATTERY_PROPERTY_FIRST_USAGE_DATE are @SystemApi
 * and @hide in AOSP (frameworks/base/core/java/android/os/BatteryManager.java) — gated
 * behind the signature-level BATTERY_STATS permission and never present in the public
 * SDK stub at any API level, not just below their documented API floor. Ids verified
 * against the AOSP source: CHARGE_COUNTER=1, CURRENT_NOW=2, CURRENT_AVERAGE=3,
 * MANUFACTURING_DATE=7, FIRST_USAGE_DATE=8, STATE_OF_HEALTH=10.
 */
enum class BatteryProperty(val id: Int, val minSdk: Int) {
    ChargeCounter(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 21),
    CurrentNow(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, 21),
    CurrentAverage(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, 21),
    StateOfHealth(10, 35),
    ManufacturingDate(7, 36),
    FirstUsageDate(8, 36),
}

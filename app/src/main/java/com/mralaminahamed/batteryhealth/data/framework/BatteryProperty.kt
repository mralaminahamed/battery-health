package com.mralaminahamed.batteryhealth.data.framework

import android.os.BatteryManager

/** Abstracts BatteryManager.getIntProperty so the probe is unit-testable. */
fun interface IntPropertyReader {
    fun read(propertyId: Int): Int
}

/**
 * Each integer property this app can read as a normal, unprivileged app. Values are
 * compile-time constants, so referencing them from JVM tests is safe.
 *
 * StateOfHealth, ManufacturingDate and FirstUsageDate deliberately do not appear here:
 * they are @SystemApi/@hide in AOSP (frameworks/base/core/java/android/os/BatteryManager.java),
 * gated behind the signature-level BATTERY_STATS permission, and throw SecurityException
 * for this app on real hardware regardless of API level. They belong to the privileged
 * (Shizuku) tier, not the framework layer — a later task wires them to
 * Reading.NeedsShizuku on BatterySnapshot. Every property remaining here is API 21, well
 * below this app's minSdk 26, so there is no API-floor filtering left to do.
 */
enum class BatteryProperty(val id: Int) {
    ChargeCounter(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
    CurrentNow(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
    CurrentAverage(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
}

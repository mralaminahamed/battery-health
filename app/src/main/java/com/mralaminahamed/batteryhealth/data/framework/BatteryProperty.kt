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

/**
 * Whether a raw `getIntProperty` result is data rather than a sentinel, for this
 * property specifically. The rule is per-property, not global, and both
 * [CapabilityProbe] (which samples each property once, at startup) and
 * [BatteryManagerSource] (which reads properties on every call afterwards) share this
 * one definition rather than each re-deriving it -- a fix that repeats the check in a
 * second place is exactly how this codebase reintroduced its own already-fixed defect
 * one layer down.
 *
 * -1 is the documented "unsupported" value for [ChargeCounter] (charge never goes
 * negative), but [CurrentNow] and [CurrentAverage] can genuinely read -1 microamps -- a
 * tiny real draw, observed for real on the Galaxy A35 this app was verified against.
 * Folding both sentinels into one check ahead of a per-property branch disqualifies
 * current every time it happens to land on -1, which is exactly the defect Task 4 found
 * on real hardware: the wattage metric silently disabled for the whole process
 * lifetime. Only the unambiguous [Int.MIN_VALUE] sentinel disqualifies current.
 */
fun BatteryProperty.isPlausibleReading(raw: Int): Boolean = when (this) {
    BatteryProperty.CurrentNow, BatteryProperty.CurrentAverage -> raw != Int.MIN_VALUE
    BatteryProperty.ChargeCounter -> raw != Int.MIN_VALUE && raw != -1 && raw > 0
}

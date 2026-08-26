package com.alaminahamed.batteryhealth.data.framework

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
 * they are @SystemApi/@hide in AOSP (frameworks/base/core/java/android/os/BatteryManager.java)
 * and gated behind BATTERY_STATS, so they throw SecurityException for an ordinary install.
 *
 * "Regardless of API level" is what this comment used to say next, and that was wrong.
 * BATTERY_STATS is signature|privileged|**development**, and the development flag means
 * adb can grant it directly -- after which all three read normally, verified on real
 * hardware. `GrantedBatterySource` is where that route lives. They stay out of this enum
 * because this one describes what an app can read with nothing granted at all — a later task wires them to
 * Reading.NeedsPrivilegedAccess on BatterySnapshot. Every property remaining here is API 21, well
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

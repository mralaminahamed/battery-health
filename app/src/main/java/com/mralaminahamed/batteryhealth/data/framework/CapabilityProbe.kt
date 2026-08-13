package com.mralaminahamed.batteryhealth.data.framework

/**
 * Queries every battery property once at startup and records which ones returned a
 * real value rather than a sentinel. Nothing else in the app re-probes, so a property
 * that lies once cannot intermittently reappear.
 *
 * A handful of properties (StateOfHealth, ManufacturingDate, FirstUsageDate) are gated
 * behind the signature-level BATTERY_STATS permission on real hardware; a reader backed
 * by the real BatteryManager throws SecurityException for them rather than returning a
 * sentinel. That is just another way of saying "not available to this app", so it is
 * treated the same as Int.MIN_VALUE/-1 rather than crashing the probe.
 */
class CapabilityProbe(
    private val reader: IntPropertyReader,
    private val sdkInt: Int,
) {
    fun probe(): Set<BatteryProperty> = BatteryProperty.entries
        .filter { sdkInt >= it.minSdk }
        .filter { property -> isPlausible(property, readOrSentinel(property)) }
        .toSet()

    private fun readOrSentinel(property: BatteryProperty): Int =
        try {
            reader.read(property.id)
        } catch (e: SecurityException) {
            Int.MIN_VALUE
        }

    private fun isPlausible(property: BatteryProperty, raw: Int): Boolean {
        if (raw == Int.MIN_VALUE || raw == -1) return false
        return when (property) {
            // Discharge current is legitimately negative, and zero is a real reading.
            BatteryProperty.CurrentNow, BatteryProperty.CurrentAverage -> true
            BatteryProperty.StateOfHealth -> raw in 1..100
            BatteryProperty.ChargeCounter -> raw > 0
            BatteryProperty.ManufacturingDate, BatteryProperty.FirstUsageDate -> raw > 0
        }
    }
}

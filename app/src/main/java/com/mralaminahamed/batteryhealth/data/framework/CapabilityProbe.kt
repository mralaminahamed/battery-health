package com.mralaminahamed.batteryhealth.data.framework

/**
 * Queries every battery property once at startup and records which ones returned a
 * real value rather than a sentinel. Nothing else in the app re-probes, so a property
 * that lies once cannot intermittently reappear.
 *
 * The reader can also throw SecurityException — a real BatteryManager denies some
 * properties to unprivileged apps outright rather than returning a sentinel. That is
 * just another way of saying "not available to this app", so it is caught and treated
 * the same as any other unsupported reading rather than crashing the probe.
 */
class CapabilityProbe(
    private val reader: IntPropertyReader,
) {
    fun probe(): Set<BatteryProperty> = BatteryProperty.entries
        .filter { property -> isPlausible(property, readOrSentinel(property)) }
        .toSet()

    private fun readOrSentinel(property: BatteryProperty): Int =
        try {
            reader.read(property.id)
        } catch (e: SecurityException) {
            Int.MIN_VALUE
        }

    /**
     * The sentinel rule is per-property, not global: -1 is the documented "unsupported"
     * value for ChargeCounter, but current can genuinely read -1 microamps (a tiny real
     * draw), so only the unambiguous Int.MIN_VALUE sentinel disqualifies it. Folding both
     * sentinels into one check ahead of this `when` would silently disable current
     * whenever a real reading happened to land on -1.
     */
    private fun isPlausible(property: BatteryProperty, raw: Int): Boolean = when (property) {
        BatteryProperty.CurrentNow, BatteryProperty.CurrentAverage -> raw != Int.MIN_VALUE
        BatteryProperty.ChargeCounter -> raw != Int.MIN_VALUE && raw != -1 && raw > 0
    }
}

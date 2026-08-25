package com.alaminahamed.batteryhealth.data.framework

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
        .filter { property -> property.isPlausibleReading(readOrSentinel(property)) }
        .toSet()

    private fun readOrSentinel(property: BatteryProperty): Int =
        try {
            reader.read(property.id)
        } catch (e: SecurityException) {
            Int.MIN_VALUE
        }
}

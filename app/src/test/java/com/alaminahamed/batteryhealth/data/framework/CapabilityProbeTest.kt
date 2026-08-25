package com.alaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeTest {

    private fun probeWith(values: Map<Int, Int>): Set<BatteryProperty> =
        CapabilityProbe(
            reader = IntPropertyReader { id -> values[id] ?: Int.MIN_VALUE },
        ).probe()

    @Test
    fun minValueSentinelMeansUnsupported() {
        val supported = probeWith(mapOf(BatteryProperty.ChargeCounter.id to Int.MIN_VALUE))
        assertFalse(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun minusOneSentinelMeansChargeCounterUnsupported() {
        val supported = probeWith(mapOf(BatteryProperty.ChargeCounter.id to -1))
        assertFalse(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun realValueMeansSupported() {
        val supported = probeWith(mapOf(BatteryProperty.ChargeCounter.id to 2_095_000))
        assertTrue(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun negativeCurrentIsValidBecauseDischargeIsNegative() {
        val supported = probeWith(mapOf(BatteryProperty.CurrentNow.id to -450_000))
        assertTrue(BatteryProperty.CurrentNow in supported)
    }

    @Test
    fun minusOneIsAPlausibleCurrentReadingUnlikeChargeCounter() {
        // Regression pin: CURRENT_NOW read exactly -1 on the real A35 while idle on a
        // charger. The old global sentinel check (raw == -1 -> unsupported, checked
        // before the per-property branch) disqualified it, which silently disabled the
        // wattage metric for the whole process lifetime. -1 is the documented
        // "unsupported" value for ChargeCounter, but a real, if tiny, current reading.
        val supported = probeWith(mapOf(BatteryProperty.CurrentNow.id to -1))
        assertTrue(BatteryProperty.CurrentNow in supported)
    }

    @Test
    fun securityExceptionMeansUnsupportedRatherThanCrashing() {
        val reader = IntPropertyReader { throw SecurityException("no BATTERY_STATS") }
        val supported = CapabilityProbe(reader).probe()
        assertTrue(supported.isEmpty())
    }
}

package com.mralaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProbeTest {

    private fun probeWith(sdkInt: Int, values: Map<Int, Int>): Set<BatteryProperty> =
        CapabilityProbe(
            reader = IntPropertyReader { id -> values[id] ?: Int.MIN_VALUE },
            sdkInt = sdkInt,
        ).probe()

    @Test
    fun propertiesAboveTheApiFloorAreNotEvenQueried() {
        var queried = 0
        val probe = CapabilityProbe(
            reader = IntPropertyReader { queried++; 5000 },
            sdkInt = 26,
        )
        val supported = probe.probe()
        assertFalse(BatteryProperty.StateOfHealth in supported)
        assertFalse(BatteryProperty.FirstUsageDate in supported)
        // Only the three API 21 properties are eligible on API 26.
        assertEquals(3, queried)
    }

    @Test
    fun minValueSentinelMeansUnsupported() {
        val supported = probeWith(36, mapOf(BatteryProperty.ChargeCounter.id to Int.MIN_VALUE))
        assertFalse(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun minusOneSentinelMeansUnsupported() {
        val supported = probeWith(36, mapOf(BatteryProperty.ChargeCounter.id to -1))
        assertFalse(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun realValueMeansSupported() {
        val supported = probeWith(36, mapOf(BatteryProperty.ChargeCounter.id to 2_095_000))
        assertTrue(BatteryProperty.ChargeCounter in supported)
    }

    @Test
    fun stateOfHealthOutsideOneToHundredIsRejected() {
        assertFalse(BatteryProperty.StateOfHealth in probeWith(36, mapOf(BatteryProperty.StateOfHealth.id to 0)))
        assertFalse(BatteryProperty.StateOfHealth in probeWith(36, mapOf(BatteryProperty.StateOfHealth.id to 101)))
        assertTrue(BatteryProperty.StateOfHealth in probeWith(36, mapOf(BatteryProperty.StateOfHealth.id to 86)))
    }

    @Test
    fun negativeCurrentIsValidBecauseDischargeIsNegative() {
        val supported = probeWith(36, mapOf(BatteryProperty.CurrentNow.id to -450_000))
        assertTrue(BatteryProperty.CurrentNow in supported)
    }
}

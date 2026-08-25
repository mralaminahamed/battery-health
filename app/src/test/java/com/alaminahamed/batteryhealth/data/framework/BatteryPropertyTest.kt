package com.alaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isPlausibleReading` is the one rule `CapabilityProbe` (samples each property once,
 * at startup) and `BatteryManagerSource` (reads properties on every call afterwards)
 * now share. These tests exercise it directly, at the level both call sites delegate
 * to, since `BatteryManagerSource` itself needs a real `android.os.BatteryManager` and
 * cannot force a specific raw value out of real hardware on demand.
 */
class BatteryPropertyTest {

    @Test
    fun negativeOneDisqualifiesChargeCounterButNotCurrentNowOnAPostProbeRead() {
        // -1 is the documented "unsupported" sentinel for ChargeCounter specifically --
        // charge never goes negative -- but CURRENT_NOW genuinely read -1 on the real
        // Galaxy A35 while idle on a charger (Task 4's own on-device evidence). A
        // property that already passed CapabilityProbe's startup check and later reads
        // -1 must still resolve per-property, not through a single global check -- that
        // asymmetry is the whole point of sharing this rule rather than each call site
        // re-deriving its own.
        assertFalse(BatteryProperty.ChargeCounter.isPlausibleReading(-1))
        assertTrue(BatteryProperty.CurrentNow.isPlausibleReading(-1))
        assertTrue(BatteryProperty.CurrentAverage.isPlausibleReading(-1))
    }

    @Test
    fun minValueDisqualifiesEveryPropertyRegardlessOfWhichOne() {
        BatteryProperty.entries.forEach { property ->
            assertFalse(property.isPlausibleReading(Int.MIN_VALUE))
        }
    }

    @Test
    fun zeroAndNegativeDisqualifyChargeCounterButAPositiveValuePasses() {
        assertFalse(BatteryProperty.ChargeCounter.isPlausibleReading(0))
        assertFalse(BatteryProperty.ChargeCounter.isPlausibleReading(-2_000))
        assertTrue(BatteryProperty.ChargeCounter.isPlausibleReading(2_095_000))
    }
}

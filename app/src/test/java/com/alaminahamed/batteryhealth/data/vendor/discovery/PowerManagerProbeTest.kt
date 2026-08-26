package com.alaminahamed.batteryhealth.data.vendor.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerManagerProbeTest {

    private fun outcome(
        results: List<ProbeResult>,
        reading: PowerManagerProbe.Reading,
    ) = results.first { it.key == reading.key }.outcome

    @Test
    fun everyReadingAppearsOnThePowerManagerChannel() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 36) { "1" }
        assertEquals(PowerManagerProbe.Reading.entries.size, results.size)
        assertTrue(results.all { it.channel == ProbeChannel.PowerManager })
    }

    @Test
    fun aReadingIsRecordedAsAValue() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 36) { reading ->
            if (reading == PowerManagerProbe.Reading.ThermalStatus) "2" else null
        }
        assertEquals(ProbeOutcome.Value("2"), outcome(results, PowerManagerProbe.Reading.ThermalStatus))
    }

    /**
     * Below its API floor the platform genuinely does not have the reading, which is an
     * ordinary fact about an older device rather than something going wrong. Recording it
     * as a failure would fill the report with alarming rows on every Android 8 phone.
     */
    @Test
    fun aReadingBelowItsApiFloorIsAbsentNotFailed() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 26) { "should not be called" }
        assertEquals(
            ProbeOutcome.Absent,
            outcome(results, PowerManagerProbe.Reading.DischargePrediction),
        )
        assertEquals(
            ProbeOutcome.Absent,
            outcome(results, PowerManagerProbe.Reading.ThermalStatus),
        )
    }

    /**
     * The accessor must not even be invoked below the floor -- calling a method the device
     * does not have throws `NoSuchMethodError`, and the point of the gate is to never
     * provoke it. This project already carries the same guard for
     * `computeChargeTimeRemaining`.
     */
    @Test
    fun theAccessorIsNotCalledBelowTheApiFloor() {
        val asked = mutableListOf<PowerManagerProbe.Reading>()
        PowerManagerProbe.resultsFrom(sdkInt = 26) { reading -> asked += reading; null }
        assertEquals(listOf(PowerManagerProbe.Reading.PowerSaveMode), asked)
    }

    @Test
    fun readingsAtExactlyTheirFloorAreAsked() {
        val asked = mutableListOf<PowerManagerProbe.Reading>()
        PowerManagerProbe.resultsFrom(sdkInt = 29) { reading -> asked += reading; null }
        assertTrue(PowerManagerProbe.Reading.ThermalStatus in asked)
        // 31-gated readings must still be skipped at 29.
        assertTrue(PowerManagerProbe.Reading.DischargePrediction !in asked)
    }

    @Test
    fun aNullOrBlankReadingIsAbsent() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 36) { null }
        assertTrue(results.all { it.outcome is ProbeOutcome.Absent })

        val blank = PowerManagerProbe.resultsFrom(sdkInt = 36) { "  " }
        assertTrue(blank.all { it.outcome is ProbeOutcome.Absent })
    }

    @Test
    fun aDenialIsRecordedAsDenied() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 36) { throw SecurityException() }
        assertTrue(results.all { it.outcome is ProbeOutcome.Denied })
    }

    @Test
    fun oneFailingReadingDoesNotAbortTheRest() {
        val results = PowerManagerProbe.resultsFrom(sdkInt = 36) { reading ->
            if (reading == PowerManagerProbe.Reading.DischargePrediction) {
                throw NoSuchMethodError("getBatteryDischargePrediction")
            }
            "ok"
        }
        assertEquals(PowerManagerProbe.Reading.entries.size, results.size)
        assertTrue(
            outcome(results, PowerManagerProbe.Reading.DischargePrediction) is ProbeOutcome.Failed,
        )
        assertEquals(
            ProbeOutcome.Value("ok"),
            outcome(results, PowerManagerProbe.Reading.PowerSaveMode),
        )
    }

    /**
     * API floors are facts about the platform, not preferences. A wrong one either calls a
     * method that does not exist or hides a reading the device could have served.
     */
    @Test
    fun apiFloorsMatchThePlatform() {
        assertEquals(31, PowerManagerProbe.Reading.DischargePrediction.minApi)
        assertEquals(31, PowerManagerProbe.Reading.DischargePredictionPersonalized.minApi)
        assertEquals(29, PowerManagerProbe.Reading.ThermalStatus.minApi)
        assertEquals(21, PowerManagerProbe.Reading.PowerSaveMode.minApi)
    }

    @Test
    fun noTwoReadingsShareAKey() {
        val duplicates = PowerManagerProbe.Reading.entries
            .groupBy { it.key }
            .filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<PowerManagerProbe.Reading>>(), duplicates)
    }
}

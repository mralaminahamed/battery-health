package com.alaminahamed.batteryhealth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryModelsTest {

    // ==================== Milliwatts Tests ====================

    @Test
    fun milliwattsComputesCorrectlyFromRealA35Values() {
        // Real A35 values: 3955 mV × 1_066_000 µA → 4216 mW
        // (3955 * 1_066_000) / 1_000_000 = 4216.03 → 4216 as Int
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Available(3955, Source.Framework),
            currentUa = Reading.Available(1_066_000, Source.Framework),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        val milliwatts = snapshot.milliwatts
        val expected = Reading.Available(4216, Source.Framework)
        assertEquals(expected, milliwatts)
    }

    @Test
    fun milliwattsNegativeDuringDischarge() {
        // Negative current during discharge results in negative milliwatts
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Discharging, Source.Framework),
            plugType = Reading.Available(PlugType.None, Source.Framework),
            voltageMv = Reading.Available(4000, Source.Framework),
            currentUa = Reading.Available(-500_000, Source.Framework),
            temperatureDeciC = Reading.Available(280, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(0, Source.Framework),
        )
        val milliwatts = snapshot.milliwatts
        val expected = Reading.Available(-2000, Source.Framework)
        assertEquals(expected, milliwatts)
    }

    @Test
    fun milliwattsPreservesNeedsPrivilegedAccessWhenCurrentAbsent() {
        // Regression guard for Finding 1: absence reason is not flattened to Unsupported
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Available(4000, Source.Framework),
            currentUa = Reading.NeedsPrivilegedAccess,
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        assertEquals(Reading.NeedsPrivilegedAccess, snapshot.milliwatts)
    }

    @Test
    fun milliwattsPreservesNeedsPrivilegedAccessWhenVoltageAbsent() {
        // Mirror case: voltage is NeedsPrivilegedAccess, current is available
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.NeedsPrivilegedAccess,
            currentUa = Reading.Available(1_000_000, Source.Framework),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        assertEquals(Reading.NeedsPrivilegedAccess, snapshot.milliwatts)
    }

    @Test
    fun milliwattsReturnsUnsupportedWhenVoltageUnsupported() {
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Unsupported,
            currentUa = Reading.Available(1_000_000, Source.Framework),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        assertEquals(Reading.Unsupported, snapshot.milliwatts)
    }

    @Test
    fun milliwattsProvenanceIsFrameworkWhenBothInputsFramework() {
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Available(4000, Source.Framework),
            currentUa = Reading.Available(1_000_000, Source.Framework),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        val milliwatts = snapshot.milliwatts
        assertTrue(milliwatts is Reading.Available)
        assertEquals(Source.Framework, (milliwatts as Reading.Available).source)
    }

    @Test
    fun milliwattsProvenanceIsPrivilegedWhenOneInputPrivileged() {
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Available(4000, Source.Framework),
            currentUa = Reading.Available(1_000_000, Source.Privileged),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        val milliwatts = snapshot.milliwatts
        assertTrue(milliwatts is Reading.Available)
        assertEquals(Source.Privileged, (milliwatts as Reading.Available).source)
    }

    @Test
    fun milliwattsProvenanceIsMeasuredWhenOneInputMeasured() {
        val snapshot = BatterySnapshot(
            levelPct = Reading.Available(50, Source.Framework),
            chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
            plugType = Reading.Available(PlugType.Ac, Source.Framework),
            voltageMv = Reading.Available(4000, Source.Measured),
            currentUa = Reading.Available(1_000_000, Source.Framework),
            temperatureDeciC = Reading.Available(250, Source.Framework),
            technology = Reading.Available("Li-ion", Source.Framework),
            chargeCounterUah = Reading.Available(1_000_000, Source.Framework),
            cycleCount = Reading.Available(100, Source.Framework),
            stateOfHealthPct = Reading.Available(85, Source.Framework),
            firstUsageDateEpochDay = Reading.Available(18000, Source.Framework),
            manufacturingDateEpochDay = Reading.Available(17000, Source.Framework),
            chargeTimeRemainingMs = Reading.Available(3600000, Source.Framework),
        )
        val milliwatts = snapshot.milliwatts
        assertTrue(milliwatts is Reading.Available)
        assertEquals(Source.Measured, (milliwatts as Reading.Available).source)
    }

    // ==================== HealthReport Tests ====================

    @Test
    fun healthReportBandGoodForHealthyPercentage() {
        val report = HealthReport(
            healthPct = 86,
            measuredFullUah = 4_300_000,
            designCapacityMah = 4000,
            method = CapacityMethod.Counter,
            sessionsUsed = 50,
        )
        assertEquals(HealthBand.Good, report.band)
    }

    @Test
    fun healthReportBandFairForMiddlingPercentage() {
        val report = HealthReport(
            healthPct = 72,
            measuredFullUah = 4_300_000,
            designCapacityMah = 4000,
            method = CapacityMethod.Counter,
            sessionsUsed = 50,
        )
        assertEquals(HealthBand.Fair, report.band)
    }

    @Test
    fun healthReportBandPoorForWornPercentage() {
        val report = HealthReport(
            healthPct = 64,
            measuredFullUah = 4_300_000,
            designCapacityMah = 4000,
            method = CapacityMethod.Counter,
            sessionsUsed = 50,
        )
        assertEquals(HealthBand.Poor, report.band)
    }

    @Test
    fun healthReportMeasuredFullMahConvertsFromMicroampHours() {
        val report = HealthReport(
            healthPct = 85,
            measuredFullUah = 4_300_000,
            designCapacityMah = 4000,
            method = CapacityMethod.Counter,
            sessionsUsed = 50,
        )
        assertEquals(4300, report.measuredFullMah)
    }

    // ==================== ChargeSession Tests ====================

    @Test
    fun chargeSessionDurationMsIsEndMinusStart() {
        val session = ChargeSession(
            id = 1,
            type = SessionType.Charge,
            startedAtMs = 1000,
            endedAtMs = 5000,
            startLevelPct = 20,
            endLevelPct = 100,
            startCounterUah = null,
            endCounterUah = null,
            peakTempDeciC = null,
            avgMilliwatts = null,
            screenOnMs = 0,
        )
        assertEquals(4000L, session.durationMs)
    }

    @Test
    fun chargeSessionDeltaLevelPctIsEndMinusStart() {
        val session = ChargeSession(
            id = 1,
            type = SessionType.Charge,
            startedAtMs = 1000,
            endedAtMs = 5000,
            startLevelPct = 20,
            endLevelPct = 100,
            startCounterUah = null,
            endCounterUah = null,
            peakTempDeciC = null,
            avgMilliwatts = null,
            screenOnMs = 0,
        )
        assertEquals(80, session.deltaLevelPct)
    }

    @Test
    fun chargeSessionDeltaLevelPctNegativeDuringDischarge() {
        val session = ChargeSession(
            id = 2,
            type = SessionType.Discharge,
            startedAtMs = 5000,
            endedAtMs = 8000,
            startLevelPct = 100,
            endLevelPct = 50,
            startCounterUah = null,
            endCounterUah = null,
            peakTempDeciC = null,
            avgMilliwatts = null,
            screenOnMs = 3000,
        )
        assertEquals(-50, session.deltaLevelPct)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}

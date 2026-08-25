package com.alaminahamed.batteryhealth.ui.health

import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.CapacityMethod
import com.alaminahamed.batteryhealth.domain.ChargeState
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.PlugType
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthUiStateTest {

    private fun snapshot(stateOfHealth: Reading<Int>) = BatterySnapshot(
        levelPct = Reading.Available(41, Source.Framework),
        chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
        plugType = Reading.Available(PlugType.Usb, Source.Framework),
        voltageMv = Reading.Available(3955, Source.Framework),
        currentUa = Reading.Available(1_066_000, Source.Framework),
        temperatureDeciC = Reading.Available(371, Source.Framework),
        technology = Reading.Available("Li-ion", Source.Framework),
        chargeCounterUah = Reading.Available(2_095_000, Source.Framework),
        cycleCount = Reading.Unsupported,
        stateOfHealthPct = stateOfHealth,
        firstUsageDateEpochDay = Reading.Unsupported,
        manufacturingDateEpochDay = Reading.Unsupported,
        chargeTimeRemainingMs = Reading.Unsupported,
    )

    private val measured = Reading.Available(
        HealthReport(
            healthPct = 84,
            measuredFullUah = 4_200_000,
            designCapacityMah = 5000,
            method = CapacityMethod.Counter,
            sessionsUsed = 3,
        ),
        Source.Measured,
    )

    @Test
    fun frameworkStateOfHealthWinsOverOurOwnMeasurement() {
        val state = HealthUiState(
            snapshot = snapshot(Reading.Available(86, Source.Framework)),
            measured = measured,
        )
        assertEquals(Reading.Available(86, Source.Framework), state.headlinePct)
    }

    @Test
    fun measurementIsUsedWhenTheFrameworkHasNoStateOfHealth() {
        val state = HealthUiState(snapshot = snapshot(Reading.Unsupported), measured = measured)
        assertEquals(Reading.Available(84, Source.Measured), state.headlinePct)
    }

    @Test
    fun theMeasurementReasonSurvivesWhenNeitherSourceHasAValue() {
        val state = HealthUiState(
            snapshot = snapshot(Reading.Unsupported),
            measured = Reading.NotYetMeasured,
        )
        assertEquals(Reading.NotYetMeasured, state.headlinePct)
    }

    @Test
    fun noSnapshotYetStillReportsTheMeasurementState() {
        val state = HealthUiState(snapshot = null, measured = Reading.NotYetMeasured)
        assertEquals(Reading.NotYetMeasured, state.headlinePct)
    }

    @Test
    fun needsPrivilegedAccessSurvivesWhenMeasurementIsUnsupported() {
        // What production actually emits: BatteryRepository sets stateOfHealthPct to
        // NeedsPrivilegedAccess unconditionally (never Unsupported), and measured is Unsupported
        // for any model outside the ten-entry design-capacity table with no override --
        // nearly every Samsung. The headline must not contradict the "Needs privileged access" row
        // right below it by claiming "not available on this device" about the same
        // data source.
        val state = HealthUiState(
            snapshot = snapshot(Reading.NeedsPrivilegedAccess),
            measured = Reading.Unsupported,
        )
        assertEquals(Reading.NeedsPrivilegedAccess, state.headlinePct)
    }

    @Test
    fun measurementWinsOverNeedsPrivilegedAccessWhenAvailable() {
        val state = HealthUiState(snapshot = snapshot(Reading.NeedsPrivilegedAccess), measured = measured)
        assertEquals(Reading.Available(84, Source.Measured), state.headlinePct)
    }

    @Test
    fun notYetMeasuredBeatsNeedsPrivilegedAccess() {
        val state = HealthUiState(
            snapshot = snapshot(Reading.NeedsPrivilegedAccess),
            measured = Reading.NotYetMeasured,
        )
        assertEquals(Reading.NotYetMeasured, state.headlinePct)
    }
}

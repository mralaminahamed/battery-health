package com.mralaminahamed.batteryhealth.ui.health

import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.CapacityMethod
import com.mralaminahamed.batteryhealth.domain.ChargeState
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.PlugType
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
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
}

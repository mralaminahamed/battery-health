package com.alaminahamed.batteryhealth.ui.live

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.ChargeState
import com.alaminahamed.batteryhealth.domain.PlugType
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

class LiveScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun snapshot(
        currentUa: Reading<Int> = Reading.Available(1_066_000, Source.Framework),
        voltageMv: Reading<Int> = Reading.Available(3955, Source.Framework),
    ) = BatterySnapshot(
        levelPct = Reading.Available(41, Source.Framework),
        chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
        plugType = Reading.Available(PlugType.Usb, Source.Framework),
        voltageMv = voltageMv,
        currentUa = currentUa,
        temperatureDeciC = Reading.Available(371, Source.Framework),
        technology = Reading.Available("Li-ion", Source.Framework),
        chargeCounterUah = Reading.Available(2_095_000, Source.Framework),
        cycleCount = Reading.Unsupported,
        stateOfHealthPct = Reading.Unsupported,
        firstUsageDateEpochDay = Reading.Unsupported,
        manufacturingDateEpochDay = Reading.Unsupported,
        chargeTimeRemainingMs = Reading.Available(3_600_000, Source.Framework),
        // Given real values so they stay out of the "not available" counts below. These
        // rows have nothing to do with what this suite tests, and leaving them at their
        // Unsupported defaults would make every exact-count assertion here depend on how
        // many unrelated rows the Live screen happens to carry -- which is how a sharp
        // assertion decays into an arbitrary number nobody dares change.
        thermalStatus = Reading.Available(0, Source.Framework),
        dischargePredictionMs = Reading.Available(48_240_000, Source.Framework),
    )

    @Test
    fun rendersLiveReadings() {
        compose.setContent { BatteryHealthTheme { LiveContent(snapshot()) } }

        compose.onNodeWithTag(LiveScreenTags.ROOT).assertIsDisplayed()
        // 3955 mV x 1066 mA -> 4216.03 mW, rounded to two decimals -> 4.22.
        compose.onNodeWithText("4.22").assertIsDisplayed()
        compose.onNodeWithText("1066 mA").assertIsDisplayed()
        compose.onNodeWithText("3955 mV").assertIsDisplayed()
        compose.onNodeWithText("37.1 °C").assertIsDisplayed()
    }

    @Test
    fun anAbsentInputMeansNoWattageNumberAtAll() {
        compose.setContent {
            BatteryHealthTheme { LiveContent(snapshot(currentUa = Reading.Unsupported)) }
        }
        // Wattage needs both volts and amps. Without current it must not fall back to 0.
        compose.onNodeWithText("0.00").assertDoesNotExist()
        // Two rows are legitimately Unsupported here: the raw current reading, and the
        // derived wattage that propagates its absence (BatterySnapshot.milliwatts returns
        // currentUa itself when currentUa is not Available). Both must independently show
        // the reason rather than a numeral, so the count is asserted exactly, not just
        // that the text exists somewhere -- a regression in either row alone must fail.
        compose.onAllNodesWithText("Not available on this device").assertCountEquals(2)
    }

    @Test
    fun nullSnapshotShowsAWaitingStateRatherThanZeroes() {
        compose.setContent { BatteryHealthTheme { LiveContent(null) } }
        compose.onNodeWithText("Reading battery…").assertIsDisplayed()
    }
}

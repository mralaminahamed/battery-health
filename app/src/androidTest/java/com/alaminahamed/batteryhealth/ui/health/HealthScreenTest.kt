package com.alaminahamed.batteryhealth.ui.health

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.CapacityMethod
import com.alaminahamed.batteryhealth.domain.ChargeState
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.PlugType
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

class HealthScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun snapshot() = BatterySnapshot(
        levelPct = Reading.Available(41, Source.Framework),
        chargeState = Reading.Available(ChargeState.Charging, Source.Framework),
        plugType = Reading.Available(PlugType.Usb, Source.Framework),
        voltageMv = Reading.Available(3955, Source.Framework),
        currentUa = Reading.Available(1_066_000, Source.Framework),
        temperatureDeciC = Reading.Available(371, Source.Framework),
        technology = Reading.Available("Li-ion", Source.Framework),
        chargeCounterUah = Reading.Available(2_095_000, Source.Framework),
        cycleCount = Reading.Unsupported,
        stateOfHealthPct = Reading.Unsupported,
        firstUsageDateEpochDay = Reading.Available(19_904, Source.Framework),
        manufacturingDateEpochDay = Reading.Unsupported,
        chargeTimeRemainingMs = Reading.Unsupported,
    )

    @Test
    fun showsMeasuredHealthWithItsProvenance() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.Available(
                HealthReport(84, 4_200_000, 5000, CapacityMethod.Counter, 3),
                Source.Measured,
            ),
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithTag(HealthScreenTags.ROOT).assertIsDisplayed()
        compose.onNodeWithText("84").assertIsDisplayed()
        compose.onNodeWithText("Measured").assertIsDisplayed()
        compose.onNodeWithText("4200 mAh of 5000 mAh").assertIsDisplayed()
        compose.onNodeWithText("2024-06-30").assertIsDisplayed()
    }

    @Test
    fun measuringStateShowsProgressInsteadOfANumber() {
        val state = HealthUiState(snapshot = snapshot(), measured = Reading.NotYetMeasured)
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("Measuring").assertIsDisplayed()
        compose.onNodeWithText("Needs 3 full charge sessions").assertIsDisplayed()
    }

    @Test
    fun unknownDesignCapacityIsStatedRatherThanGuessed() {
        val state = HealthUiState(snapshot = snapshot(), measured = Reading.Unsupported)
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        // Three rows are independently Unsupported here: the headline (no design capacity
        // to measure against), the cycle count, and the manufacturing date -- the fixture
        // above sets both of the latter two to Unsupported regardless of this test's own
        // concern. Each renders the shared reason text on its own; a singular text lookup
        // would be ambiguous, so the count is asserted exactly rather than just that the
        // text exists somewhere, the same way LiveScreenTest asserts multiplicity.
        compose.onAllNodesWithText("Not available on this device").assertCountEquals(3)
    }

    private fun snapshotWithBatteryProtect(modeEnabled: Boolean, thresholdPct: Int) = snapshot().copy(
        protectBatteryModeEnabled = Reading.Available(modeEnabled, Source.Privileged),
        protectionThresholdPct = Reading.Available(thresholdPct, Source.Privileged),
    )

    /**
     * Important 3: `mProtectionThreshold` is a real, known number even while Battery
     * Protect's mode is off (Samsung keeps the configured cap around for when it is next
     * turned on) -- rendering it as today's "80%" charge limit would claim something is
     * capping charge when nothing is. Suppressed in the Health screen's own presentation,
     * not upstream in the Reading, per `HealthScreen`'s own doc on why.
     */
    @Test
    fun chargeLimitIsSuppressedWhenBatteryProtectIsOff() {
        val state = HealthUiState(
            snapshot = snapshotWithBatteryProtect(modeEnabled = false, thresholdPct = 80),
            measured = Reading.Unsupported,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("Not limiting").assertIsDisplayed()
    }

    /** The regression this guards against: suppressing the threshold unconditionally,
     * which would hide a real, currently-enforced charge limit too. */
    @Test
    fun chargeLimitIsShownWhenBatteryProtectIsOn() {
        val state = HealthUiState(
            snapshot = snapshotWithBatteryProtect(modeEnabled = true, thresholdPct = 80),
            measured = Reading.Unsupported,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("On").assertIsDisplayed()
        compose.onNodeWithText("80%").assertIsDisplayed()
    }
}

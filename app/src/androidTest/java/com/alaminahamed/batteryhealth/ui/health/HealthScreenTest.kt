package com.alaminahamed.batteryhealth.ui.health

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
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
        // `recorderEnabled = true` is load-bearing and was missing. This test previously
        // relied on the field's default of false, so it asserted "Needs 3 full charge
        // sessions" for a state in which nothing was recording and no session could ever
        // be counted -- pinning the defect rather than the behaviour. Counting sessions
        // is only the right thing to say while something is actually recording.
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.NotYetMeasured,
            recorderEnabled = true,
        )
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

    /**
     * The fix for this whole task: a fresh install on a device the model table doesn't
     * know now says how to fix it, rather than just reporting Unsupported and stopping.
     */
    @Test
    fun noDesignCapacityPointsTheUserAtTheOverride() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.Unsupported,
            designCapacity = EffectiveDesignCapacity.None,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText(
            "No design capacity is known for this device. Set one in Settings to " +
                "start measuring health from your charge counter -- it still takes a " +
                "few real charge sessions to produce a number, and it won't add BSOH, " +
                "first-use date or Battery Protect; those need the privileged tier above.",
        ).assertIsDisplayed()
    }

    @Test
    fun aKnownDesignCapacityDoesNotShowTheOverrideHint() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.Unsupported,
            designCapacity = EffectiveDesignCapacity(5000, DesignCapacitySource.Table),
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onAllNodesWithText("Set one in Settings", substring = true).assertCountEquals(0)
    }

    /**
     * `headlinePct` can be `Available` from the privileged/ASOC path even while no
     * design capacity is known at all -- the hint only makes sense while there is
     * nothing else on this card to show, so a working headline must suppress it too,
     * not just a known design capacity.
     */
    @Test
    fun aWorkingHeadlineFromThePrivilegedTierAlsoSuppressesTheHint() {
        val state = HealthUiState(
            snapshot = snapshot().copy(stateOfHealthPct = Reading.Available(91, Source.Privileged)),
            measured = Reading.Unsupported,
            designCapacity = EffectiveDesignCapacity.None,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onAllNodesWithText("Set one in Settings", substring = true).assertCountEquals(0)
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

    // ---- the subtitle under the headline --------------------------------------------

    private fun measuringState(
        recorderEnabled: Boolean,
        recorderStartFailed: Boolean = false,
    ) = HealthUiState(
        snapshot = null,
        measured = Reading.NotYetMeasured,
        recorderEnabled = recorderEnabled,
        recorderStartFailed = recorderStartFailed,
    )

    /**
     * Found by running the app on a real device: the card said "Needs 3 full charge
     * sessions" while the recorder switch further down the same screen was off, so no
     * session would ever be counted and the line would have stood there forever. The
     * screen must not promise progress that cannot happen.
     */
    @Test
    fun withTheRecorderOffTheHeadlineSaysHowToStartRatherThanCountingSessions() {
        compose.setContent {
            BatteryHealthTheme { HealthContent(measuringState(recorderEnabled = false), Modifier) }
        }

        compose.onNodeWithTag(HealthScreenTags.MEASUREMENT_NOTE)
            .performScrollTo()
            .assertTextContains("Record charge sessions", substring = true)
    }

    @Test
    fun withTheRecorderOnItCountsTheSessionsItStillNeeds() {
        compose.setContent {
            BatteryHealthTheme { HealthContent(measuringState(recorderEnabled = true), Modifier) }
        }

        compose.onNodeWithTag(HealthScreenTags.MEASUREMENT_NOTE)
            .performScrollTo()
            .assertTextContains("full charge sessions", substring = true)
    }

    @Test
    fun aRefusedRecorderSaysSoRatherThanCountingSessions() {
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(
                    measuringState(recorderEnabled = true, recorderStartFailed = true),
                    Modifier,
                )
            }
        }

        compose.onNodeWithTag(HealthScreenTags.MEASUREMENT_NOTE)
            .performScrollTo()
            .assertTextContains("couldn", substring = true)
    }

    // ---- cycles and BSOH ---------------------------------------------------------------

    /**
     * The distinction that matters most about this app's own cycle count: Samsung's counts
     * from the day the battery was made, this counts charge the app actually watched go
     * in. Without saying so, a low number on a year-old phone reads as a healthy battery
     * rather than as a young measurement.
     */
    @Test
    fun anAppMeasuredCycleCountSaysItCountsFromWhenRecordingStarted() {
        val state = HealthUiState(
            snapshot = snapshot().copy(cycleCount = Reading.Available(3, Source.Measured)),
            measured = Reading.NotYetMeasured,
            recorderEnabled = true,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("not the battery's whole life", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** Samsung's own figure keeps its own, different caveat. */
    @Test
    fun aVendorCycleCountKeepsThePartialChargeCaveat() {
        val state = HealthUiState(
            snapshot = snapshot().copy(cycleCount = Reading.Available(412, Source.Privileged)),
            measured = Reading.NotYetMeasured,
            recorderEnabled = true,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("Counts every partial charge", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A build with no shell has no source for BSOH at any price, and the headline already
     * answers the same question -- so the row would be a permanent "not available" sitting
     * under a number that supersedes it.
     */
    @Test
    fun bsohIsHiddenEntirelyInABuildWithNoPrivilegedTier() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.NotYetMeasured,
            privilegedTierSupported = false,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("BSOH").assertDoesNotExist()
    }

    @Test
    fun bsohIsShownWhereATierCouldSupplyIt() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.NotYetMeasured,
            privilegedTierSupported = true,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("BSOH").performScrollTo().assertIsDisplayed()
    }

    /**
     * "Measuring" alone says nothing about what would end the wait, and with recording off
     * it would say it forever -- the same defect the headline's own subtitle was fixed
     * for earlier.
     */
    @Test
    fun aCycleCountWaitingOnRecordingSaysHowToStartIt() {
        val state = HealthUiState(
            snapshot = snapshot().copy(cycleCount = Reading.NotYetMeasured),
            measured = Reading.NotYetMeasured,
            recorderEnabled = false,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("start counting", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aCycleCountAlreadyRecordingSaysWhatItIsWaitingFor() {
        val state = HealthUiState(
            snapshot = snapshot().copy(cycleCount = Reading.NotYetMeasured),
            measured = Reading.NotYetMeasured,
            recorderEnabled = true,
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("Counting charge as it goes in", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * A figure above 100% has to explain itself or it just looks broken. It usually means
     * the design capacity being compared against is wrong for the device -- something the
     * user can fix -- and before the clamp was removed they had no way to know it was even
     * happening.
     */
    @Test
    fun aReadingAboveDesignExplainsItselfRatherThanLookingBroken() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.Available(
                HealthReport(
                    healthPct = 104,
                    measuredFullUah = 5_033_000,
                    designCapacityMah = 4_855,
                    method = CapacityMethod.Counter,
                    sessionsUsed = 3,
                ),
                Source.Measured,
            ),
            designCapacity = EffectiveDesignCapacity(4_855, DesignCapacitySource.PowerProfile),
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("104").assertIsDisplayed()
        compose.onNodeWithText("design figure is wrong", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** An ordinary reading says nothing of the sort. */
    @Test
    fun anOrdinaryReadingCarriesNoSuchWarning() {
        val state = HealthUiState(
            snapshot = snapshot(),
            measured = Reading.Available(
                HealthReport(
                    healthPct = 86,
                    measuredFullUah = 4_300_000,
                    designCapacityMah = 5_000,
                    method = CapacityMethod.Counter,
                    sessionsUsed = 3,
                ),
                Source.Measured,
            ),
            designCapacity = EffectiveDesignCapacity(5_000, DesignCapacitySource.Table),
        )
        compose.setContent { BatteryHealthTheme { HealthContent(state, Modifier) } }

        compose.onNodeWithText("design figure is wrong", substring = true).assertDoesNotExist()
    }
}

package com.alaminahamed.batteryhealth.ui.apps

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alaminahamed.batteryhealth.data.apps.AppLabel
import com.alaminahamed.batteryhealth.data.apps.EstimatedAppRow
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

/**
 * This screen has one per-app section, [EstimatedDrainSection]: a per-app drain
 * *estimate* apportioned by foreground screen time, gated only on `PACKAGE_USAGE_STATS`,
 * an ordinary Settings toggle. It used to carry a second, independent section,
 * `CpuTimeSection`, for per-uid CPU time -- deleted along with `UidCpuTimeSource` because
 * it needed `BATTERY_STATS`, which this app has never declared and can never reach on a
 * normal install, so it could only ever render its own permanent absence.
 */
class AppsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun estimatedRow(
        packageName: String,
        label: AppLabel,
        foregroundMs: Long = 58 * 60_000L,
        estimatedMah: Double = 84.32,
        sharePct: Double = 31.5,
    ) = EstimatedAppRow(
        packageName = packageName,
        label = label,
        foregroundMs = foregroundMs,
        estimatedMah = estimatedMah,
        sharePct = sharePct,
    )

    @Test
    fun estimateNeedsUsageAccessShowsTheDisclosureCardBeforeItsOwnButton() {
        val state = AppsUiState(estimatedDrainRows = Reading.NeedsUsageAccess)
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithTag(AppsScreenTags.USAGE_ACCESS_CARD).assertIsDisplayed()
        // SectionHeader renders its text uppercased -- see OneUiComponents.kt.
        compose.onNodeWithText("Estimate drain from screen time", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag(AppsScreenTags.USAGE_ACCESS_ACTION).assertIsDisplayed()
    }

    @Test
    fun clickingTheUsageAccessButtonInvokesTheCallback() {
        var invoked = false
        val state = AppsUiState(estimatedDrainRows = Reading.NeedsUsageAccess)
        compose.setContent {
            BatteryHealthTheme { AppsContent(state, onOpenUsageAccessSettings = { invoked = true }) }
        }

        compose.onNodeWithTag(AppsScreenTags.USAGE_ACCESS_ACTION).performClick()
        assert(invoked) { "Expected the usage-access button to invoke its callback" }
    }

    @Test
    fun estimateNotYetMeasuredShowsMeasuringWithNoDisclosureCard() {
        val state = AppsUiState(estimatedDrainRows = Reading.NotYetMeasured)
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Measuring").assertIsDisplayed()
        compose.onAllNodesWithTag(AppsScreenTags.USAGE_ACCESS_CARD).assertCountEquals(0)
    }

    /**
     * The caption states the samples' own span as a duration ("over the past 2 h 57 m"),
     * never a bare clock time, and the real mAh total -- the exact defect this feature's
     * own history shipped and fixed (a bare `HH:mm` rendered as if it were "since the
     * current time"). Each row carries its own screen time, its `~`-prefixed mAh figure,
     * its share, and exactly one "Estimated" chip -- never "Observed", the other historical
     * defect this port starts from the fixed state of.
     */
    @Test
    fun availableEstimateShowsTheRealSpanAndTotalAndEachRowWithTheEstimatedChip() {
        val drain = EstimatedDrain(
            rows = listOf(
                estimatedRow(
                    packageName = "com.samsung.android.spdclient",
                    label = AppLabel.PackageNameOnly("com.samsung.android.spdclient"),
                    foregroundMs = 58 * 60_000L,
                    estimatedMah = 84.32,
                    sharePct = 31.5,
                ),
                estimatedRow(
                    packageName = "com.instagram.android",
                    label = AppLabel.Resolved("Instagram", icon = null),
                    foregroundMs = 42 * 60_000L,
                    estimatedMah = 12.10,
                    sharePct = 4.5,
                ),
            ),
            totalMah = 320.50,
            windowStartMs = 0L,
            windowEndMs = 10_620_000L, // 2 h 57 m
        )
        val state = AppsUiState(
            estimatedDrainRows = Reading.Available(drain, Source.Inferred),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Estimated drain from screen time", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("320.50 mAh", substring = true).assertIsDisplayed()
        compose.onNodeWithText("over the past 2 h 57 m", substring = true).assertIsDisplayed()

        compose.onNodeWithText("com.samsung.android.spdclient").assertIsDisplayed()
        compose.onNodeWithText("Instagram").assertIsDisplayed()
        compose.onNodeWithText("~84.32 mAh").assertIsDisplayed()
        compose.onNodeWithText("31.5%").assertIsDisplayed()
        compose.onNodeWithText("~12.10 mAh").assertIsDisplayed()

        // One chip per row, all labelled "Estimated" -- never a second, differently-worded
        // chip for the same Source.Inferred value.
        compose.onAllNodesWithText("Estimated").assertCountEquals(2)
        compose.onAllNodesWithText("Observed").assertCountEquals(0)
    }

    /**
     * Pins the fix for the row layout collapsing on `play` with long, unresolved package
     * names ("~31. / 84 / mA / h" wrapped over the caption): a real Samsung system-package
     * name, long enough to actually exercise the ellipsis/weight layout this fixture
     * exists to catch, rather than a short fixture (e.g. "com.b") that fits comfortably
     * either way and could never distinguish a collapsed layout from a correct one.
     */
    @Test
    fun estimatedRowWithARealLongUnresolvedPackageNameRendersEveryFieldWithoutCrashing() {
        val drain = EstimatedDrain(
            rows = listOf(
                estimatedRow(
                    packageName = "com.samsung.android.spdclient",
                    label = AppLabel.PackageNameOnly("com.samsung.android.spdclient"),
                ),
            ),
            totalMah = 84.32,
            windowStartMs = 0L,
            windowEndMs = 3_600_000L,
        )
        val state = AppsUiState(
            estimatedDrainRows = Reading.Available(drain, Source.Inferred),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("com.samsung.android.spdclient").assertIsDisplayed()
        compose.onNodeWithText("~84.32 mAh").assertIsDisplayed()
        compose.onNodeWithText("31.5%").assertIsDisplayed()
    }

    /**
     * The duration comes first in a row's caption, the label caveat second -- the reverse
     * would let this `maxLines = 1` caption's own truncation eat the duration's unit on a
     * long `play`-flavour caveat, rendering "58 …" instead of "58 m". Exact string, not a
     * substring match, so a regression to the old ordering fails this rather than
     * happening to still contain both fragments.
     */
    @Test
    fun estimatedSecondaryTextPutsTheDurationFirstSoTruncationCannotEatItsUnit() {
        val drain = EstimatedDrain(
            rows = listOf(
                estimatedRow(
                    packageName = "com.samsung.android.spdclient",
                    label = AppLabel.PackageNameOnly("com.samsung.android.spdclient"),
                    foregroundMs = 58 * 60_000L,
                ),
            ),
            totalMah = 84.32,
            windowStartMs = 0L,
            windowEndMs = 3_600_000L,
        )
        val state = AppsUiState(
            estimatedDrainRows = Reading.Available(drain, Source.Inferred),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("58 m on screen -- package name only, label unavailable").assertIsDisplayed()
    }
}

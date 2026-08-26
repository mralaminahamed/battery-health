package com.alaminahamed.batteryhealth.ui.apps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alaminahamed.batteryhealth.data.apps.AppCpuRow
import com.alaminahamed.batteryhealth.data.apps.AppLabel
import com.alaminahamed.batteryhealth.domain.AppBucket
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.domain.UidKind
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

/**
 * This screen used to lead with per-uid battery power from a privileged adb/root shell,
 * behind `UnlockCard`. That whole feature is gone -- see the task report -- and what
 * remains is [CpuTimeSection] alone, which every build already rendered on its own for a
 * device with no shell. These tests replace the old `AppRow`-based coverage with coverage
 * of the [AppsUiState.cpuRows] rendering that is now this screen's entire content.
 */
class AppsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun cpuRow(
        uid: Int,
        kind: UidKind = UidKind.App,
        bucket: AppBucket = AppBucket.Visible,
        label: AppLabel = AppLabel.Resolved("Camera", icon = null),
        totalCpuMs: Long = 65_000,
        sharePct: Double = 40.0,
    ) = AppCpuRow(
        uid = uid,
        kind = kind,
        bucket = bucket,
        label = label,
        totalCpuMs = totalCpuMs,
        userCpuMs = totalCpuMs / 2,
        systemCpuMs = totalCpuMs / 2,
        sharePct = sharePct,
    )

    @Test
    fun needsPrivilegedAccessShowsTheHonestReason() {
        val state = AppsUiState(cpuRows = Reading.NeedsPrivilegedAccess)
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Needs the one-time permission").assertIsDisplayed()
    }

    @Test
    fun unsupportedShowsNotAvailableOnThisDevice() {
        val state = AppsUiState(cpuRows = Reading.Unsupported)
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Not available on this device").assertIsDisplayed()
    }

    @Test
    fun notYetMeasuredShowsMeasuring() {
        val state = AppsUiState(cpuRows = Reading.NotYetMeasured)
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Measuring").assertIsDisplayed()
    }

    @Test
    fun anEmptyButAvailableListShowsAnHonestEmptyStateNotAnAbsenceReason() {
        val state = AppsUiState(cpuRows = Reading.Available(emptyList(), Source.Framework))
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("No CPU time recorded yet").assertIsDisplayed()
    }

    /**
     * One row rendered by default (the "Visible" tab), with its resolved label and
     * formatted CPU time -- proves the whole chain from [AppsUiState.cpuRows] through
     * [AppCpuRow] to the rendered row text.
     */
    @Test
    fun boundStateRendersTheDefaultBucketWithATabCountAndTheRowsText() {
        val state = AppsUiState(
            cpuRows = Reading.Available(
                listOf(
                    cpuRow(uid = 10106, bucket = AppBucket.Visible, totalCpuMs = 65_000),
                    cpuRow(
                        uid = 1000,
                        kind = UidKind.System,
                        bucket = AppBucket.System,
                        label = AppLabel.Unknown,
                        totalCpuMs = 5_000,
                    ),
                ),
                Source.Framework,
            ),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        // The default tab is Visible, carrying the one row placed in it.
        compose.onNodeWithText("Visible 1").assertIsDisplayed()
        compose.onNodeWithText("System 1").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("1 m 5 s").assertIsDisplayed()
    }

    /** Switching tabs shows a different bucket's rows, and an unresolved uid falls back
     * to its raw uid rather than an invented name. */
    @Test
    fun switchingToTheSystemTabShowsItsOwnRowsWithAnUnresolvedLabelAsUid() {
        val state = AppsUiState(
            cpuRows = Reading.Available(
                listOf(
                    cpuRow(uid = 10106, bucket = AppBucket.Visible),
                    cpuRow(
                        uid = 1000,
                        kind = UidKind.System,
                        bucket = AppBucket.System,
                        label = AppLabel.Unknown,
                    ),
                ),
                Source.Framework,
            ),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithTag("cpu-tab-System").performClick()
        compose.onNodeWithText("uid 1000").assertIsDisplayed()
    }
}

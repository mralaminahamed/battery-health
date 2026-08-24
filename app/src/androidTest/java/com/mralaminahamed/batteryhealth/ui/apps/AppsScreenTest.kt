package com.mralaminahamed.batteryhealth.ui.apps

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.mralaminahamed.batteryhealth.data.apps.AppLabel
import com.mralaminahamed.batteryhealth.data.apps.AppRow
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.mralaminahamed.batteryhealth.data.privileged.Transport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.ui.components.UnlockCardTags
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

class AppsScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun needsShizukuShowsUnlockCardAndTheSharedReasonText() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Unavailable,
            rows = Reading.NeedsShizuku,
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        compose.onNodeWithText("Needs Shizuku").assertIsDisplayed()
    }

    /**
     * One row per [AppRow] kind, sorted descending as `BatteryRepository`/`AppRowMapper`
     * already guarantee -- this test only has to prove each kind renders its own distinct
     * text, not re-derive the sort itself.
     */
    @Test
    fun boundStateRendersEachRowKindWithItsOwnDistinctText() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.Available(
                listOf(
                    AppRow.Shell(uid = 2000, mAh = 422.0, sharePct = 94.7),
                    AppRow.System(uid = 1000, mAh = 6.23, sharePct = 1.4, packageCount = 82),
                    AppRow.App(
                        uid = 10106,
                        mAh = 15.6,
                        sharePct = 3.5,
                        label = AppLabel.Resolved("Camera", icon = null),
                    ),
                ),
                Source.Privileged,
            ),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onAllNodesWithTag(UnlockCardTags.ROOT).assertCountEquals(0)
        compose.onNodeWithText("USB debugging (adb)").assertIsDisplayed()
        compose.onNodeWithText("Development / testing, not normal use").assertIsDisplayed()
        compose.onNodeWithText("422.00 mAh").assertIsDisplayed()
        compose.onNodeWithText("System (uid 1000)").assertIsDisplayed()
        compose.onNodeWithText("82 packages, not an app").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("15.60 mAh").assertIsDisplayed()
    }

    @Test
    fun anEmptyButAvailableListShowsAnHonestEmptyStateNotAnAbsenceReason() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.Available(emptyList(), Source.Privileged),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("No power data recorded yet").assertIsDisplayed()
    }

    /**
     * The distinction [AppLabel] exists for, all the way to the screen: a package name
     * this build could not resolve a label for must show the raw identifier plus an
     * explicit caption, never a bare name that could pass for a confirmed one.
     */
    @Test
    fun packageNameOnlyShowsTheRawIdentifierAndAnExplicitCaption() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.Available(
                listOf(
                    AppRow.App(
                        uid = 10501,
                        mAh = 1.2,
                        sharePct = 0.5,
                        label = AppLabel.PackageNameOnly("com.example.unresolved"),
                    ),
                ),
                Source.Privileged,
            ),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("com.example.unresolved").assertIsDisplayed()
        compose.onNodeWithText("Package name only, label unavailable").assertIsDisplayed()
    }

    @Test
    fun unknownLabelShowsTheUidAndNoInventedName() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.Available(
                listOf(AppRow.App(uid = 10999, mAh = 0.8, sharePct = 0.2, label = AppLabel.Unknown)),
                Source.Privileged,
            ),
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithText("Uid 10999").assertIsDisplayed()
        compose.onNodeWithText("No app name available").assertIsDisplayed()
    }

    /** This screen's own failure signal, independent of Health's `privilegedDumpFailed` --
     * see `BatteryRepository.appPowerFailed`'s own doc. */
    @Test
    fun appPowerFailedShowsTheSharedRetryCard() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.NeedsShizuku,
            appPowerFailed = true,
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        compose.onNodeWithText("PRIVILEGED READ FAILED").assertIsDisplayed()
        compose.onNodeWithTag(UnlockCardTags.ACTION).assertIsDisplayed()
    }

    /**
     * The skeleton only ever replaces `ReadingSlot`'s own rendering while there is
     * genuinely nothing to show yet -- see `AppsContent`'s own doc. Once a real list is
     * already in hand, `isLoading` going true again (a background resume-triggered
     * refresh) must not blank the screen back to a skeleton.
     */
    @Test
    fun loadingWithNothingToShowYetRendersTheSkeletonNotNeedsShizuku() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.NeedsShizuku,
            isLoading = true,
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onNodeWithTag(AppsScreenTags.SKELETON).assertIsDisplayed()
        compose.onAllNodesWithTag(UnlockCardTags.ROOT).assertCountEquals(0)
    }

    @Test
    fun loadingWithARealListAlreadyInHandKeepsShowingItInsteadOfASkeleton() {
        val state = AppsUiState(
            privilegedAvailability = PrivilegedAvailability.Ready(Transport.Adb),
            rows = Reading.Available(
                listOf(AppRow.Shell(uid = 2000, mAh = 422.0, sharePct = 94.7)),
                Source.Privileged,
            ),
            isLoading = true,
        )
        compose.setContent { BatteryHealthTheme { AppsContent(state) } }

        compose.onAllNodesWithTag(AppsScreenTags.SKELETON).assertCountEquals(0)
        compose.onNodeWithText("USB debugging (adb)").assertIsDisplayed()
    }
}

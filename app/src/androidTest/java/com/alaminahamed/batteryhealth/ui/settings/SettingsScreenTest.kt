package com.alaminahamed.batteryhealth.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `SettingsContent` exercised directly, with no Hilt test harness needed.
 *
 * This suite used to also cover the design-capacity override dialog and the ADB-port
 * setting, both round-tripping through the app's real DataStore. Both are gone: the owner's
 * decision for this app is that it asks for nothing by typing a number, and there is no
 * longer a privileged transport whose port could ever need configuring. What is covered
 * now is what the screen actually does post-removal -- Notifications and the Permissions
 * section, the one place every declared permission is shown with its live state and the
 * one action (if any) that advances it.
 */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(permissions: List<PermissionRow> = emptyList()) =
        SettingsUiState(permissions = permissions)

    /**
     * Android stops showing its own prompt after two refusals, at which point the in-app
     * request is a silent no-op forever. Without this route the app would report
     * notifications as off while offering nothing that could turn them back on.
     */
    @Test
    fun blockedNotificationsOfferARouteToSystemSettings() {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(state().copy(notificationsGranted = false), Modifier)
            }
        }

        compose.onNodeWithTag(SettingsNotificationTags.ACTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Blocked").assertIsDisplayed()
    }

    /** A button that leads somewhere with nothing to do is worse than no button. */
    @Test
    fun grantedNotificationsOfferNoButtonToPress() {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(state().copy(notificationsGranted = true), Modifier)
            }
        }

        compose.onNodeWithTag(SettingsNotificationTags.ROW).performScrollTo()
        compose.onNodeWithText("Allowed").assertIsDisplayed()
        compose.onAllNodesWithTag(SettingsNotificationTags.ACTION).assertCountEquals(0)
    }

    // ---- the Permissions section ------------------------------------------------------

    /** Empty only as the cold-start placeholder -- see `SettingsUiState.permissions`'s own
     * doc. There is nothing honest to show yet, so the section stays off entirely rather
     * than rendering an empty shell. */
    @Test
    fun anEmptyPermissionsListRendersNoSectionAtAll() {
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = emptyList()), Modifier) }
        }

        compose.onAllNodesWithTag(SettingsPermissionsTags.SECTION).assertCountEquals(0)
    }

    @Test
    fun anUngrantedRequestablePermissionReadsDeniedAndOffersARequestButton() {
        val row = PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = false)
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = listOf(row)), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.row("POST_NOTIFICATIONS")).performScrollTo()
        compose.onNodeWithText("Denied").assertIsDisplayed()
        compose.onNodeWithTag(SettingsPermissionsTags.action("POST_NOTIFICATIONS"))
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aGrantedRequestablePermissionReadsGrantedWithNoButton() {
        val row = PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = true)
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = listOf(row)), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.row("POST_NOTIFICATIONS")).performScrollTo()
        compose.onNodeWithText("Granted").assertIsDisplayed()
        compose.onAllNodesWithTag(SettingsPermissionsTags.action("POST_NOTIFICATIONS")).assertCountEquals(0)
    }

    /** Tapping the request button is what launches the real
     * `ActivityResultContracts.RequestPermission()` dialog in production -- here it just
     * has to reach the callback `SettingsScreen` wires it to. */
    @Test
    fun tappingTheRequestButtonInvokesTheCallback() {
        var requested = false
        val row = PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = false)
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state(permissions = listOf(row)),
                    Modifier,
                    onRequestNotificationPermission = { requested = true },
                )
            }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.action("POST_NOTIFICATIONS"))
            .performScrollTo().performClick()
        assertTrue("tapping the button must invoke the request callback", requested)
    }

    @Test
    fun aNotHeldAppOpPermissionReadsNotHeldAndOffersTheUsageAccessDeepLink() {
        val row = PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = false)
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = listOf(row)), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.row("PACKAGE_USAGE_STATS")).performScrollTo()
        compose.onNodeWithText("Not held").assertIsDisplayed()
        compose.onNodeWithText("Open Usage access settings").assertIsDisplayed()
    }

    @Test
    fun aHeldAppOpPermissionReadsHeldWithNoButton() {
        val row = PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = true)
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = listOf(row)), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.row("PACKAGE_USAGE_STATS")).performScrollTo()
        compose.onNodeWithText("Held").assertIsDisplayed()
        compose.onAllNodesWithText("Open Usage access settings").assertCountEquals(0)
    }

    @Test
    fun tappingTheUsageAccessButtonInvokesTheCallback() {
        var opened = false
        val row = PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = false)
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state(permissions = listOf(row)),
                    Modifier,
                    onOpenUsageAccessSettings = { opened = true },
                )
            }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.action("PACKAGE_USAGE_STATS"))
            .performScrollTo().performClick()
        assertTrue("tapping the button must invoke the usage-access callback", opened)
    }

    /** An install-time row's action text is always shown, regardless of `held` -- it is
     * true unconditionally, and saying so plainly is the whole point of the row. */
    @Test
    fun anInstallTimePermissionAlwaysReadsHeldWithNoActionNeeded() {
        val row = PermissionRow("FOREGROUND_SERVICE", PermissionKind.InstallTime, held = true)
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = listOf(row)), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.row("FOREGROUND_SERVICE")).performScrollTo()
        compose.onNodeWithText("Held").assertIsDisplayed()
        compose.onNodeWithText("No action needed — granted automatically at install.")
            .assertIsDisplayed()
    }

    /** Multiple rows each render under their own per-permission tag -- proves the section
     * does not collapse or overwrite one permission's row with another's. */
    @Test
    fun multiplePermissionsEachRenderUnderTheirOwnRow() {
        val rows = listOf(
            PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = true),
            PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = false),
            PermissionRow("WAKE_LOCK", PermissionKind.InstallTime, held = true),
        )
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(permissions = rows), Modifier) }
        }

        compose.onNodeWithTag(SettingsPermissionsTags.SECTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsPermissionsTags.row("POST_NOTIFICATIONS")).assertIsDisplayed()
        compose.onNodeWithTag(SettingsPermissionsTags.row("PACKAGE_USAGE_STATS")).assertIsDisplayed()
        compose.onNodeWithTag(SettingsPermissionsTags.row("WAKE_LOCK")).assertIsDisplayed()
    }
}

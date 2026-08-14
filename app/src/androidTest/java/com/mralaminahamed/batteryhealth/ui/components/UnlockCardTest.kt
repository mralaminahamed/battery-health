package com.mralaminahamed.batteryhealth.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuAvailability
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Important 1's UI half: a failed dump while genuinely [ShizukuAvailability.Bound] must
 * be visible and retryable, not silently indistinguishable from Shizuku never having
 * connected. See [UnlockCard]'s own doc for why `dumpFailed` is the one case that
 * overrides "render nothing once Bound".
 */
class UnlockCardTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersNothingWhenBoundAndTheLastDumpDidNotFail() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = ShizukuAvailability.Bound,
                    dumpFailed = false,
                    onRequestPermission = {},
                    onOpenShizuku = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onAllNodesWithTag(UnlockCardTags.ROOT).assertCountEquals(0)
    }

    @Test
    fun showsARetryCardWhenBoundButTheDumpFailed() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = ShizukuAvailability.Bound,
                    dumpFailed = true,
                    onRequestPermission = {},
                    onOpenShizuku = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        // SectionHeader upper-cases its text (confirmed on-device: "BATTERY HEALTH",
        // "UNLOCK MORE READINGS", etc. all render shouting-case), so the header text set
        // in UnlockCard's own `if (boundButFailed) "Privileged read failed" else ...`
        // reaches the screen as "PRIVILEGED READ FAILED".
        compose.onNodeWithText("PRIVILEGED READ FAILED").assertIsDisplayed()
        compose.onNodeWithTag(UnlockCardTags.ACTION).assertIsDisplayed()
    }

    @Test
    fun tappingRetryOnAFailedDumpInvokesTheCallback() {
        var retried = false
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = ShizukuAvailability.Bound,
                    dumpFailed = true,
                    onRequestPermission = {},
                    onOpenShizuku = {},
                    onLearnMore = {},
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ACTION).performClick()

        assertTrue("expected onRetry to have been invoked", retried)
    }

    /** Regression against Connecting gaining a spurious retry action: `dumpFailed` only
     * ever applies once genuinely `Bound`. */
    @Test
    fun connectingShowsNoActionRegardlessOfDumpFailed() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = ShizukuAvailability.Connecting,
                    dumpFailed = true,
                    onRequestPermission = {},
                    onOpenShizuku = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Connecting to Shizuku…").assertIsDisplayed()
        compose.onAllNodesWithTag(UnlockCardTags.ACTION).assertCountEquals(0)
    }
}

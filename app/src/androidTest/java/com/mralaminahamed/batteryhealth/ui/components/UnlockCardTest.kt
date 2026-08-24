package com.mralaminahamed.batteryhealth.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.mralaminahamed.batteryhealth.data.privileged.Transport
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Important 1's UI half: a failed dump while genuinely [PrivilegedAvailability.Ready] must
 * be visible and retryable, not silently indistinguishable from the tier never having
 * connected. See [UnlockCard]'s own doc for why `dumpFailed` is the one case that
 * overrides "render nothing once Ready". Also covers the other four states' label/handler
 * pairing -- each asserted by its own test so a state's button text and the action behind
 * it can never drift apart without a failure pointing at exactly which state broke.
 */
class UnlockCardTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersNothingWhenReadyAndTheLastDumpSucceeded() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Ready(Transport.Adb),
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onAllNodesWithTag(UnlockCardTags.ROOT).assertCountEquals(0)
    }

    @Test
    fun explainsTheAdbStepAndOffersHowToEnableWhenUnavailable() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Unavailable,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        compose.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("How to enable")
    }

    @Test
    fun tappingHowToEnableInvokesOnLearnMore() {
        var learnMoreTapped = false
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Unavailable,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = { learnMoreTapped = true },
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ACTION).performClick()

        assertTrue("expected onLearnMore to have been invoked", learnMoreTapped)
    }

    @Test
    fun showsNoActionWhileAwaitingAuthorization() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.AwaitingAuthorization,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        // No button: the action is on the system dialog the user is looking at, not in
        // this card. A button here would compete with the prompt the user is supposed
        // to be answering.
        compose.onAllNodesWithTag(UnlockCardTags.ACTION).assertCountEquals(0)
    }

    @Test
    fun offersTryAgainAfterDeniedAndInvokesOnConnect() {
        var connectTapped = false
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Denied,
                    dumpFailed = false,
                    onConnect = { connectTapped = true },
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("Try again")
        compose.onNodeWithTag(UnlockCardTags.ACTION).performClick()

        assertTrue("expected onConnect to have been invoked", connectTapped)
    }

    @Test
    fun offersRetryWhenReadyButTheLastDumpCameBackEmpty() {
        var retried = false
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Ready(Transport.Root),
                    dumpFailed = true,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = { retried = true },
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        // SectionHeader upper-cases its text (confirmed on-device: "BATTERY HEALTH",
        // "UNLOCK MORE READINGS", etc. all render shouting-case), so the header text set
        // in UnlockCard's own `if (readyButFailed) "Privileged read failed" else ...`
        // reaches the screen as "PRIVILEGED READ FAILED".
        compose.onNodeWithText("PRIVILEGED READ FAILED").assertIsDisplayed()
        compose.onNodeWithTag(UnlockCardTags.ACTION).assertTextEquals("Retry")

        compose.onNodeWithTag(UnlockCardTags.ACTION).performClick()

        assertTrue("expected onRetry to have been invoked", retried)
    }

    /** Regression against Connecting gaining a spurious retry action: `dumpFailed` only
     * ever applies once genuinely `Ready`. */
    @Test
    fun connectingShowsNoActionRegardlessOfDumpFailed() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Connecting,
                    dumpFailed = true,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                )
            }
        }

        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onAllNodesWithTag(UnlockCardTags.ACTION).assertCountEquals(0)
    }
}

package com.alaminahamed.batteryhealth.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.data.privileged.Transport
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
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
 *
 * Several tests below also pin a substring of that state's [UnlockCard] explanation prose,
 * via `onNodeWithText(..., substring = true)` rather than [assertTextEquals]: header text and
 * action labels are short and meant to stay literal, but the explanation is the
 * honesty-critical part of this card -- it is what tells the user the app cannot grant itself
 * this access, that the adb workaround has a real per-reboot cost (except for a rooted
 * device, which is exempt from it), that a denial is recoverable and bounded, and that a
 * failed dump is a dropped shell call rather than a denial, with a retry that costs nothing.
 * None of that was guarded before; a copy edit could soften or delete any of those claims
 * and every existing test (header/action only) would stay green. The pinned substrings are
 * deliberately short claims, not full sentences, so ordinary copy-editing around them does
 * not also break the test.
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

        // Honesty-critical claim #1: this app cannot grant itself the permission -- the
        // whole reason a "How to enable" button exists instead of a "Grant" button.
        compose.onNodeWithText("this app cannot request on its own", substring = true)
            .assertIsDisplayed()
        // Honesty-critical claim #2: the adb workaround is not a one-time setup step --
        // it has a real, recurring cost the user is told about up front.
        compose.onNodeWithText("repeat that command each time your phone restarts", substring = true)
            .assertIsDisplayed()
        // Honesty-critical claim #3: root is not just another way to reach the same adb
        // step -- it is exempt from the recurring cost claim #2 just pinned. Dropping or
        // inverting this line would misstate `Transport.Root`'s actual behavior.
        compose.onNodeWithText("A rooted device skips this step entirely", substring = true)
            .assertIsDisplayed()
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

        // Honesty-critical claim: the user is pointed at their own device's screen, not
        // told to wait on this app -- there is nothing this card itself can do here.
        compose.onNodeWithText("Check your screen", substring = true).assertIsDisplayed()
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

        // Honesty-critical claim #1: a denial does not degrade the rest of the app --
        // only the privileged readings are affected.
        compose.onNodeWithText("Nothing else in the app is affected", substring = true)
            .assertIsDisplayed()
        // Honesty-critical claim #2: the denial is not permanent -- the user can retry
        // on their own terms, whenever they like.
        compose.onNodeWithText("you can try again whenever you like", substring = true)
            .assertIsDisplayed()

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

        // Honesty-critical claim #1: this is a transient, most-likely-a-dropped-call
        // failure, not a permission problem -- see UnlockCard's own class doc calling
        // dumpFailed "the one exception" this card exists to cover. Attributing this to
        // denial (e.g. "Access denied for this reading") would be a false claim.
        compose.onNodeWithText("most likely a dropped shell call", substring = true)
            .assertIsDisplayed()
        // Honesty-critical claim #2: retrying carries no cost or risk to the user, which
        // is the whole reason a bare "Retry" button is an honest thing to offer here.
        compose.onNodeWithText("Retrying costs nothing", substring = true).assertIsDisplayed()

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

    // ---- dismissal -------------------------------------------------------------------

    @Test
    fun anOfferCanBeDismissedAndThenStaysGone() {
        var dismissed = false
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Unavailable,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                    dismissed = dismissed,
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.DISMISS).performClick()
        compose.waitForIdle()
        assertTrue("onDismiss should have fired", dismissed)
    }

    @Test
    fun aDismissedOfferRendersNothingAtAll() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.Unavailable,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                    dismissed = true,
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertDoesNotExist()
    }

    /**
     * The line dismissal must not cross. A user staring at the system's authorization
     * dialog needs the card that explains it, whatever they dismissed earlier, and a
     * failed privileged read needs its retry -- without it every privileged row silently
     * reads "needs privileged access" again with no way back.
     */
    @Test
    fun dismissalDoesNotHideWhatTheUserThemselvesStarted() {
        compose.setContent {
            BatteryHealthTheme {
                UnlockCard(
                    availability = PrivilegedAvailability.AwaitingAuthorization,
                    dumpFailed = false,
                    onConnect = {},
                    onLearnMore = {},
                    onRetry = {},
                    dismissed = true,
                )
            }
        }

        compose.onNodeWithTag(UnlockCardTags.ROOT).assertIsDisplayed()
        // ...and offers no dismiss control, because dismissing it would do nothing.
        compose.onNodeWithTag(UnlockCardTags.DISMISS).assertDoesNotExist()
    }
}

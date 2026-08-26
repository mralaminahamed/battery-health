package com.alaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object UnlockCardTags {
    const val ROOT = "unlock-card"
    const val ACTION = "unlock-card-action"
    const val DISMISS = "unlock-card-dismiss"
}

/**
 * When the card renders, and when it offers to be dismissed.
 *
 * Pure and separate from the composable so both rules are provable on the JVM, and
 * because the second one is a judgement rather than a detail: dismissal silences the card
 * only where the card is an *offer*, never where it is the consequence of something the
 * user themselves started.
 *
 * That distinction is the whole design. A dismissal that also swallowed
 * [PrivilegedAvailability.AwaitingAuthorization] would leave a user staring at a system
 * dialog with nothing on screen explaining it; one that swallowed the failed-dump state
 * would remove the only retry and leave every privileged row reading "needs privileged
 * access" with no way back. One tap would have turned into a permanently broken setup
 * flow.
 */
internal object UnlockCardVisibility {

    /**
     * States that are purely an offer to set up a feature the user has not asked for.
     * These are what dismissal is for: a user who has decided they do not want the
     * privileged tier should not be pitched it on every launch forever.
     */
    fun isDismissible(availability: PrivilegedAvailability, dumpFailed: Boolean): Boolean =
        when (availability) {
            PrivilegedAvailability.Unavailable, PrivilegedAvailability.Denied -> true
            PrivilegedAvailability.AwaitingAuthorization,
            PrivilegedAvailability.Connecting,
            -> false
            // Ready renders only when the dump failed, and that state is a retry the user
            // needs rather than an offer they can decline.
            is PrivilegedAvailability.Ready -> false
        }

    fun shouldShow(
        availability: PrivilegedAvailability,
        dumpFailed: Boolean,
        dismissed: Boolean,
    ): Boolean {
        // Predates dismissal and is unaffected by it: once the tier works, the values
        // this card explained the absence of are already on screen, and a card still
        // advertising a state that no longer holds is worse than no card.
        if (availability is PrivilegedAvailability.Ready && !dumpFailed) return false
        return !(dismissed && isDismissible(availability, dumpFailed))
    }
}

/**
 * The Health screen's honest explanation of the privileged tier, and the only entry
 * point into it: state of health (ASOC/BSOH), first-use date and Battery Protect all
 * read `NeedsPrivilegedAccess` until the user acts here.
 *
 * Deliberately not one "connect" button behind one boolean -- [PrivilegedAvailability]
 * has five states, and each needs its own explanation and, where one applies, its own
 * action; a single flag would have to guess which one the user is actually in. Renders
 * nothing once [PrivilegedAvailability.Ready] is reached *and* [dumpFailed] is false: at
 * that point the values it was explaining the absence of have already started appearing
 * through the ordinary [ReadingSlot] rows elsewhere on the screen, and a card that keeps
 * advertising a state that no longer holds is worse than no card. [dumpFailed] is the
 * one exception to "nothing once Ready": a connection that succeeded but whose most
 * recent `dumpsys battery` attempt came back empty is not the same as a working
 * privileged tier, and without this card the only visible symptom would be every
 * privileged row silently reading `NeedsPrivilegedAccess` again -- indistinguishable
 * from the tier never having been connected at all, with no explanation and no way to
 * retry short of toggling the connection off and back on.
 *
 * There is no companion app to open here and no permission this app can grant itself:
 * [onConnect] only ever attempts the adb or root connection that the user's own setup
 * (a computer running one `adb tcpip` command, or a rooted device) has already made
 * possible. It is the one entry point this card offers, replacing the two separate
 * callbacks an earlier, now-removed permission model needed.
 */
@Composable
fun UnlockCard(
    availability: PrivilegedAvailability,
    dumpFailed: Boolean,
    onConnect: () -> Unit,
    onLearnMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    dismissed: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    if (!UnlockCardVisibility.shouldShow(availability, dumpFailed, dismissed)) return

    val colors = LocalOneUiColors.current
    val readyButFailed = availability is PrivilegedAvailability.Ready && dumpFailed
    OneUiCard(modifier.testTag(UnlockCardTags.ROOT)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader(if (readyButFailed) "Privileged read failed" else "Unlock more readings")
            // Offered only where dismissing actually silences something -- see
            // UnlockCardVisibility.isDismissible. A control that visibly did nothing on
            // the other states would be worse than no control.
            if (UnlockCardVisibility.isDismissible(availability, dumpFailed)) {
                // A word rather than a bare glyph: material-icons is not a dependency of
                // this module and is not worth adding for one control, and "Dismiss" is
                // what a screen reader should say anyway -- an unlabelled multiplication
                // sign is announced as punctuation or skipped entirely.
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(UnlockCardTags.DISMISS),
                ) {
                    Text(
                        text = "Dismiss",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        Text(
            text = explanation(availability, dumpFailed),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        val action = actionFor(availability, dumpFailed, onConnect, onLearnMore, onRetry)
        if (action != null) {
            val (label, onClick) = action
            Button(
                onClick = onClick,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag(UnlockCardTags.ACTION),
            ) { Text(label) }
        }
    }
}

/**
 * State of health, first-use date and Battery Protect all sit behind `BATTERY_STATS`, a
 * signature-level permission this app cannot hold itself. Reading it needs either a
 * per-boot `adb tcpip` command run from a computer, or a rooted device -- neither of
 * which this app can set up on its own. That real burden is stated plainly rather than
 * papered over with a single friendly button that quietly can't do what it implies.
 */
private fun explanation(availability: PrivilegedAvailability, dumpFailed: Boolean): String = when (availability) {
    PrivilegedAvailability.Unavailable ->
        "State of health, first-use date and Battery Protect status sit behind a " +
            "permission this app cannot request on its own. Run \"adb tcpip 5555\" " +
            "from a computer with your device connected, and this app takes it from " +
            "there -- you'll need to repeat that command each time your phone " +
            "restarts. A rooted device skips this step entirely."
    PrivilegedAvailability.AwaitingAuthorization ->
        "Check your screen -- your device is asking whether to allow this. Approve " +
            "it and the readings appear."
    PrivilegedAvailability.Denied ->
        "Access was declined, so the privileged readings stay hidden. Nothing else " +
            "in the app is affected, and you can try again whenever you like."
    PrivilegedAvailability.Connecting -> "Connecting…"
    is PrivilegedAvailability.Ready ->
        if (dumpFailed) {
            "Connected, but the last privileged read didn't come back -- most " +
                "likely a dropped shell call. Retrying costs nothing and often just " +
                "works."
        } else {
            "" // unreachable; UnlockCard returns before rendering this case
        }
}

/**
 * Label paired with its handler by state, in one place, so there is no way for the
 * button text and the action it performs to describe two different states (the
 * defect a separate `when` per property risks the moment one of them is edited alone).
 */
private fun actionFor(
    availability: PrivilegedAvailability,
    dumpFailed: Boolean,
    onConnect: () -> Unit,
    onLearnMore: () -> Unit,
    onRetry: () -> Unit,
): Pair<String, () -> Unit>? = when (availability) {
    PrivilegedAvailability.Unavailable -> "How to enable" to onLearnMore
    PrivilegedAvailability.AwaitingAuthorization -> null
    PrivilegedAvailability.Denied -> "Try again" to onConnect
    PrivilegedAvailability.Connecting -> null
    is PrivilegedAvailability.Ready -> if (dumpFailed) "Retry" to onRetry else null
}

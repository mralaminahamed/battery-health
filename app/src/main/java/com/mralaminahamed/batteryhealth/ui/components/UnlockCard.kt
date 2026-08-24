package com.mralaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object UnlockCardTags {
    const val ROOT = "unlock-card"
    const val ACTION = "unlock-card-action"
}

/**
 * The Health screen's honest explanation of the privileged tier, and the only entry
 * point into it: state of health (ASOC/BSOH), first-use date and Battery Protect all
 * read `NeedsShizuku` until the user acts here.
 *
 * Deliberately not one "connect" button behind one boolean -- [PrivilegedAvailability]
 * has several distinct not-yet-ready states, each with its own next action, and a single
 * flag would have to guess which one applies. Renders nothing once
 * [PrivilegedAvailability.Ready] is reached *and* [dumpFailed] is false: at that point the
 * values it was explaining the absence of have already started appearing through the
 * ordinary [ReadingSlot] rows elsewhere on the screen, and a card that keeps advertising
 * a state that no longer holds is worse than no card. [dumpFailed] is the one exception
 * to "nothing once Ready": a connection that succeeded but whose most recent `dumpsys
 * battery` attempt came back empty is not the same as a working privileged tier, and
 * without this card the only visible symptom would be every privileged row silently
 * reading `NeedsShizuku` again -- indistinguishable from the tier never having been
 * connected at all, with no explanation and no way to retry short of toggling the
 * connection off and back on.
 *
 * Interim copy: this card's exact wording is a placeholder pending a dedicated pass over
 * the adb/root states (see the privileged-tier plan's later task). What is here now is
 * accurate for each [PrivilegedAvailability] case, just not yet polished.
 */
@Composable
fun UnlockCard(
    availability: PrivilegedAvailability,
    dumpFailed: Boolean,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onLearnMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availability is PrivilegedAvailability.Ready && !dumpFailed) return

    val colors = LocalOneUiColors.current
    val readyButFailed = availability is PrivilegedAvailability.Ready && dumpFailed
    OneUiCard(modifier.testTag(UnlockCardTags.ROOT)) {
        SectionHeader(if (readyButFailed) "Privileged read failed" else "Unlock more readings")
        Text(
            text = explanation(availability, dumpFailed),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        val action = actionFor(availability, dumpFailed, onRequestPermission, onOpenShizuku, onLearnMore, onRetry)
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
 * State of health, first-use date and Battery Protect all sit behind
 * `BATTERY_STATS`, a signature-level permission this app cannot hold itself -- reading it
 * needs either a one-time `adb tcpip` command from a computer (adb debugging) or a rooted
 * device, neither of which this app can set up on its own. That real burden is stated
 * plainly rather than papered over with a single friendly button that quietly can't do
 * what it implies.
 */
private fun explanation(availability: PrivilegedAvailability, dumpFailed: Boolean): String = when (availability) {
    PrivilegedAvailability.Unavailable ->
        "State of health, first-use date and Battery Protect status are hidden behind " +
            "a permission this app cannot request on its own. Connect once from a " +
            "computer with a one-time adb command, or use a rooted device -- either " +
            "unlocks them."
    PrivilegedAvailability.AwaitingAuthorization ->
        "Check your device -- it's asking whether to allow this connection."
    PrivilegedAvailability.Denied ->
        "Access was declined, so the privileged readings stay hidden. You can try " +
            "again whenever you like."
    PrivilegedAvailability.Connecting -> "Connecting…"
    is PrivilegedAvailability.Ready ->
        if (dumpFailed) {
            "Connected, but the last privileged read didn't come back -- most likely " +
                "a dropped shell call. Retrying costs nothing and often just works."
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
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onLearnMore: () -> Unit,
    onRetry: () -> Unit,
): Pair<String, () -> Unit>? = when (availability) {
    PrivilegedAvailability.Unavailable -> "How to enable" to onLearnMore
    PrivilegedAvailability.AwaitingAuthorization -> null
    PrivilegedAvailability.Denied -> "Try again" to onRequestPermission
    PrivilegedAvailability.Connecting -> null
    is PrivilegedAvailability.Ready -> if (dumpFailed) "Retry" to onRetry else null
}

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
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuAvailability
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
 * Deliberately not one "connect" button behind one boolean -- [ShizukuAvailability] has
 * four distinct not-yet-bound states, each with its own next action, and a single
 * flag would have to guess which one applies. Renders nothing once
 * [ShizukuAvailability.Bound] is reached: at that point the values it was explaining
 * the absence of have already started appearing through the ordinary [ReadingSlot] rows
 * elsewhere on the screen, and a card that keeps advertising a state that no longer
 * holds is worse than no card.
 */
@Composable
fun UnlockCard(
    availability: ShizukuAvailability,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onLearnMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availability == ShizukuAvailability.Bound) return

    val colors = LocalOneUiColors.current
    OneUiCard(modifier.testTag(UnlockCardTags.ROOT)) {
        SectionHeader("Unlock more readings")
        Text(
            text = explanation(availability),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        val action = actionFor(availability, onRequestPermission, onOpenShizuku, onLearnMore)
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
 * `BATTERY_STATS`, a signature-level permission this app cannot hold itself -- Shizuku
 * is a separate app, installed and started on its own (via wireless debugging or ADB),
 * not a permission this screen can request by itself the way it does for
 * notifications. That real burden is stated plainly rather than papered over with a
 * single friendly button that quietly can't do what it implies.
 */
private fun explanation(availability: ShizukuAvailability): String = when (availability) {
    ShizukuAvailability.NotInstalled ->
        "State of health, first-use date and Battery Protect status are hidden behind " +
            "a permission this app cannot request on its own. Shizuku is a separate, " +
            "free app that unlocks them -- it has to be installed and started " +
            "yourself, via wireless debugging or ADB."
    ShizukuAvailability.NotRunning ->
        "Shizuku is installed but not running. Open it and start it -- it walks you " +
            "through wireless debugging or a one-time ADB command."
    ShizukuAvailability.PermissionNotGranted ->
        "Shizuku is running. Grant this app permission to read the privileged battery " +
            "values."
    ShizukuAvailability.Connecting -> "Connecting to Shizuku…"
    ShizukuAvailability.Bound -> "" // unreachable; UnlockCard returns before rendering
}

/**
 * Label paired with its handler by state, in one place, so there is no way for the
 * button text and the action it performs to describe two different states (the
 * defect a separate `when` per property risks the moment one of them is edited alone).
 */
private fun actionFor(
    availability: ShizukuAvailability,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onLearnMore: () -> Unit,
): Pair<String, () -> Unit>? = when (availability) {
    ShizukuAvailability.NotInstalled -> "Get Shizuku" to onLearnMore
    ShizukuAvailability.NotRunning -> "Open Shizuku" to onOpenShizuku
    ShizukuAvailability.PermissionNotGranted -> "Grant permission" to onRequestPermission
    ShizukuAvailability.Connecting, ShizukuAvailability.Bound -> null
}

package com.alaminahamed.batteryhealth.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.alaminahamed.batteryhealth.R
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.alaminahamed.batteryhealth.domain.AppBucket
import com.alaminahamed.batteryhealth.data.apps.AppCpuRow
import com.alaminahamed.batteryhealth.data.apps.AppLabel
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.domain.UidKind
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import java.util.Locale
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object AppsScreenTags {
    const val ROOT = "apps-root"
    const val SKELETON = "apps-skeleton"
}

/**
 * The fourth navigation destination: per-uid CPU time from `SystemHealthManager`.
 *
 * This screen used to lead with per-uid battery power from `dumpsys batterystats
 * --checkin`, reached through a privileged adb or root shell. That shell tier is gone --
 * this app now asks for nothing beyond a normal Android permission flow, and neither adb
 * nor root is either -- so that mAh figure has no source left at any price and the rows
 * that rendered it ([com.alaminahamed.batteryhealth.data.apps.AppRow]`,
 * `AppRowMapper`, `AppPowerAggregator`) were deleted along with it. What is left is
 * [CpuTimeSection], which every build already rendered on its own for a device with no
 * shell -- see the task report for why per-uid CPU time still needs `BATTERY_STATS` too,
 * and is therefore likely to read "Needs the one-time permission" for the same reason the
 * old power rows would have.
 */
@Composable
fun AppsScreen(modifier: Modifier = Modifier, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    val currentViewModel by rememberUpdatedState(viewModel)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentViewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppsContent(state = state, modifier = modifier)
}

@Composable
fun AppsContent(
    state: AppsUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(AppsScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        CpuTimeSection(state.cpuRows)
    }
}

@Composable
private fun AppRowIcon(kind: UidKind, label: AppLabel?) {
    val colors = LocalOneUiColors.current
    val drawable = (label as? AppLabel.Resolved)?.icon
    if (drawable != null) {
        val bitmap = remember(drawable) { drawable.toBitmap(width = 128, height = 128).asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    } else {
        // A drawn icon, not a text glyph.
        //
        // A letter in a box reads as a different *kind* of row from one with a real
        // launcher icon, even at identical dimensions -- text sits inside its own line
        // box and never fills the slot the way an icon does. These vectors occupy the
        // full 32dp square, so a row the app could not identify looks like a row, not
        // like a smaller or lesser one.
        //
        // Each says what it actually is rather than standing in generically: a chip for
        // part of the phone, a terminal for the shell uid, a broken ring for something
        // this build could not identify at all. Nothing here is a plausible-looking app
        // icon, which would be the one genuinely misleading option.
        val isApp = kind == UidKind.App
        val icon = when (kind) {
            UidKind.System -> R.drawable.ic_row_system
            UidKind.Shell -> R.drawable.ic_row_shell
            UidKind.App -> R.drawable.ic_row_unidentified
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isApp) colors.accent.copy(alpha = 0.18f) else colors.divider),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (isApp) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Per-app CPU time.
 *
 * Headed "CPU time" and never "battery use". `SystemHealthManager` is the only public
 * per-uid source, and on real hardware its power buckets are empty while its CPU time
 * buckets are populated and differentiated. Android's own per-app mAh figure is these
 * same times multiplied by `power_profile.xml` coefficients -- a model, computed behind a
 * permission wall. This app can do that arithmetic and does not, because a modelled number
 * shown beside measured ones, in the same list, is indistinguishable from a measurement.
 */
@Composable
private fun CpuTimeSection(rows: Reading<List<AppCpuRow>>) {
    val colors = LocalOneUiColors.current
    OneUiCard {
        SectionHeader("CPU time by app")
        Text(
            text = "How long each app has held the CPU since the last full charge. This " +
                "is time, not power — Android reports no per-app power figure to " +
                "ordinary apps, and estimating one from CPU time would be a guess dressed " +
                "as a measurement.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }

    val ranked = (rows as? Reading.Available)?.value
    if (ranked != null && ranked.isNotEmpty()) {
        // Tab state is remembered per composition rather than hoisted into AppsUiState:
        // it is a view preference with no bearing on any reading, and putting it in the
        // state flow would make every CPU refresh a reason to re-emit it.
        var selected by rememberSaveable { mutableStateOf(AppBucket.Visible) }
        val byBucket = remember(ranked) { ranked.groupBy { it.bucket } }

        OneUiCard {
            Row(modifier = Modifier.fillMaxWidth()) {
                AppBucket.entries.forEach { bucket ->
                    val count = byBucket[bucket]?.size ?: 0
                    TextButton(
                        onClick = { selected = bucket },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cpu-tab-${bucket.name}"),
                    ) {
                        Text(
                            // The count is part of the label, not a badge: an empty tab
                            // should be visibly empty before it is tapped, so nobody hunts
                            // through three lists looking for rows that are not there.
                            text = "${bucket.tabLabel()} $count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bucket == selected) colors.accent else colors.textSecondary,
                        )
                    }
                }
            }
        }

        val shown = byBucket[selected].orEmpty()
        if (shown.isEmpty()) {
            OneUiCard {
                KeyValueRow(selected.emptyText(), showDivider = false) {}
            }
            return
        }

        OneUiCard {
            shown.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppRowIcon(row.kind, row.label)
                    Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            text = cpuRowTitle(row),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                        )
                        // Share stays relative to every visible row, not to the selected
                        // tab: a percentage that changed when you switched tabs would be
                        // describing the tab rather than the device.
                        Text(
                            text = String.format(Locale.US, "%.1f%% of visible CPU time", row.sharePct),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                    Value(Formatters.cpuTime(row.totalCpuMs))
                }
                if (index != shown.lastIndex) HorizontalDivider(color = colors.divider)
            }
        }
        return
    }

    // Every remaining case is an absence, and they are not interchangeable: a missing
    // permission names what would unlock it, a device that has nothing is not
    // actionable, and "measuring" says a figure is coming. Exhaustive so a fourth
    // Reading case has to be answered here rather than falling into whichever branch is
    // last.
    val text = when (rows) {
        Reading.NeedsPrivilegedAccess -> "Needs the one-time permission"
        Reading.NotYetMeasured -> "Measuring"
        Reading.Unsupported -> "Not available on this device"
        is Reading.Available -> "No CPU time recorded yet"
    }
    OneUiCard {
        KeyValueRow("CPU time", showDivider = false) { Value(text) }
    }
}

/**
 * A CPU row's heading: the resolved app name where one was confirmed, otherwise the raw
 * package identifier, never something in between. An unresolved row shows what is
 * actually known about it rather than a guess.
 */
private fun cpuRowTitle(row: AppCpuRow): String = when (val label = row.label) {
    is AppLabel.Resolved -> label.label
    is AppLabel.PackageNameOnly -> label.packageName
    AppLabel.Unknown -> "uid ${row.uid}"
}

/** Tab titles. Short, because three have to fit across a phone's width. */
private fun AppBucket.tabLabel(): String = when (this) {
    AppBucket.Visible -> "Visible"
    AppBucket.Hidden -> "Hidden"
    AppBucket.System -> "System"
}

/**
 * What an empty tab says. Each explains why it is empty rather than sharing one blank
 * message -- "nothing here" invites the reader to assume the app failed to look.
 */
private fun AppBucket.emptyText(): String = when (this) {
    AppBucket.Visible -> "No launchable app has used the CPU yet"
    AppBucket.Hidden -> "No background-only app has used the CPU yet"
    AppBucket.System -> "No platform uid has used the CPU yet"
}

package com.alaminahamed.batteryhealth.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
import com.alaminahamed.batteryhealth.data.apps.EstimatedAppRow
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.domain.UidKind
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import com.alaminahamed.batteryhealth.ui.health.SourceChip
import com.alaminahamed.batteryhealth.ui.settings.openUsageAccessSettings
import com.alaminahamed.batteryhealth.ui.theme.LocalDesignLanguage
import java.util.Locale
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object AppsScreenTags {
    const val ROOT = "apps-root"
    const val SKELETON = "apps-skeleton"
    const val USAGE_ACCESS_CARD = "apps-usage-access-card"
    const val USAGE_ACCESS_ACTION = "apps-usage-access-action"
}

/**
 * The fourth navigation destination: two independent per-app views.
 *
 * This screen used to lead with per-uid battery power from `dumpsys batterystats
 * --checkin`, reached through a privileged adb or root shell. That shell tier is gone --
 * this app now asks for nothing beyond a normal Android permission flow, and neither adb
 * nor root is either -- so that mAh figure has no source left at any price and the rows
 * that rendered it ([com.alaminahamed.batteryhealth.data.apps.AppRow]`,
 * `AppRowMapper`, `AppPowerAggregator`) were deleted along with it. [CpuTimeSection] is
 * what remains from that removal: per-uid CPU time still needs `BATTERY_STATS`, which is
 * just as unreachable through a normal install, so it reads "Needs the one-time
 * permission" for the same reason the old power rows would have.
 *
 * [EstimatedDrainSection] is this app's actual answer to the same question `CpuTimeSection`
 * can no longer give one for: a per-app battery-drain *estimate*, apportioned from this
 * app's own measured discharge by how long each package held the foreground.
 * `PACKAGE_USAGE_STATS` is appop-gated behind an ordinary Settings toggle -- no adb, no
 * root, no companion app -- and is the only route left to per-app data at all.
 */
@Composable
fun AppsScreen(modifier: Modifier = Modifier, viewModel: AppsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

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

    AppsContent(
        state = state,
        modifier = modifier,
        onOpenUsageAccessSettings = { openUsageAccessSettings(context) },
    )
}

@Composable
fun AppsContent(
    state: AppsUiState,
    modifier: Modifier = Modifier,
    onOpenUsageAccessSettings: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(AppsScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        CpuTimeSection(state.cpuRows)
        EstimatedDrainSection(state.estimatedDrainRows, onOpenUsageAccessSettings)
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
        // Unreachable for cpuRows by construction -- UidCpuTimeSource never returns this
        // absence, only EstimatedDrainSection's own Reading does -- but this stays
        // exhaustive rather than an `else`, the same discipline every Reading `when` in
        // this app is held to.
        Reading.NeedsUsageAccess -> "Needs usage access"
        Reading.NotYetMeasured -> "Measuring"
        Reading.Unsupported -> "Not available on this device"
        is Reading.Available -> "No CPU time recorded yet"
    }
    OneUiCard {
        KeyValueRow("CPU time", showDivider = false) { Value(text) }
    }
}

/**
 * Per-app battery drain, *estimated* by apportioning this app's own measured discharge
 * across packages by how long each held the foreground -- the one per-app route left that
 * needs nothing beyond a normal Android permission flow, now that the privileged tier
 * behind [CpuTimeSection] is unreachable on every real install. See `EstimateWindow`,
 * `EstimatedAppDrain` and `EstimatedDrainReading` (`data.repo`) for the arithmetic and the
 * absence rules this section's own `when` mirrors.
 */
@Composable
private fun EstimatedDrainSection(
    reading: Reading<EstimatedDrain>,
    onOpenUsageAccessSettings: () -> Unit,
) {
    if (reading == Reading.NeedsUsageAccess) {
        UsageAccessCard(onOpenSettings = onOpenUsageAccessSettings)
        return
    }

    val colors = LocalDesignLanguage.current.colors
    OneUiCard {
        SectionHeader("Estimated drain from screen time")
        val available = (reading as? Reading.Available)?.value
        if (available != null && available.rows.isNotEmpty()) {
            Text(
                text = "These are estimates, not measurements. This app estimated " +
                    "${Formatters.milliampHours(available.totalMah)} of battery used over " +
                    "the past " +
                    "${Formatters.duration(available.windowEndMs - available.windowStartMs)} " +
                    "and split it between apps by how long each was on screen during that " +
                    "same period. The battery figure excludes any time spent charging; the " +
                    "screen-time split does not, so a period that included charging will " +
                    "show more on-screen time than the battery figure alone would suggest. " +
                    "Screen time is not energy: background activity, mobile data, and " +
                    "location are not counted, and the screen's own drain is credited to " +
                    "whichever app was in front of it.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Column(Modifier.fillMaxWidth()) {
                available.rows.forEachIndexed { index, row ->
                    EstimatedAppPowerRow(row, showDivider = index != available.rows.lastIndex)
                }
            }
        } else {
            // Every remaining case is an absence, and NeedsUsageAccess never reaches here
            // (handled above, before this card is even built). Unsupported and
            // NeedsPrivilegedAccess are unreachable for this Reading by construction --
            // see AppsUiState's own doc -- but this stays exhaustive rather than an
            // `else`, the same discipline every Reading `when` in this app is held to.
            val text = when (reading) {
                is Reading.Available -> "No screen-time drain estimated yet"
                Reading.NotYetMeasured -> "Measuring"
                Reading.Unsupported -> "Not available on this device"
                Reading.NeedsPrivilegedAccess -> "Not available on this device"
                Reading.NeedsUsageAccess -> "Needs usage access"
            }
            KeyValueRow("Estimated drain", showDivider = false) { Value(text) }
        }
    }
}

/**
 * The Apps screen's own permission-request card, for the one permission with no runtime
 * dialog: `PACKAGE_USAGE_STATS` is appop-gated, and the only way to grant it is Settings,
 * Special app access, Usage access -- entirely outside this app. See
 * [UsageAccessState][com.alaminahamed.batteryhealth.data.settings.UsageAccessState]'s own
 * doc for how "held" is determined.
 *
 * States what will be read, what it is used for, that it never leaves the device, and
 * that the result is an estimate -- in that order, before the button -- matching Play's
 * prominent-disclosure expectation for a sensitive permission requested through Settings
 * rather than a runtime prompt, and simply the honest order to put it in regardless.
 */
@Composable
private fun UsageAccessCard(onOpenSettings: () -> Unit) {
    val colors = LocalDesignLanguage.current.colors
    OneUiCard(Modifier.testTag(AppsScreenTags.USAGE_ACCESS_CARD)) {
        SectionHeader("Estimate drain from screen time")
        Text(
            text = "This app can estimate which apps used the most battery by reading how " +
                "long each app was shown on screen -- nothing about what you did in it, " +
                "nothing over the network, and nothing that leaves this device. Combined " +
                "with how much battery this app has measured draining, that produces an " +
                "estimate, not a measurement: it assumes drain is roughly proportional to " +
                "screen time, which is a useful assumption but not an exact one. Turning " +
                "this on opens Android's own Usage access screen, outside this app.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .padding(top = 10.dp)
                .testTag(AppsScreenTags.USAGE_ACCESS_ACTION),
        ) { Text("Open usage access settings") }
    }
}

/**
 * [EstimatedAppRow]'s own row rendering -- distinct from [CpuTimeSection]'s CPU rows, and
 * carrying its own [SourceChip] labelled "Estimated": an estimate must never borrow the
 * same styling a measured figure would use, so the mAh figure is rendered in the
 * secondary text colour with a leading `~`, never through [Value].
 *
 * The left side (icon, name, chip, caption) is weighted and the name/caption truncate
 * with an ellipsis; the trailing value column is not weighted, so it is measured at its
 * natural size first and never has to share a line with anything else. Without this, a
 * long, unresolved `play`-flavour package name squeezed the trailing mAh figure into a
 * multi-line wrap that split "mAh" itself across lines.
 */
@Composable
private fun EstimatedAppPowerRow(row: EstimatedAppRow, showDivider: Boolean) {
    val colors = LocalDesignLanguage.current.colors
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EstimatedAppRowIcon(row)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = estimatedPrimaryText(row),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SourceChip(Source.Inferred)
                }
                Text(
                    text = estimatedSecondaryText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                // No Value(...) here -- see this function's own doc for why an estimate
                // must not borrow the same styling a measured figure uses. softWrap =
                // false so this figure truncates rather than wrapping "mAh" onto its own
                // line if it is ever squeezed regardless.
                Text(
                    text = "~${Formatters.milliampHours(row.estimatedMah)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = Formatters.percentShare(row.sharePct),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (showDivider) HorizontalDivider(color = colors.divider)
    }
}

/** [EstimatedAppRow] has no system/shell equivalent -- every row it produces is, by
 * construction, one package usage stats reported foreground time for -- so this needs
 * only the single unresolved-icon shape [AppRowIcon] uses for an app row, never its
 * System/Shell branches. */
@Composable
private fun EstimatedAppRowIcon(row: EstimatedAppRow) {
    val colors = LocalDesignLanguage.current.colors
    val drawable = (row.label as? AppLabel.Resolved)?.icon
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
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_row_unidentified),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Mirrors [cpuRowTitle]'s per-[AppLabel] cases, but [EstimatedAppRow] always has a real
 * [EstimatedAppRow.packageName] to fall back to (usage stats reports package names, never
 * an unnamed uid), so the [AppLabel.Unknown] case here shows the package name rather than
 * a uid -- there is no uid in this row's own data to name instead. */
private fun estimatedPrimaryText(row: EstimatedAppRow): String = when (val label = row.label) {
    is AppLabel.Resolved -> label.label
    is AppLabel.PackageNameOnly -> label.packageName
    AppLabel.Unknown -> row.packageName
}

/**
 * Always states the foreground time this row's share was computed from, in addition to
 * (not instead of) the label-availability caveat where one applies -- the duration is
 * what makes this row's percentage legible on its own, without requiring the reader to
 * already understand [com.alaminahamed.batteryhealth.data.repo.EstimatedAppDrain]'s
 * arithmetic.
 *
 * The duration comes *first*, the label caveat second. This caption is `maxLines = 1`
 * with an ellipsis, and a `play`-flavour row's label caveat is long enough on its own to
 * push the duration past that one line -- with the caveat first, truncation would eat the
 * duration's own unit, so "58 m on screen" would render as "-- 58 …", a number with no
 * unit, which is worse than no number. Putting the duration first means only the caveat,
 * never the number the row exists to justify, is ever what gets cut.
 */
private fun estimatedSecondaryText(row: EstimatedAppRow): String {
    val onScreen = "${Formatters.duration(row.foregroundMs)} on screen"
    return when (row.label) {
        is AppLabel.Resolved -> onScreen
        is AppLabel.PackageNameOnly -> "$onScreen -- package name only, label unavailable"
        AppLabel.Unknown -> "$onScreen -- no app name available"
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

package com.alaminahamed.batteryhealth.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.alaminahamed.batteryhealth.data.apps.AppLabel
import com.alaminahamed.batteryhealth.data.apps.EstimatedAppRow
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import com.alaminahamed.batteryhealth.ui.health.SourceChip
import com.alaminahamed.batteryhealth.ui.settings.openUsageAccessSettings
import com.alaminahamed.batteryhealth.ui.theme.LocalDesignLanguage

object AppsScreenTags {
    const val ROOT = "apps-root"
    const val SKELETON = "apps-skeleton"
    const val USAGE_ACCESS_CARD = "apps-usage-access-card"
    const val USAGE_ACCESS_ACTION = "apps-usage-access-action"
}

/**
 * The fourth navigation destination: a per-app view built entirely on permissions an
 * ordinary install can actually grant.
 *
 * This screen used to lead with per-uid battery power from `dumpsys batterystats
 * --checkin`, reached through a privileged adb or root shell. That shell tier is gone --
 * this app now asks for nothing beyond a normal Android permission flow, and neither adb
 * nor root is either -- so that mAh figure has no source left at any price and the rows
 * that rendered it (`AppRow`, `AppRowMapper`, `AppPowerAggregator`) were deleted along
 * with it. A later per-uid CPU-time section ([EstimatedDrainSection]'s own predecessor,
 * `CpuTimeSection`) filled the same slot from `SystemHealthManager.takeUidSnapshot`, but
 * that needs `BATTERY_STATS` for any uid but this app's own -- grantable only by `adb
 * shell pm grant`, which this app has never declared and will never ask anyone to run --
 * so it was permanently stuck reading "Needs the one-time permission" on every real
 * install and was deleted along with the shell tier it depended on in spirit.
 *
 * [EstimatedDrainSection] is this app's actual answer to the same per-app question: a
 * per-app battery-drain *estimate*, apportioned from this app's own measured discharge by
 * how long each package held the foreground. `PACKAGE_USAGE_STATS` is appop-gated behind
 * an ordinary Settings toggle -- no adb, no root, no companion app -- and is the only
 * route left to per-app data at all.
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
        EstimatedDrainSection(state.estimatedDrainRows, onOpenUsageAccessSettings)
    }
}

/**
 * Per-app battery drain, *estimated* by apportioning this app's own measured discharge
 * across packages by how long each held the foreground -- the only per-app route this app
 * has, now that per-uid CPU time's privileged tier (`BATTERY_STATS`, unreachable on every
 * real install) has been deleted along with the section that rendered it. See
 * `EstimateWindow`, `EstimatedAppDrain` and `EstimatedDrainReading` (`data.repo`) for the
 * arithmetic and the absence rules this section's own `when` mirrors.
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
 * [EstimatedAppRow]'s own row rendering, carrying its own [SourceChip] labelled
 * "Estimated": an estimate must never borrow the same styling a measured figure would
 * use, so the mAh figure is rendered in the secondary text colour with a leading `~`,
 * never through [Value].
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
 * construction, one package usage stats reported foreground time for -- so this only ever
 * needs the resolved-icon-or-unidentified-glyph shape below, never a System/Shell
 * branch. */
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

/** [EstimatedAppRow] always has a real [EstimatedAppRow.packageName] to fall back to
 * (usage stats reports package names, never an unnamed uid), so the [AppLabel.Unknown]
 * case here shows the package name rather than a uid -- there is no uid in this row's own
 * data to name instead. */
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


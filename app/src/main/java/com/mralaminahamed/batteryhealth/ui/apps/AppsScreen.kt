package com.mralaminahamed.batteryhealth.ui.apps

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mralaminahamed.batteryhealth.data.apps.AppLabel
import com.mralaminahamed.batteryhealth.data.apps.AppRow
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.ui.components.OneUiCard
import com.mralaminahamed.batteryhealth.ui.components.ReadingSlot
import com.mralaminahamed.batteryhealth.ui.components.SectionHeader
import com.mralaminahamed.batteryhealth.ui.components.UnlockCard
import com.mralaminahamed.batteryhealth.ui.components.Value
import com.mralaminahamed.batteryhealth.ui.format.Formatters
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors

// See HealthScreen's own copy of this constant for why it points at general adb docs
// rather than a specific app.
private const val PRIVILEGED_TIER_INFO_URL = "https://developer.android.com/tools/adb#wireless"

object AppsScreenTags {
    const val ROOT = "apps-root"
    const val SKELETON = "apps-skeleton"
}

/**
 * The fourth navigation destination: per-uid battery power from `dumpsys batterystats
 * --checkin`, the second command the privileged gateway supports (see `data/privileged/`).
 *
 * Reuses [UnlockCard] rather than a second unlock affordance -- one entry point into the
 * privileged tier is enough, and this screen genuinely needs the same not-yet-ready
 * states the Health screen already explains. [AppsUiState.appPowerFailed] is this
 * screen's own failure signal, independent of Health's `privilegedDumpFailed`, so a
 * checkin-call failure here shows "read failed, retry" here and nowhere else -- see
 * `BatteryRepository.appPowerFailed`'s own doc for why the two are kept apart.
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
                currentViewModel.refreshPrivilegedTier()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppsContent(
        state = state,
        modifier = modifier,
        onRequestShizukuPermission = viewModel::connectPrivilegedTier,
        // See HealthScreen's own copy of this pairing for why both callbacks point at
        // the same explanation for now.
        onOpenShizuku = {
            context.startActivity(Intent(Intent.ACTION_VIEW, PRIVILEGED_TIER_INFO_URL.toUri()))
        },
        onLearnMoreAboutShizuku = {
            context.startActivity(Intent(Intent.ACTION_VIEW, PRIVILEGED_TIER_INFO_URL.toUri()))
        },
        onRetry = viewModel::retryPrivilegedDump,
    )
}

@Composable
fun AppsContent(
    state: AppsUiState,
    modifier: Modifier = Modifier,
    onRequestShizukuPermission: () -> Unit = {},
    onOpenShizuku: () -> Unit = {},
    onLearnMoreAboutShizuku: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val colors = LocalOneUiColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(AppsScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        UnlockCard(
            availability = state.privilegedAvailability,
            dumpFailed = state.appPowerFailed,
            onRequestPermission = onRequestShizukuPermission,
            onOpenShizuku = onOpenShizuku,
            onLearnMore = onLearnMoreAboutShizuku,
            onRetry = onRetry,
        )

        OneUiCard {
            SectionHeader("App power")
            Text(
                text = "Includes system processes and, on a device used for development, " +
                    "USB debugging (adb) -- not just apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            // A skeleton only ever replaces ReadingSlot's own rendering, never sits
            // alongside a real answer: once `rows` has genuinely gone Available at least
            // once, a later background refresh (every `ON_RESUME`, same as Health's own
            // privileged re-dump) keeps showing that last-known list quietly instead of
            // blanking back to a skeleton, and simply updates the numbers when the new
            // fetch resolves. Only the very first load -- bound, but nothing to show yet
            // -- has no real answer to fall back to, which is the one case this actually
            // needs to cover.
            if (state.isLoading && state.rows !is Reading.Available) {
                AppPowerSkeleton()
            } else {
                ReadingSlot(state.rows, modifier = Modifier.fillMaxWidth()) { rows, _ ->
                    if (rows.isEmpty()) {
                        Text(
                            text = "No power data recorded yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textSecondary,
                        )
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            rows.forEachIndexed { index, row ->
                                AppPowerRow(row, showDivider = index != rows.lastIndex)
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SKELETON_ROW_COUNT = 6

/**
 * Stands in for [AppPowerRow] while the very first checkin call is still in flight (see
 * [AppsContent]'s own doc for exactly when that is). Deliberately built from the same
 * icon-slot shape [AppRowIcon] already draws for a row with no real icon -- a 32dp
 * rounded square in [colors.divider][com.mralaminahamed.batteryhealth.ui.theme.OneUiColors.divider]
 * -- rather than a generic shimmer bar standing in for it, so the skeleton previews the
 * actual shape of what is about to appear instead of a shape unrelated to this screen.
 * The same pulsing alpha is shared across every bar in every row (one
 * [rememberInfiniteTransition] here, not one per row) so the whole card breathes as a
 * single unit rather than several independently-animated rows.
 */
@Composable
private fun AppPowerSkeleton() {
    val colors = LocalOneUiColors.current
    val transition = rememberInfiniteTransition(label = "app-power-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "app-power-skeleton-alpha",
    )

    Column(Modifier.fillMaxWidth().alpha(alpha).testTag(AppsScreenTags.SKELETON)) {
        repeat(SKELETON_ROW_COUNT) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.divider),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        SkeletonBar(width = 130.dp, height = 15.dp)
                        Spacer(Modifier.height(6.dp))
                        SkeletonBar(width = 80.dp, height = 11.dp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    SkeletonBar(width = 64.dp, height = 15.dp)
                    Spacer(Modifier.height(6.dp))
                    SkeletonBar(width = 36.dp, height = 11.dp)
                }
            }
            if (index != SKELETON_ROW_COUNT - 1) {
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

@Composable
private fun SkeletonBar(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(4.dp))
            .background(LocalOneUiColors.current.divider),
    )
}

@Composable
private fun AppPowerRow(row: AppRow, showDivider: Boolean) {
    val colors = LocalOneUiColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppRowIcon(row)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = primaryText(row),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textPrimary,
                    )
                    secondaryText(row)?.let { caption ->
                        Text(
                            text = caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Value(Formatters.milliampHours(row.mAh))
                Text(
                    text = Formatters.percentShare(row.sharePct),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider)
        }
    }
}

/**
 * A resolved app's real launcher icon where one was actually found; otherwise a small
 * generated glyph standing in for it -- never a generic "app-like" icon that could be
 * mistaken for a real one. The glyph itself still tells the truth about what is and is
 * not known: [UidKind.System] and [UidKind.Shell] rows get a fixed symbol for what they
 * categorically *are* (a gear, a shell prompt), never a letter that could look like it
 * came from a name, because neither row has anything resembling an app name to draw one
 * from; an [AppRow.App] row instead gets a monogram *derived from whatever identifier is
 * actually known for it* (the resolved label, or failing that the raw package name), the
 * same "first letter of the real thing" convention contacts-style avatars use elsewhere,
 * not an invented placeholder unrelated to this row.
 */
@Composable
private fun AppRowIcon(row: AppRow) {
    val colors = LocalOneUiColors.current
    val drawable = ((row as? AppRow.App)?.label as? AppLabel.Resolved)?.icon
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
        // System/Shell keep the neutral, existing divider tint -- they are categorically
        // not apps, so nothing here should look like an app icon. An App row (whatever
        // its label turned out to be) gets the accent tint instead, the same colour this
        // screen's own SectionHeader already uses -- a quiet visual echo of UidKind.App
        // that costs no new token.
        val isApp = row is AppRow.App
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isApp) colors.accent.copy(alpha = 0.18f) else colors.divider),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = placeholderGlyph(row),
                style = MaterialTheme.typography.labelSmall,
                color = if (isApp) colors.accent else colors.textSecondary,
            )
        }
    }
}

/** The fixed symbol for a non-app row, or a monogram derived from an app row's own known
 * identifier -- see [AppRowIcon]'s own doc for why these are not interchangeable. */
private fun placeholderGlyph(row: AppRow): String = when (row) {
    is AppRow.System -> "⚙" // gear
    is AppRow.Shell -> "$" // shell prompt
    is AppRow.App -> when (val label = row.label) {
        is AppLabel.Resolved -> label.label.monogram()
        is AppLabel.PackageNameOnly -> label.packageName.packageMonogram()
        AppLabel.Unknown -> "?"
    }
}

private fun String.monogram(): String =
    firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** Prefers a package name's second segment (conventionally the organisation, e.g. the
 * "instagram" in `com.instagram.android`) over its last, since Android's own
 * `com.example.android`/`com.example.app`-style trailing segments are far less
 * distinctive across unrelated packages than the organisation segment is. Still just a
 * glyph derived from the one real string this row has, not a lookup of anything else. */
private fun String.packageMonogram(): String {
    val segments = split('.')
    val candidate = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: segments.lastOrNull() ?: this
    return candidate.monogram()
}

/**
 * One text per [AppRow] kind, not a shared "label" field with a fallback chain -- see
 * `AppRow`'s own doc for why each kind is a distinct case in the first place. An
 * [AppRow.App] with [AppLabel.PackageNameOnly] renders the raw package name here (never a
 * placeholder that could be mistaken for a resolved name); see [secondaryText] for the
 * caption that keeps it from being mistaken for one.
 */
private fun primaryText(row: AppRow): String = when (row) {
    is AppRow.App -> when (val label = row.label) {
        is AppLabel.Resolved -> label.label
        is AppLabel.PackageNameOnly -> label.packageName
        AppLabel.Unknown -> "Uid ${row.uid}"
    }

    is AppRow.System -> "System (uid ${row.uid})"
    is AppRow.Shell -> "USB debugging (adb)"
}

/** `null` for a genuinely resolved app label -- nothing further needs saying. Every other
 * case gets an explicit caption stating exactly what is and is not known, rather than
 * leaving the honesty gap implicit in the primary text alone. */
private fun secondaryText(row: AppRow): String? = when (row) {
    is AppRow.App -> when (row.label) {
        is AppLabel.Resolved -> null
        is AppLabel.PackageNameOnly -> "Package name only, label unavailable"
        AppLabel.Unknown -> "No app name available"
    }

    is AppRow.System -> if (row.packageCount > 0) {
        "${row.packageCount} packages, not an app"
    } else {
        "System process, not an app"
    }

    is AppRow.Shell -> "Development / testing, not normal use"
}

package com.alaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageId
import com.alaminahamed.batteryhealth.ui.theme.LocalDesignLanguage
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

@Composable
fun OneUiCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val language = LocalDesignLanguage.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = language.spacing.cardOuterHorizontal,
                vertical = language.spacing.cardOuterVertical,
            )
            .clip(RoundedCornerShape(language.shapes.card))
            .background(language.colors.card)
            .padding(language.spacing.cardInner),
        content = content,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val language = LocalDesignLanguage.current
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        // One UI puts section headers in the accent colour; Expressive uses a neutral
        // label, so this reads from the bundle rather than always using the accent.
        color = when (language.id) {
            DesignLanguageId.OneUi -> language.colors.accent
            DesignLanguageId.Expressive -> language.colors.textSecondary
        },
        modifier = modifier.padding(bottom = language.spacing.sectionHeaderBottom),
    )
}

@Composable
fun KeyValueRow(
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    value: @Composable () -> Unit,
) {
    val language = LocalDesignLanguage.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = language.spacing.rowVertical),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = language.colors.textSecondary,
            )
            value()
        }
        if (showDivider) {
            HorizontalDivider(color = language.colors.divider)
        }
    }
}

/**
 * The right-hand value of a [KeyValueRow]. Lifted out of the individual screens once a
 * third one needed the same rendering: a titleMedium numeral in the primary text color.
 */
@Composable
fun Value(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalDesignLanguage.current.colors.textPrimary,
        modifier = modifier,
    )
}

@Composable
fun BigMetric(
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalDesignLanguage.current.spacing
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.padding(
                start = spacing.unitOffsetStart,
                bottom = spacing.unitOffsetBottom,
            ),
        )
    }
}

@Composable
fun ProgressTrack(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val language = LocalDesignLanguage.current
    val height = language.spacing.progressHeight
    val pill = RoundedCornerShape(language.shapes.pill)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(pill)
            .background(language.colors.divider),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(pill)
                .background(color),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTitleScaffold(
    title: String,
    bottomBar: @Composable () -> Unit,
    // Empty by default: most callers (every screen but the root nav host) have nothing
    // to put here. Threaded through to LargeTopAppBar's own `actions` slot rather than
    // this composable inventing a fixed one-icon shape, so the one caller that does need
    // an action (a Settings entry point, kept out of the bottom bar -- see
    // BatteryHealthApp's own doc) can supply it without a second top-bar composable.
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = LocalOneUiColors.current
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection),
        containerColor = colors.canvas,
        bottomBar = bottomBar,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Start,
                    )
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.canvas,
                    scrolledContainerColor = colors.canvas,
                ),
                scrollBehavior = behavior,
            )
        },
        content = content,
    )
}

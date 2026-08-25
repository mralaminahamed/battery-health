package com.alaminahamed.batteryhealth.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.alaminahamed.batteryhealth.domain.ChargeSession
import com.alaminahamed.batteryhealth.domain.HistoryRange
import com.alaminahamed.batteryhealth.domain.SessionType
import com.alaminahamed.batteryhealth.ui.charts.LevelHistoryChart
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object HistoryScreenTags {
    const val ROOT = "history-root"
    const val CHART = "history-chart"
}

@Composable
fun HistoryScreen(modifier: Modifier = Modifier, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    HistoryContent(state, viewModel::selectRange, modifier)
}

@Composable
fun HistoryContent(
    state: HistoryUiState,
    onRangeSelected: (HistoryRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalOneUiColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HistoryScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        OneUiCard {
            SectionHeader("Battery level")
            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryRange.entries.forEach { range ->
                    val selected = range == state.range
                    Text(
                        text = range.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) colors.card else colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) colors.accent else colors.divider)
                            .clickable { onRangeSelected(range) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            if (state.points.isEmpty()) {
                Text(
                    text = "No samples recorded yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            } else {
                LevelHistoryChart(
                    points = state.points,
                    lineColor = colors.accent,
                    fillColor = colors.accent.copy(alpha = 0.12f),
                    modifier = Modifier.testTag(HistoryScreenTags.CHART),
                )
            }
        }

        OneUiCard {
            SectionHeader("Sessions")
            if (state.sessions.isEmpty()) {
                Text(
                    text = "No completed sessions yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            } else {
                state.sessions.forEachIndexed { index, session ->
                    SessionRow(session, showDivider = index != state.sessions.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ChargeSession, showDivider: Boolean) {
    val colors = LocalOneUiColors.current
    val label = when (session.type) {
        SessionType.Charge -> "Charged ${session.startLevelPct}% to ${session.endLevelPct}%"
        SessionType.Discharge -> "Drained ${session.startLevelPct}% to ${session.endLevelPct}%"
    }
    KeyValueRow(label, showDivider = showDivider) {
        Column {
            Value(Formatters.duration(session.durationMs))
            session.avgMilliwatts?.let { milliwatts ->
                Text(
                    text = Formatters.watts(milliwatts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

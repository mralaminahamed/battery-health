package com.mralaminahamed.batteryhealth.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.mralaminahamed.batteryhealth.domain.CapacityMethod
import com.mralaminahamed.batteryhealth.domain.HealthBand
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.domain.valueOrNull
import com.mralaminahamed.batteryhealth.ui.components.BigMetric
import com.mralaminahamed.batteryhealth.ui.components.KeyValueRow
import com.mralaminahamed.batteryhealth.ui.components.OneUiCard
import com.mralaminahamed.batteryhealth.ui.components.ProgressTrack
import com.mralaminahamed.batteryhealth.ui.components.ReadingSlot
import com.mralaminahamed.batteryhealth.ui.components.SectionHeader
import com.mralaminahamed.batteryhealth.ui.format.Formatters
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors
import com.mralaminahamed.batteryhealth.data.repo.HealthEstimator

object HealthScreenTags {
    const val ROOT = "health-root"
}

@Composable
fun HealthScreen(modifier: Modifier = Modifier, viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    HealthContent(state, modifier)
}

@Composable
fun HealthContent(state: HealthUiState, modifier: Modifier = Modifier) {
    val colors = LocalOneUiColors.current
    val report = state.measured.valueOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(HealthScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        OneUiCard {
            SectionHeader("Battery health")
            ReadingSlot(state.headlinePct) { percent, source ->
                BigMetric(
                    value = percent.toString(),
                    unit = "%",
                    color = colors.forBand(HealthBand.of(percent)),
                )
                SourceChip(source)
            }
            if (state.measured is Reading.NotYetMeasured) {
                Text(
                    text = "Needs ${HealthEstimator.MIN_SESSIONS} full charge sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            if (report != null) {
                Text(
                    text = "${report.measuredFullMah} mAh of ${report.designCapacityMah} mAh",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
                ProgressTrack(
                    fraction = report.healthPct / 100f,
                    color = colors.forBand(report.band),
                    modifier = Modifier.padding(top = 13.dp),
                )
            }
        }

        OneUiCard {
            SectionHeader("Battery information")
            KeyValueRow("Cycles") {
                ReadingSlot(state.snapshot?.cycleCount ?: Reading.NotYetMeasured) { cycles, _ ->
                    Value(cycles.toString())
                }
            }
            KeyValueRow("First use") {
                ReadingSlot(
                    state.snapshot?.firstUsageDateEpochDay ?: Reading.NotYetMeasured
                ) { day, _ -> Value(Formatters.epochDay(day)) }
            }
            KeyValueRow("Manufactured") {
                ReadingSlot(
                    state.snapshot?.manufacturingDateEpochDay ?: Reading.NotYetMeasured
                ) { day, _ -> Value(Formatters.epochDay(day)) }
            }
            KeyValueRow("Technology", showDivider = report != null) {
                ReadingSlot(state.snapshot?.technology ?: Reading.NotYetMeasured) { tech, _ ->
                    Value(tech)
                }
            }
            if (report != null) {
                KeyValueRow("Measured from", showDivider = false) {
                    Value(
                        when (report.method) {
                            CapacityMethod.Counter -> "${report.sessionsUsed} sessions, charge counter"
                            CapacityMethod.Coulomb -> "${report.sessionsUsed} sessions, coulomb count"
                        }
                    )
                }
            }
        }

        OneUiCard {
            SectionHeader("Condition")
            KeyValueRow("Temperature") {
                ReadingSlot(
                    state.snapshot?.temperatureDeciC ?: Reading.NotYetMeasured
                ) { deciC, _ -> Value(Formatters.temperature(deciC)) }
            }
            KeyValueRow("Voltage", showDivider = false) {
                ReadingSlot(state.snapshot?.voltageMv ?: Reading.NotYetMeasured) { mv, _ ->
                    Value("$mv mV")
                }
            }
        }
    }
}

@Composable
private fun SourceChip(source: Source) {
    val colors = LocalOneUiColors.current
    Text(
        text = when (source) {
            Source.Framework -> "Reported"
            Source.Measured -> "Measured"
            Source.Privileged -> "ASOC"
        },
        style = MaterialTheme.typography.labelSmall,
        color = colors.accent,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.divider)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Value(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = LocalOneUiColors.current.textPrimary,
    )
}

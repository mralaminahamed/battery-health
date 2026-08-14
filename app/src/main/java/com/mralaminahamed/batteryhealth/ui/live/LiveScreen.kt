package com.mralaminahamed.batteryhealth.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.ChargeState
import com.mralaminahamed.batteryhealth.domain.PlugType
import com.mralaminahamed.batteryhealth.ui.components.BigMetric
import com.mralaminahamed.batteryhealth.ui.components.KeyValueRow
import com.mralaminahamed.batteryhealth.ui.components.OneUiCard
import com.mralaminahamed.batteryhealth.ui.components.ReadingSlot
import com.mralaminahamed.batteryhealth.ui.components.SectionHeader
import com.mralaminahamed.batteryhealth.ui.components.Value
import com.mralaminahamed.batteryhealth.ui.format.Formatters
import com.mralaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object LiveScreenTags {
    const val ROOT = "live-root"
}

@Composable
fun LiveScreen(modifier: Modifier = Modifier, viewModel: LiveViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsState()
    LiveContent(snapshot, modifier)
}

@Composable
fun LiveContent(snapshot: BatterySnapshot?, modifier: Modifier = Modifier) {
    val colors = LocalOneUiColors.current

    if (snapshot == null) {
        Text(
            text = "Reading battery…",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textSecondary,
            modifier = modifier.padding(24.dp),
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(LiveScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        OneUiCard {
            SectionHeader("Power now")
            ReadingSlot(snapshot.milliwatts) { milliwatts, _ ->
                BigMetric(
                    value = Formatters.wattsValue(milliwatts),
                    unit = "W",
                    color = colors.accent,
                )
            }
            ReadingSlot(snapshot.chargeState) { state, _ ->
                Text(
                    text = when (state) {
                        ChargeState.Charging -> "Charging"
                        ChargeState.Discharging -> "Discharging"
                        ChargeState.Full -> "Full"
                        ChargeState.NotCharging -> "Not charging"
                        ChargeState.Unknown -> "Unknown"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }
        }

        OneUiCard {
            SectionHeader("Instantaneous")
            KeyValueRow("Current") {
                ReadingSlot(snapshot.currentUa) { microAmps, _ ->
                    Value(Formatters.milliamps(microAmps))
                }
            }
            KeyValueRow("Voltage") {
                ReadingSlot(snapshot.voltageMv) { millivolts, _ -> Value("$millivolts mV") }
            }
            KeyValueRow("Temperature") {
                ReadingSlot(snapshot.temperatureDeciC) { deciC, _ ->
                    Value(Formatters.temperature(deciC))
                }
            }
            KeyValueRow("Charge counter", showDivider = false) {
                ReadingSlot(snapshot.chargeCounterUah) { uah, _ -> Value("${uah / 1000} mAh") }
            }
        }

        OneUiCard {
            SectionHeader("Charging")
            KeyValueRow("Level") {
                ReadingSlot(snapshot.levelPct) { level, _ -> Value("$level%") }
            }
            KeyValueRow("Source") {
                ReadingSlot(snapshot.plugType) { plug, _ ->
                    Value(
                        when (plug) {
                            PlugType.None -> "Battery"
                            PlugType.Ac -> "AC"
                            PlugType.Usb -> "USB"
                            PlugType.Wireless -> "Wireless"
                            PlugType.Dock -> "Dock"
                        }
                    )
                }
            }
            KeyValueRow("Time to full", showDivider = false) {
                ReadingSlot(snapshot.chargeTimeRemainingMs) { ms, _ ->
                    Value(Formatters.duration(ms))
                }
            }
        }
    }
}

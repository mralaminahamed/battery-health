package com.alaminahamed.batteryhealth.ui.health

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.domain.CapacityMethod
import com.alaminahamed.batteryhealth.domain.HealthBand
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.domain.valueOrNull
import com.alaminahamed.batteryhealth.ui.components.BigMetric
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.ProgressTrack
import com.alaminahamed.batteryhealth.ui.components.ReadingSlot
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors
import com.alaminahamed.batteryhealth.data.repo.HealthEstimator

object HealthScreenTags {
    const val ROOT = "health-root"

    /** The line under the headline explaining why there is no measured percentage yet. */
    const val MEASUREMENT_NOTE = "health-measurement-note"
}

object CycleBaselineTags {
    const val ROW = "cycle-baseline-row"
}

object DesignCapacityTags {
    const val ROW = "design-capacity-row"
}

@Composable
fun HealthScreen(modifier: Modifier = Modifier, viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    // A no-op on API < 33 (there is no such runtime permission to request) and a no-op
    // if the permission is already granted -- safe to launch unconditionally rather
    // than pre-checking the current grant state.
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    HealthContent(
        state = state,
        modifier = modifier,
        onRecorderEnabledChange = { enabled ->
            // The notification is the honest signal that measurement is running (Task
            // 12's persistent-service rationale rests on it being visible), so this is
            // asked for at the moment the user opts in, not silently skipped.
            //
            // Launched before setRecorderEnabled below, not after: the permission
            // request briefly puts a system dialog on top of this Activity while the
            // foreground-service start underneath it is issued from a now-covered, but
            // still resumed, Activity -- confirmed working on-device (API 37). Do not
            // "fix" this by reordering to request-then-start-after-the-callback: that
            // would leave the switch looking on with nothing actually started until
            // the user answers the dialog, and if they never answer (e.g. they leave
            // the app), the recorder would never start at all despite the flag already
            // reading true. The current order starts unconditionally and only the
            // notification's visibility depends on the answer, which self-heals on the
            // next launch via HealthViewModel's re-arm if the service is ever refused
            // for an unrelated reason in that same window.
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.setRecorderEnabled(enabled)
        },
    )
}

@Composable
fun HealthContent(
    state: HealthUiState,
    modifier: Modifier = Modifier,
    onRecorderEnabledChange: (Boolean) -> Unit = {},
) {
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
            // Exhaustive over MeasurementNote rather than keyed on `measured` alone: the
            // subtitle used to read "Needs N full charge sessions" whenever nothing had
            // been measured, including while the recorder switch further down this very
            // screen was off. That promised progress towards a number that could never
            // arrive. See HealthUiState.measurementNote.
            val measurementText = when (state.measurementNote) {
                MeasurementNote.NeedsSessions ->
                    "Needs ${HealthEstimator.MIN_SESSIONS} full charge sessions"

                MeasurementNote.NotRecording ->
                    "Turn on “Record charge sessions” below to start measuring"

                MeasurementNote.RecordingBlocked ->
                    "Recording is on but couldn’t start — battery saver can block it"

                MeasurementNote.None -> null
            }
            // A figure above 100% needs saying out loud, or it just looks broken. It is
            // usually not a remarkable battery -- vendors publish a rated and a typical
            // capacity a few per cent apart, and measuring a healthy cell against the
            // rated one lands here routinely. What it reliably means is that the design
            // capacity this app is comparing against is probably the wrong one for this
            // device. Before this, the number was silently clamped and the user had no
            // way to know.
            if (report?.exceedsDesign == true) {
                Text(
                    text = "Measured above the design capacity being compared against " +
                        "(${state.designCapacity.mah ?: 0} mAh). That usually means the " +
                        "design figure is wrong for this device rather than that the " +
                        "battery gained capacity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (measurementText != null) {
                Text(
                    text = measurementText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.testTag(HealthScreenTags.MEASUREMENT_NOTE),
                )
            }
            // The whole reason a fresh install on an unlisted device is a bad first-run
            // experience: with no design capacity known -- this device is absent from the
            // model table and either has no readable `power_profile.xml` or one this app
            // does not trust -- `measured` can only ever be Unsupported
            // (HealthEstimator.estimate's very first check), and there is no user-facing
            // way left to supply one: the override that used to live here is gone. That is
            // a real, permanent limit for a device in this state, stated plainly rather
            // than papered over.
            if (state.headlinePct !is Reading.Available && state.designCapacity.mah == null) {
                Text(
                    text = "No design capacity is known for this device -- it is not in " +
                        "the model table, and this device's own declaration could not be " +
                        "read or trusted. This app has no further way to supply one, so " +
                        "the measured health trend cannot be shown here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 3.dp),
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
            KeyValueRow("Cycles", modifier = Modifier.testTag(CycleBaselineTags.ROW)) {
                ReadingSlot(state.snapshot?.cycleCount ?: Reading.NotYetMeasured) { cycles, _ ->
                    Value(cycles.toString())
                }
            }
            // "Measuring" on its own says nothing about what would end the wait. The count
            // comes from charge sessions this app records, so with recording off it reads
            // that way forever -- the same defect the headline's own subtitle was fixed for.
            val cycleCaption = if (state.snapshot?.cycleCount is Reading.NotYetMeasured) {
                if (state.recorderEnabled) {
                    "Counting charge as it goes in — a figure appears after a full " +
                        "cycle's worth"
                } else {
                    "Turn on “Record charge sessions” below to start counting"
                }
            } else when ((state.snapshot?.cycleCount as? Reading.Available)?.source) {
                // The distinction that matters most about this number. The battery
                // broadcast's own figure counts from the day the battery was made; this
                // app's own count starts from when recording started, so a phone that is
                // already a year old starts from zero here. Without saying so, a low
                // number reads as a healthy battery rather than as a young measurement.
                Source.Measured ->
                    "Measured by this app, so it counts from when recording started — " +
                        "not the battery's whole life"
                Source.Framework, Source.Privileged, Source.Vendor, null -> null
            }
            if (cycleCaption != null) {
                Text(
                    text = cycleCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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

        OneUiCard {
            SectionHeader("Battery Protect")
            KeyValueRow("Status") {
                ReadingSlot(
                    state.snapshot?.protectBatteryModeEnabled ?: Reading.NotYetMeasured
                ) { enabled, _ -> Value(if (enabled) "On" else "Off") }
            }
            KeyValueRow("Charge limit", showDivider = false) {
                // The vendor's on/off key and its threshold key are read independently
                // (see VendorSettingsSource), so a device can report "off" while still
                // publishing a real, configured threshold -- Samsung keeps the cap even
                // while nothing is enforcing it. Rendering that number as today's "Charge
                // limit" would be a limit displayed while nothing is limiting, which is
                // its own false claim, not merely a stale one. Suppressed here, in the
                // presentation, rather than upstream in the Reading itself: the number is
                // genuinely known, just not in force right now, and Reading's absences
                // have no case for "known but not applicable". When the mode reading
                // itself is not Available, there is no positive signal it is off, so this
                // falls back to showing the raw value rather than guessing.
                val modeIsOff = state.snapshot?.protectBatteryModeEnabled?.valueOrNull() == false
                ReadingSlot(
                    state.snapshot?.protectionThresholdPct ?: Reading.NotYetMeasured
                ) { pct, _ -> Value(if (modeIsOff) "Not limiting" else "$pct%") }
            }
        }

        OneUiCard {
            SectionHeader("Measurement")
            val warning = when {
                state.recorderStartFailed -> "Couldn't start recording — reopen the app to try again"
                state.recorderEnabled && state.notificationsDenied ->
                    "Notifications are off, so you won't see when it's recording"
                else -> null
            }
            KeyValueRow("Record charge sessions", showDivider = true) {
                Switch(checked = state.recorderEnabled, onCheckedChange = onRecorderEnabledChange)
            }
            if (warning != null) {
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalOneUiColors.current.textSecondary,
                    modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
                )
            }
            KeyValueRow(
                "Design capacity",
                modifier = Modifier.testTag(DesignCapacityTags.ROW),
                showDivider = false,
            ) {
                Value(designCapacityValueText(state.designCapacity))
            }
        }
    }
}

/**
 * "None" is included in this `when` only so the compiler can enforce exhaustiveness if a
 * further source is ever added -- `EffectiveDesignCapacity.mah` is null only when `source`
 * is already `None`, so the early return above is what actually handles that case.
 * [DesignCapacitySource.Override] can no longer be produced by
 * [com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider.resolve] -- the
 * setting that produced it is gone -- but the branch stays for the same reason the enum
 * case itself does: a stale value written by an older install must still render something
 * sensible rather than crash an exhaustive `when`.
 */
private fun designCapacityValueText(info: EffectiveDesignCapacity): String {
    val mah = info.mah ?: return "Not set"
    return when (info.source) {
        DesignCapacitySource.Override -> "$mah mAh, your override"
        DesignCapacitySource.Table -> "$mah mAh, model table"
        // Named for where it came from, not dressed up as a measurement: this is the
        // figure the manufacturer wrote into the platform image, which is real device
        // data but still a declaration rather than something this app observed.
        DesignCapacitySource.PowerProfile -> "$mah mAh, reported by this device"
        DesignCapacitySource.None -> "Not set"
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
            // Named for who said it rather than how it was obtained. "Reported" would
            // blur it into an ordinary Android reading, and the distinction is real: this
            // is Samsung's own value, present only on Samsung devices.
            Source.Vendor -> "Vendor"
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

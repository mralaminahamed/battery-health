package com.alaminahamed.batteryhealth.ui.health

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alaminahamed.batteryhealth.data.settings.CycleBaselineValidation
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityValidation
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
import com.alaminahamed.batteryhealth.ui.components.UnlockCard
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.format.Formatters
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors
import com.alaminahamed.batteryhealth.data.repo.HealthEstimator

// General adb-over-wifi documentation, not a link to a specific app -- there is no
// separate companion app to point at any more, only a one-time host-side command.
private const val PRIVILEGED_TIER_INFO_URL = "https://developer.android.com/tools/adb#wireless"

object HealthScreenTags {
    const val ROOT = "health-root"

    /** The line under the headline explaining why there is no measured percentage yet. */
    const val MEASUREMENT_NOTE = "health-measurement-note"
}

object CycleBaselineTags {
    const val ROW = "cycle-baseline-row"
    const val DIALOG = "cycle-baseline-dialog"
    const val INPUT = "cycle-baseline-input"
    const val SAVE = "cycle-baseline-save"
    const val CLEAR = "cycle-baseline-clear"
    const val ERROR = "cycle-baseline-error"
}

object DesignCapacityTags {
    const val ROW = "design-capacity-row"
    const val DIALOG = "design-capacity-dialog"
    const val INPUT = "design-capacity-input"
    const val SAVE = "design-capacity-save"
    const val CLEAR = "design-capacity-clear"
    const val ERROR = "design-capacity-error"
}

@Composable
fun HealthScreen(modifier: Modifier = Modifier, viewModel: HealthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    // A no-op on API < 33 (there is no such runtime permission to request) and a no-op
    // if the permission is already granted -- safe to launch unconditionally rather
    // than pre-checking the current grant state.
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onNotificationPermissionResult(granted) }

    // Enabling wireless debugging, or granting root, both happen outside this app
    // entirely -- neither produces a broadcast or a callback this process would
    // otherwise see. Re-checking on every resume is what notices the user coming back
    // having done either, without needing this screen to be recreated.
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

    HealthContent(
        state = state,
        modifier = modifier,
        onConnect = viewModel::connectPrivilegedTier,
        onDismissUnlockCard = viewModel::dismissUnlockCard,
        onSetCycleBaseline = viewModel::setCycleBaseline,
        onLearnMore = {
            context.startActivity(Intent(Intent.ACTION_VIEW, PRIVILEGED_TIER_INFO_URL.toUri()))
        },
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
        onSaveDesignCapacity = viewModel::setDesignCapacityOverride,
        onClearDesignCapacity = viewModel::clearDesignCapacityOverride,
        onRetryPrivilegedDump = viewModel::retryPrivilegedDump,
    )
}

@Composable
fun HealthContent(
    state: HealthUiState,
    modifier: Modifier = Modifier,
    onRecorderEnabledChange: (Boolean) -> Unit = {},
    onSaveDesignCapacity: (Int) -> Unit = {},
    onClearDesignCapacity: () -> Unit = {},
    onConnect: () -> Unit = {},
    onLearnMore: () -> Unit = {},
    onRetryPrivilegedDump: () -> Unit = {},
    onDismissUnlockCard: () -> Unit = {},
    onSetCycleBaseline: (Int?) -> Unit = {},
) {
    val colors = LocalOneUiColors.current
    val report = state.measured.valueOrNull()
    var showDesignCapacityDialog by rememberSaveable { mutableStateOf(false) }
    var showCycleBaselineDialog by rememberSaveable { mutableStateOf(false) }

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
                    "Turn on \u201CRecord charge sessions\u201D below to start measuring"

                MeasurementNote.RecordingBlocked ->
                    "Recording is on but couldn\u2019t start \u2014 battery saver can block it"

                MeasurementNote.None -> null
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
            // experience: with no design capacity known, `measured` can only ever be
            // Unsupported (HealthEstimator.estimate's very first check), and `framework`
            // is Unsupported too until the privileged tier is connected -- so headlinePct
            // itself is not yet Available, and ReadingSlot's own "Not available on this
            // device" above says nothing about there being a fix. This is that fix,
            // stated plainly and without overselling it: setting a design capacity
            // unlocks only the measured trend (not BSOH, first-use date or Battery
            // Protect -- those genuinely need the privileged tier), and even then it
            // needs real charge sessions before it can say anything, same as the
            // NotYetMeasured case just above.
            if (state.headlinePct !is Reading.Available && state.designCapacity.mah == null) {
                Text(
                    text = "No design capacity is known for this device. Set one in " +
                        "Settings to start measuring health from your charge counter -- " +
                        "it still takes a few real charge sessions to produce a number, " +
                        "and it won't add BSOH, first-use date or Battery Protect; those " +
                        "need the privileged tier above.",
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

        UnlockCard(
            availability = state.privilegedAvailability,
            dumpFailed = state.privilegedDumpFailed,
            onConnect = onConnect,
            dismissed = state.unlockCardDismissed,
            permissionGranted = state.batteryStatsGranted,
            shellSupported = state.privilegedTierSupported,
            onDismiss = onDismissUnlockCard,
            onLearnMore = onLearnMore,
            onRetry = onRetryPrivilegedDump,
        )

        OneUiCard {
            SectionHeader("Battery information")
            // Tappable only where this app's own count is what is shown. A vendor figure
            // is already a lifetime total and a baseline would double-count the very
            // history it exists to stand in for, so offering the dialog there would invite
            // a user to break a correct number.
            val baselineEditable = state.privilegedTierSupported.not() ||
                (state.snapshot?.cycleCount as? Reading.Available)?.source != Source.Privileged
            KeyValueRow(
                "Cycles",
                modifier = if (baselineEditable) {
                    Modifier
                        .fillMaxWidth()
                        .clickable { showCycleBaselineDialog = true }
                        .testTag(CycleBaselineTags.ROW)
                } else {
                    Modifier
                },
            ) {
                ReadingSlot(state.snapshot?.cycleCount ?: Reading.NotYetMeasured) { cycles, _ ->
                    Value(cycles.toString())
                }
            }
            // Samsung's own accumulated figure counts every partial charge, not just a
            // full 0-100% discharge -- some users expect the latter from "cycle count",
            // so this line says so briefly rather than leaving the number to be
            // misread as unusually high. Shown only when it is actually that figure
            // (Source.Privileged): a plain framework EXTRA_CYCLE_COUNT reading, on the
            // rare device that reports one, has no such documented partial-cycle
            // behaviour to disclose.
            // "Measuring" on its own says nothing about what would end the wait. The count
            // comes from charge sessions this app records, so with recording off it reads
            // that way forever -- the same defect the headline's own subtitle was fixed for.
            val cycleCaption = if (state.snapshot?.cycleCount is Reading.NotYetMeasured) {
                if (state.recorderEnabled) {
                    "Counting charge as it goes in \u2014 a figure appears after a full " +
                        "cycle's worth"
                } else {
                    "Turn on \u201CRecord charge sessions\u201D below to start counting"
                }
            } else when ((state.snapshot?.cycleCount as? Reading.Available)?.source) {
                Source.Privileged -> "Counts every partial charge, not just full cycles"
                // The distinction that matters most about this number. Samsung's counts
                // from the day the battery was made; this one counts charge this app
                // actually watched go in, so a phone that is already a year old starts
                // from zero here. Without saying so, a low number reads as a healthy
                // battery rather than as a young measurement.
                Source.Measured ->
                    "Measured by this app, so it counts from when recording started \u2014 " +
                        "not the battery's whole life"
                Source.Framework, Source.Vendor, null -> null
            }
            if (cycleCaption != null) {
                Text(
                    text = cycleCaption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            // BSOH is Samsung's own second health figure, read only from `dumpsys`. A
            // build with no shell has no source for it at any price, and the headline
            // above already shows the platform's state of health -- so the row would be a
            // permanent "not available" sitting under a number that answers the same
            // question. Hidden rather than shown empty; `full` still has it.
            if (state.privilegedTierSupported) {
                KeyValueRow("BSOH") {
                    ReadingSlot(state.snapshot?.bsohPct ?: Reading.NotYetMeasured) { pct, _ ->
                        Value("$pct%")
                    }
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

        OneUiCard {
            SectionHeader("Battery Protect")
            KeyValueRow("Status") {
                ReadingSlot(
                    state.snapshot?.protectBatteryModeEnabled ?: Reading.NotYetMeasured
                ) { enabled, _ -> Value(if (enabled) "On" else "Off") }
            }
            KeyValueRow("Charge limit", showDivider = false) {
                // `mProtectBatteryMode`'s on/off collapses more than the two states the
                // fixture proves exist (see DumpsysBatteryParser's doc on why -- One UI's
                // Basic/Adaptive/Maximum modes aren't distinguishable from this one field
                // with the evidence this app was built against), but mode `0` is
                // unambiguously off, and `mProtectionThreshold` is still a real number in
                // that state -- Samsung keeps the configured cap even while nothing is
                // enforcing it. Rendering that number as today's "Charge limit" would be
                // a limit displayed while nothing is limiting, which is its own false
                // claim, not merely a stale one. Suppressed here, in the presentation,
                // rather than upstream in the Reading itself: the number is genuinely
                // known (a real dump returned it), just not in force right now, and
                // Reading's three absences have no case for "known but not applicable" --
                // forcing it into Unsupported or NeedsPrivilegedAccess would misstate *why* it is
                // absent. When the mode reading itself is not Available (rarer: the two
                // fields parse independently), there is no positive signal it is off, so
                // this falls back to showing the raw value rather than guessing.
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { showDesignCapacityDialog = true })
                    .testTag(DesignCapacityTags.ROW),
                showDivider = false,
            ) {
                Value(designCapacityValueText(state.designCapacity))
            }
        }
    }

    if (showCycleBaselineDialog) {
        CycleBaselineDialog(
            current = state.cycleBaseline,
            onSave = { cycles ->
                onSetCycleBaseline(cycles)
                showCycleBaselineDialog = false
            },
            onClear = {
                onSetCycleBaseline(null)
                showCycleBaselineDialog = false
            },
            onDismiss = { showCycleBaselineDialog = false },
        )
    }

    if (showDesignCapacityDialog) {
        DesignCapacityDialog(
            currentOverrideMah = when (state.designCapacity.source) {
                DesignCapacitySource.Override -> state.designCapacity.mah
                // Only the user's own override pre-fills the dialog. A table figure or the
                // device's own declaration is not the user's claim to edit, and offering
                // one as the starting value invites them to "confirm" a number they never
                // supplied, turning a derived figure into a stored override by accident.
                DesignCapacitySource.Table,
                DesignCapacitySource.PowerProfile,
                DesignCapacitySource.None,
                -> null
            },
            onSave = { mah ->
                onSaveDesignCapacity(mah)
                showDesignCapacityDialog = false
            },
            onClear = {
                onClearDesignCapacity()
                showDesignCapacityDialog = false
            },
            onDismiss = { showDesignCapacityDialog = false },
        )
    }
}

/**
 * "None" is included in this `when` only so the compiler can enforce exhaustiveness if a
 * further source is ever added -- `EffectiveDesignCapacity.mah` is null only when `source`
 * is already `None`, so the early return above is what actually handles that case.
 */
private fun designCapacityValueText(info: EffectiveDesignCapacity): String {
    val mah = info.mah ?: return "Not set — tap to add"
    return when (info.source) {
        DesignCapacitySource.Override -> "$mah mAh, your override"
        DesignCapacitySource.Table -> "$mah mAh, model table"
        // Named for where it came from, not dressed up as a measurement: this is the
        // figure the manufacturer wrote into the platform image, which is real device
        // data but still a declaration rather than something this app observed.
        DesignCapacitySource.PowerProfile -> "$mah mAh, reported by this device"
        DesignCapacitySource.None -> "Not set — tap to add"
    }
}

@Composable
private fun DesignCapacityDialog(
    currentOverrideMah: Int?,
    onSave: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Pre-filled only with the user's own existing override, never with the table value
    // or any other guess -- an empty field here means "nothing of the user's to edit",
    // not "here's a plausible starting point".
    var text by remember { mutableStateOf(currentOverrideMah?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(DesignCapacityTags.DIALOG),
        title = { Text("Design capacity") },
        text = {
            Column {
                Text(
                    text = "The battery's rated capacity when new, in mAh " +
                        "(${DesignCapacityValidation.MIN_MAH}–${DesignCapacityValidation.MAX_MAH}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("mAh") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(DesignCapacityTags.INPUT),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(DesignCapacityTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = DesignCapacityValidation.validate(text)) {
                        is DesignCapacityValidation.Result.Valid -> onSave(result.mah)
                        is DesignCapacityValidation.Result.Invalid -> error = result.message
                    }
                },
                modifier = Modifier.testTag(DesignCapacityTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (currentOverrideMah != null) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag(DesignCapacityTags.CLEAR),
                    ) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
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

/**
 * Lets the user supply a cycle count they read from their phone.
 *
 * The dialog names where to find it, because a user who cannot locate the number cannot
 * use the feature, and a vague "enter your cycle count" would invite a guess -- which is
 * the one thing this must not collect. A guessed baseline would be indistinguishable, in
 * the row above, from a measured one.
 */
@Composable
private fun CycleBaselineDialog(
    current: Int?,
    onSave: (Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(current?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(CycleBaselineTags.DIALOG),
        title = { Text("Cycle count so far") },
        text = {
            Column {
                Text(
                    text = "This app can only count charge it has watched go in, so on a " +
                        "phone that is not new its count starts at zero. If your phone " +
                        "reports a real figure \u2014 on Samsung: Settings, Battery, " +
                        "Battery information \u2014 enter it here and this app will count " +
                        "on from there.\n\nEnter only a number your phone actually shows. " +
                        "A guess here would look exactly like a measurement afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("Cycles") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(CycleBaselineTags.INPUT),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(CycleBaselineTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = CycleBaselineValidation.validate(text)) {
                        is CycleBaselineValidation.Result.Valid -> onSave(result.cycles)
                        is CycleBaselineValidation.Result.Invalid -> error = result.message
                    }
                },
                modifier = Modifier.testTag(CycleBaselineTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag(CycleBaselineTags.CLEAR),
                    ) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

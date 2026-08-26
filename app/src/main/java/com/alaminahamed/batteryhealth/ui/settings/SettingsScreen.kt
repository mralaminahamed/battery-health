package com.alaminahamed.batteryhealth.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.alaminahamed.batteryhealth.data.vendor.discovery.ProbeChannel
import com.alaminahamed.batteryhealth.data.vendor.discovery.ProbeOutcome
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.alaminahamed.batteryhealth.data.settings.AdbPortValidation
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityValidation
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.ui.components.KeyValueRow
import com.alaminahamed.batteryhealth.ui.components.OneUiCard
import com.alaminahamed.batteryhealth.ui.components.SectionHeader
import com.alaminahamed.batteryhealth.ui.components.Value
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

object DiagnosticsTags {
    const val RUN = "diagnostics-run"
    const val RESULTS = "diagnostics-results"
    const val EMPTY = "diagnostics-empty"
}

object SettingsScreenTags {
    const val ROOT = "settings-root"
}

object SettingsDesignCapacityTags {
    const val ROW = "settings-design-capacity-row"
    const val DIALOG = "settings-design-capacity-dialog"
    const val INPUT = "settings-design-capacity-input"
    const val SAVE = "settings-design-capacity-save"
    const val CLEAR = "settings-design-capacity-clear"
    const val ERROR = "settings-design-capacity-error"
}

object SettingsAdbPortTags {
    const val ROW = "settings-adb-port-row"
    const val DIALOG = "settings-adb-port-dialog"
    const val INPUT = "settings-adb-port-input"
    const val SAVE = "settings-adb-port-save"
    const val ERROR = "settings-adb-port-error"
}

/**
 * The fifth destination: where `SettingsStore.designCapacityOverrideMah` and
 * `SettingsStore.adbPort` are actually reachable from the UI. Both already had a
 * production write path before this screen existed -- `adbPort` had none at all, and
 * design capacity could only be set from a row buried in Health's "Measurement" card --
 * so this screen exists to make the one input the *unprivileged* health path actually
 * needs (design capacity) the discoverable, primary thing here, with the privileged
 * tier's own adb port kept visually secondary underneath it: that tier is optional and
 * this app's second-best experience, not the one this screen exists for.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onDesignLanguageChange: (DesignLanguageChoice) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        modifier = modifier,
        onSaveDesignCapacity = viewModel::setDesignCapacityOverride,
        onClearDesignCapacity = viewModel::clearDesignCapacityOverride,
        onSaveAdbPort = viewModel::setAdbPort,
        onDesignLanguageChange = onDesignLanguageChange,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onSaveDesignCapacity: (Int) -> Unit = {},
    onClearDesignCapacity: () -> Unit = {},
    onSaveAdbPort: (Int) -> Unit = {},
    onDesignLanguageChange: (DesignLanguageChoice) -> Unit = {},
) {
    val colors = LocalOneUiColors.current
    var showDesignCapacityDialog by rememberSaveable { mutableStateOf(false) }
    var showAdbPortDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SettingsScreenTags.ROOT)
            .verticalScroll(rememberScrollState()),
    ) {
        OneUiCard {
            SectionHeader("Appearance")
            // selectableGroup + selectable(role = RadioButton) rather than plain clickable
            // rows: these three are a single-choice group, not three unrelated buttons, and
            // without this a screen reader announces them as undifferentiated clickable rows
            // with no indication they are mutually exclusive or which one is selected.
            Column(modifier = Modifier.selectableGroup()) {
                DesignLanguageChoice.entries.forEachIndexed { index, choice ->
                    val selected = state.designLanguage == choice
                    KeyValueRow(
                        label = when (choice) {
                            DesignLanguageChoice.Auto -> "Match this device"
                            DesignLanguageChoice.Samsung -> "One UI"
                            DesignLanguageChoice.Material -> "Material"
                        },
                        showDivider = index != DesignLanguageChoice.entries.lastIndex,
                        modifier = Modifier
                            .selectable(
                                selected = selected,
                                onClick = { onDesignLanguageChange(choice) },
                                role = Role.RadioButton,
                            )
                            .testTag("design-language-${choice.name}"),
                    ) {
                        if (selected) Value("Selected")
                    }
                }
            }
        }

        DiagnosticsCard()

        OneUiCard {
            SectionHeader("Design capacity")
            Text(
                text = "The measured health trend on the Health screen compares your " +
                    "battery's charge counter against this number, in mAh. Most Samsung " +
                    "models are filled in automatically; on any other device, set it " +
                    "yourself from the battery's rated capacity to unlock that trend -- " +
                    "no privileged access needed for this part.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            KeyValueRow(
                "Design capacity",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { showDesignCapacityDialog = true })
                    .testTag(SettingsDesignCapacityTags.ROW),
                showDivider = false,
            ) {
                Value(designCapacityValueText(state.designCapacity))
            }
        }

        OneUiCard {
            SectionHeader("Privileged tier")
            Text(
                text = "Only needed if you connect the optional privileged tier from " +
                    "Health or Apps with \"adb tcpip\". This must match the port you ran " +
                    "that command with -- 5555, the default, is right for almost everyone.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            KeyValueRow(
                "ADB port",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { showAdbPortDialog = true })
                    .testTag(SettingsAdbPortTags.ROW),
                showDivider = false,
            ) {
                Value(state.adbPort.toString())
            }
        }
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

    if (showAdbPortDialog) {
        AdbPortDialog(
            currentPort = state.adbPort,
            onSave = { port ->
                onSaveAdbPort(port)
                showAdbPortDialog = false
            },
            onDismiss = { showAdbPortDialog = false },
        )
    }
}

/**
 * "None" is included in this `when` only so the compiler can enforce exhaustiveness if a
 * further source is ever added -- `EffectiveDesignCapacity.mah` is null only when `source`
 * is already `None`, so the early return above is what actually handles that case. Mirrors
 * `HealthScreen`'s own `designCapacityValueText` -- kept as a separate copy rather than a
 * shared import because each screen's row has a different surrounding voice ("model
 * table" reads fine standing alone here; Health's version sits next to a live reading).
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
        modifier = Modifier.testTag(SettingsDesignCapacityTags.DIALOG),
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
                        .testTag(SettingsDesignCapacityTags.INPUT),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(SettingsDesignCapacityTags.ERROR),
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
                modifier = Modifier.testTag(SettingsDesignCapacityTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (currentOverrideMah != null) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag(SettingsDesignCapacityTags.CLEAR),
                    ) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun AdbPortDialog(
    currentPort: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentPort.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SettingsAdbPortTags.DIALOG),
        title = { Text("ADB port") },
        text = {
            Column {
                Text(
                    text = "The TCP port \"adb tcpip\" printed when you ran it " +
                        "(${AdbPortValidation.MIN_PORT}–${AdbPortValidation.MAX_PORT}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(SettingsAdbPortTags.INPUT),
                )
                val currentError = error
                if (currentError != null) {
                    Text(
                        text = currentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(SettingsAdbPortTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = AdbPortValidation.validate(text)) {
                        is AdbPortValidation.Result.Valid -> onSave(result.port)
                        is AdbPortValidation.Result.Invalid -> error = result.message
                    }
                },
                modifier = Modifier.testTag(SettingsAdbPortTags.SAVE),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * What this device actually offered, channel by channel.
 *
 * The sweep is the only way to see what a given phone exposes, and until now it was
 * reachable only from an instrumented test -- which meant the app could not answer the
 * question on anyone's hardware but the developer's. Everything the app knows about
 * devices it was not built on has to start here.
 *
 * Deliberately behind a button rather than run on entry. It is cheap but not free, most
 * people opening Settings do not want it, and the result must describe the device now
 * rather than at process start: a permission can be granted between launches and a
 * platform flag can flip across an OS update.
 */
@Composable
private fun DiagnosticsCard(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val colors = LocalOneUiColors.current

    OneUiCard {
        SectionHeader("Device diagnostics")
        Text(
            text = "Asks this device for every battery value it might expose and reports " +
                "exactly what came back \u2014 including what it has but will not share. " +
                "Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Button(
            onClick = viewModel::run,
            enabled = !state.running,
            modifier = Modifier
                .padding(top = 10.dp)
                .testTag(DiagnosticsTags.RUN),
        ) { Text(if (state.running) "Reading\u2026" else "Run") }

        val report = state.report
        if (report != null) {
            state.identity?.let { identity ->
                KeyValueRow("Device") { Value("${identity.manufacturer} ${identity.model}") }
            }
            // Counted rather than just listed: "6 withheld" is the line that tells a user
            // there is something to unlock, and it is the single most actionable number
            // the sweep produces.
            KeyValueRow("Readable") { Value("${report.values.size}") }
            KeyValueRow("Withheld", showDivider = false) { Value("${report.denied.size}") }

            Column(modifier = Modifier.testTag(DiagnosticsTags.RESULTS)) {
                ProbeChannel.entries.forEach { channel ->
                    val rows = report.of(channel)
                    if (rows.isEmpty()) return@forEach
                    SectionHeader(channel.name)
                    rows.forEachIndexed { index, row ->
                        KeyValueRow(row.key, showDivider = index != rows.lastIndex) {
                            Value(describe(row.outcome))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders an outcome as something a person can act on.
 *
 * "Withheld" rather than the exception's name: a `SecurityException` is not an error the
 * user caused and framing it as one would be misleading. It is the platform confirming the
 * value exists, which is the one outcome worth telling them about.
 */
private fun describe(outcome: ProbeOutcome): String = when (outcome) {
    is ProbeOutcome.Value -> outcome.raw
    ProbeOutcome.Absent -> "not available"
    ProbeOutcome.Denied -> "withheld"
    is ProbeOutcome.Failed -> "failed"
}

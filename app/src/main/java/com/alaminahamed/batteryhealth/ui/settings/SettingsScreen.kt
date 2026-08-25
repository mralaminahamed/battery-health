package com.alaminahamed.batteryhealth.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.alaminahamed.batteryhealth.ui.theme.LocalOneUiColors

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
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        modifier = modifier,
        onSaveDesignCapacity = viewModel::setDesignCapacityOverride,
        onClearDesignCapacity = viewModel::clearDesignCapacityOverride,
        onSaveAdbPort = viewModel::setAdbPort,
    )
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onSaveDesignCapacity: (Int) -> Unit = {},
    onClearDesignCapacity: () -> Unit = {},
    onSaveAdbPort: (Int) -> Unit = {},
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
                DesignCapacitySource.Table, DesignCapacitySource.None -> null
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
 * fourth source is ever added -- `EffectiveDesignCapacity.mah` is null only when `source`
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

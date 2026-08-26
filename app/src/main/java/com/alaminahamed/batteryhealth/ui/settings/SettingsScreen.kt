package com.alaminahamed.batteryhealth.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alaminahamed.batteryhealth.data.settings.AdbPortValidation
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityValidation
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.ui.health.CycleBaselineDialog
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

object SettingsCycleBaselineTags {
    const val ROW = "settings-cycle-baseline-row"
}

object SettingsNotificationTags {
    const val ROW = "settings-notification-row"
    const val ACTION = "settings-notification-action"
}

object SettingsPrivilegedReadingsTags {
    const val ROW = "settings-privileged-readings-row"
    const val COMMAND = "settings-privileged-readings-command"
    const val RESTORE = "settings-restore-unlock-card"
}

object SettingsAdbPortTags {
    const val ROW = "settings-adb-port-row"
    const val DIALOG = "settings-adb-port-dialog"
    const val INPUT = "settings-adb-port-input"
    const val SAVE = "settings-adb-port-save"
    const val ERROR = "settings-adb-port-error"
}

/**
 * Row and action tags are per-permission rather than fixed constants: this section has as
 * many rows as [SettingsUiState.permissions] does, and a test targeting "the BATTERY_STATS
 * row" needs a tag that says which one, the same reason `DiagnosticsCard`'s per-channel
 * rows are found by text rather than a fixed tag.
 */
object SettingsPermissionsTags {
    const val SECTION = "settings-permissions-section"
    fun row(shortName: String) = "settings-permissions-row-$shortName"
    fun action(shortName: String) = "settings-permissions-action-$shortName"
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
    diagnosticsViewModel: DiagnosticsViewModel = hiltViewModel(),
    onDesignLanguageChange: (DesignLanguageChoice) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val diagnostics by diagnosticsViewModel.state.collectAsState()
    val context = LocalContext.current

    // A no-op if already granted, and a no-op below API 33 where there is no such runtime
    // permission -- safe to launch unconditionally rather than pre-checking. Refreshed
    // explicitly in the callback rather than waiting on the resume observer below: the
    // system dialog's own show/dismiss does cycle this Activity through pause/resume (the
    // same behaviour HealthScreen's equivalent launcher relies on), but reading the new
    // state the moment the user answers is one call and removes any dependency on that
    // ordering holding on every OEM build.
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshPermissions() }

    // Both permissions this screen reports are granted from outside the app -- one over
    // adb from a computer, one from the system's own notification settings -- and
    // neither tells this process when it happens. Re-reading on resume is what notices
    // the user coming back having changed either, without needing the screen recreated.
    val currentViewModel by rememberUpdatedState(viewModel)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsContent(
        state = state,
        modifier = modifier,
        onSaveDesignCapacity = viewModel::setDesignCapacityOverride,
        onClearDesignCapacity = viewModel::clearDesignCapacityOverride,
        onSaveAdbPort = viewModel::setAdbPort,
        onDesignLanguageChange = onDesignLanguageChange,
        onSetCycleBaseline = viewModel::setCycleBaseline,
        onRestoreUnlockCard = viewModel::restoreUnlockCard,
        onOpenNotificationSettings = { context.startActivity(notificationSettingsIntent(context)) },
        onRequestNotificationPermission = {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        },
        onOpenUsageAccessSettings = { openUsageAccessSettings(context) },
        diagnostics = diagnostics,
        onRunDiagnostics = diagnosticsViewModel::run,
    )
}

/**
 * Deep-links to this app's own notification settings.
 *
 * The reason this exists at all: once the user denies the runtime permission twice,
 * Android stops showing the dialog entirely and `launch()` becomes a silent no-op. From
 * that point the in-app request can never succeed again, and without a route to the
 * system screen the app would be telling the user notifications are off while offering
 * nothing that could turn them on.
 *
 * `ACTION_APP_NOTIFICATION_SETTINGS` needs API 26, which is this app's minimum, so there
 * is no older branch to carry.
 */
private fun notificationSettingsIntent(context: Context) =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Deep-links to the system's Usage access list, the only route to `PACKAGE_USAGE_STATS`:
 * it is appop-gated with no runtime dialog of its own, so this and a manual toggle there
 * are the entire action.
 *
 * `resolveActivity` is checked first because some OEM builds ship no activity for
 * `ACTION_USAGE_ACCESS_SETTINGS` at all -- `startActivity` on an unresolvable intent throws
 * `ActivityNotFoundException`, and there is no fallback screen worth sending the user to
 * instead, so this silently does nothing rather than crash the settings screen over a
 * deep link with no working destination.
 */
private fun openUsageAccessSettings(context: Context) {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

@Composable
fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onSaveDesignCapacity: (Int) -> Unit = {},
    onClearDesignCapacity: () -> Unit = {},
    onSaveAdbPort: (Int) -> Unit = {},
    onDesignLanguageChange: (DesignLanguageChoice) -> Unit = {},
    onSetCycleBaseline: (Int?) -> Unit = {},
    onRestoreUnlockCard: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onRequestNotificationPermission: () -> Unit = {},
    onOpenUsageAccessSettings: () -> Unit = {},
    /**
     * Diagnostics state and its trigger arrive as parameters rather than being pulled
     * from a `hiltViewModel()` inside this composable. `SettingsContent` is exercised
     * directly by `SettingsScreenTest` with a plain Compose rule and no Hilt harness --
     * see this file's own doc -- and a `hiltViewModel()` call anywhere inside it breaks
     * every one of those tests at once, which is exactly what happened when the
     * diagnostics card was first added.
     */
    diagnostics: DiagnosticsUiState = DiagnosticsUiState(),
    onRunDiagnostics: () -> Unit = {},
) {
    val colors = LocalOneUiColors.current
    var showDesignCapacityDialog by rememberSaveable { mutableStateOf(false) }
    var showAdbPortDialog by rememberSaveable { mutableStateOf(false) }
    var showCycleBaselineDialog by rememberSaveable { mutableStateOf(false) }

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

        DiagnosticsCard(diagnostics, onRunDiagnostics)

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
            SectionHeader("Cycle count")
            Text(
                text = "This app can only count charge it has watched go in, so on a phone " +
                    "that is not new its own count starts at zero. If your phone reports a " +
                    "real figure, enter it here and counting continues from there instead " +
                    "of from nothing.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            KeyValueRow(
                "Cycles so far",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { showCycleBaselineDialog = true })
                    .testTag(SettingsCycleBaselineTags.ROW),
                showDivider = false,
            ) {
                // "Not set" rather than "0": a supplied zero is a real claim about a new
                // battery, and CycleCountResolver acts on it as one. Showing the absence
                // of a claim as zero would make the two indistinguishable here.
                Value(state.cycleBaseline?.toString() ?: "Not set")
            }
        }

        OneUiCard {
            SectionHeader("Notifications")
            Text(
                text = if (state.notificationsGranted) {
                    "Recording shows an ongoing notification while it runs. That notification " +
                        "is what keeps measurement alive in the background, and it is the " +
                        "honest signal that this app is doing something."
                } else {
                    "Notifications are off, so recording runs without telling you it is " +
                        "running. Android stops showing its own permission prompt after two " +
                        "refusals, so this has to be turned back on from system settings."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            KeyValueRow(
                "Notifications",
                modifier = Modifier.testTag(SettingsNotificationTags.ROW),
                showDivider = !state.notificationsGranted,
            ) {
                Value(if (state.notificationsGranted) "Allowed" else "Blocked")
            }
            // Offered only where it would change something. A button to "open settings"
            // on an already-granted permission is a button that leads somewhere with
            // nothing to do.
            if (!state.notificationsGranted) {
                Button(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .testTag(SettingsNotificationTags.ACTION),
                ) {
                    Text("Open notification settings")
                }
            }
        }

        OneUiCard {
            SectionHeader("Privileged readings")
            Text(
                text = if (state.batteryStatsGranted) {
                    "State of health, first-use date and manufacturing date are unlocked on " +
                        "this device. The grant survives restarts and app updates; " +
                        "uninstalling drops it."
                } else {
                    "State of health, first-use date and manufacturing date sit behind a " +
                        "permission no app can request from you -- Android offers no dialog " +
                        "for it. From a computer with this device connected, run this once:"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (!state.batteryStatsGranted) {
                Text(
                    text = "adb shell pm grant com.alaminahamed.batteryhealth " +
                        "android.permission.BATTERY_STATS",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(bottom = 10.dp)
                        .testTag(SettingsPrivilegedReadingsTags.COMMAND),
                )
            }
            KeyValueRow(
                "BATTERY_STATS",
                modifier = Modifier.testTag(SettingsPrivilegedReadingsTags.ROW),
                showDivider = state.unlockCardDismissed,
            ) {
                Value(if (state.batteryStatsGranted) "Granted" else "Not granted")
            }
            // Shown only once there is something to restore. This is the only way back:
            // the control that sets the flag lives on the card being dismissed, so
            // without this the dismissal is permanent short of a reinstall, which costs
            // every recorded session.
            if (state.unlockCardDismissed) {
                KeyValueRow(
                    "Unlock card",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRestoreUnlockCard)
                        .testTag(SettingsPrivilegedReadingsTags.RESTORE),
                    showDivider = false,
                ) {
                    Value("Show again")
                }
            }
        }

        // Hidden where no transport exists. The Play build compiles none in, so this card
        // offered a port for a connection that build cannot make, describing a command
        // ("adb tcpip") that would achieve nothing on it. A setting that cannot affect
        // anything is worse than a missing one: it invites the user to go and try.
        if (state.privilegedTierSupported) OneUiCard {
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

        // Empty only before SettingsViewModel's first real read (see
        // SettingsUiState.permissions's own doc) -- there is nothing honest to show yet,
        // so the card itself stays off rather than rendering an empty shell.
        if (state.permissions.isNotEmpty()) {
            OneUiCard(modifier = Modifier.testTag(SettingsPermissionsTags.SECTION)) {
                SectionHeader("Permissions")
                Text(
                    text = "Every permission this app declares, and the one thing that " +
                        "actually moves each one forward. Six of these cannot be granted " +
                        "from inside the app at all -- they are said here rather than " +
                        "left silent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                state.permissions.forEachIndexed { index, row ->
                    KeyValueRow(
                        row.shortName,
                        showDivider = index != state.permissions.lastIndex,
                        modifier = Modifier.testTag(SettingsPermissionsTags.row(row.shortName)),
                    ) {
                        Value(permissionStateLabel(row))
                    }
                    // Offered only where it would change something -- the same rule the
                    // Notifications and Privileged readings cards above already follow: a
                    // control that leads somewhere with nothing to do is worse than no
                    // control.
                    when (row.kind) {
                        PermissionKind.Requestable -> if (!row.held) {
                            Button(
                                onClick = onRequestNotificationPermission,
                                modifier = Modifier
                                    .padding(top = 6.dp, bottom = 10.dp)
                                    .testTag(SettingsPermissionsTags.action(row.shortName)),
                            ) { Text("Request permission") }
                        }
                        PermissionKind.AppOp -> if (!row.held) {
                            Button(
                                onClick = onOpenUsageAccessSettings,
                                modifier = Modifier
                                    .padding(top = 6.dp, bottom = 10.dp)
                                    .testTag(SettingsPermissionsTags.action(row.shortName)),
                            ) { Text("Open Usage access settings") }
                        }
                        PermissionKind.AdbGrant -> if (!row.held) {
                            Text(
                                text = row.adbCommand.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .padding(top = 2.dp, bottom = 10.dp)
                                    .testTag(SettingsPermissionsTags.action(row.shortName)),
                            )
                        }
                        // Always shown, not just when !row.held: it is true regardless of
                        // this row's state, and saying so plainly is the whole point of
                        // this row existing rather than a "Grant" button that could never
                        // do anything.
                        PermissionKind.InstallTime -> Text(
                            text = "No action needed — granted automatically at install.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .padding(top = 2.dp, bottom = 10.dp)
                                .testTag(SettingsPermissionsTags.action(row.shortName)),
                        )
                    }
                }
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

    if (showCycleBaselineDialog) {
        // The same dialog Health opens, not a second one worded differently -- see
        // CycleBaselineDialog's own doc.
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
private fun DiagnosticsCard(state: DiagnosticsUiState, onRun: () -> Unit) {
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
            onClick = onRun,
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

package com.alaminahamed.batteryhealth.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import com.alaminahamed.batteryhealth.data.vendor.discovery.ProbeChannel
import com.alaminahamed.batteryhealth.data.vendor.discovery.ProbeOutcome
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

object SettingsNotificationTags {
    const val ROW = "settings-notification-row"
    const val ACTION = "settings-notification-action"
}

/**
 * Row and action tags are per-permission rather than fixed constants: this section has as
 * many rows as [SettingsUiState.permissions] does, and a test targeting "the
 * PACKAGE_USAGE_STATS row" needs a tag that says which one, the same reason
 * `DiagnosticsCard`'s per-channel rows are found by text rather than a fixed tag.
 */
object SettingsPermissionsTags {
    const val SECTION = "settings-permissions-section"
    fun row(shortName: String) = "settings-permissions-row-$shortName"
    fun action(shortName: String) = "settings-permissions-action-$shortName"
}

/**
 * The fifth destination. Two of the settings that used to live here -- the design-capacity
 * override and a cycle-count starting figure the user typed in -- are gone: the owner's
 * decision for this app is that it asks for nothing by typing a number, only for
 * permissions granted the normal Android way. What is left is read-only device state
 * (design capacity, as resolved from the model table or this device's own declaration),
 * appearance, diagnostics, notifications and the Permissions section.
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

    // PACKAGE_USAGE_STATS is granted from the system's own Usage access screen, and
    // notifications from the system's notification settings -- neither tells this
    // process when it happens. Re-reading on resume is what notices the user coming back
    // having changed either, without needing the screen recreated.
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
        onDesignLanguageChange = onDesignLanguageChange,
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

// openUsageAccessSettings now lives in UsageAccessDeepLink.kt (same package), shared with
// AppsScreen's own estimate disclosure card -- see that file's doc.

@Composable
fun SettingsContent(
    state: SettingsUiState,
    modifier: Modifier = Modifier,
    onDesignLanguageChange: (DesignLanguageChoice) -> Unit = {},
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

        // Empty only before SettingsViewModel's first real read (see
        // SettingsUiState.permissions's own doc) -- there is nothing honest to show yet,
        // so the card itself stays off rather than rendering an empty shell.
        if (state.permissions.isNotEmpty()) {
            OneUiCard(modifier = Modifier.testTag(SettingsPermissionsTags.SECTION)) {
                SectionHeader("Permissions")
                Text(
                    text = "Every permission this app declares, and the one thing that " +
                        "actually moves each one forward. Every one of these can be " +
                        "answered from inside this app or from Settings -- nothing here " +
                        "ever needs a computer.",
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
                    // Notifications card above already follows: a control that leads
                    // somewhere with nothing to do is worse than no control.
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
                "exactly what came back — including what it has but will not share. " +
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
        ) { Text(if (state.running) "Reading…" else "Run") }

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

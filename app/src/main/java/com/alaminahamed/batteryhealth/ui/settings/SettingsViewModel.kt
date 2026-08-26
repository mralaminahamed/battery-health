package com.alaminahamed.batteryhealth.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.data.settings.UsageAccessState
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    @param:Named("queryAllPackagesDeclared") private val queryAllPackagesDeclared: Boolean,
    @param:ApplicationContext private val context: Context,
    private val usageAccess: UsageAccessState,
) : ViewModel() {

    /**
     * Permission state, which is not a flow because nothing emits when it changes.
     *
     * `PACKAGE_USAGE_STATS` is granted from the system's own Usage access screen and
     * notifications from the system settings screen -- neither produces a broadcast or
     * callback this process would otherwise see. [refresh] is called from the screen on
     * every resume, which is what notices the user coming back having changed either.
     */
    private val permissions = MutableStateFlow(readPermissions())

    val state: StateFlow<SettingsUiState> = combine(
        settings.designLanguageChoice,
        permissions,
    ) { language, perms ->
        SettingsUiState(
            designLanguage = language,
            notificationsGranted = perms.notificationsGranted,
            permissions = perms.rows,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * Applies immediately: `MainActivity` collects the same flow, so the whole app
     * recomposes into the chosen language without a restart. That instant feedback is what
     * makes the setting useful for checking both languages on one device.
     */
    fun setDesignLanguage(choice: DesignLanguageChoice) {
        viewModelScope.launch { settings.setDesignLanguageChoice(choice) }
    }

    /** See [permissions]. Called from the screen on every resume. */
    fun refreshPermissions() {
        permissions.value = readPermissions()
    }

    private fun readPermissions(): PermissionState {
        // Below API 33 there is no runtime notification permission and posting is
        // unconditional, so reporting "granted" is the accurate answer, not a fallback.
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return PermissionState(
            notificationsGranted = notificationsGranted,
            rows = PermissionCatalog.rows(
                notificationsGranted = notificationsGranted,
                usageAccessHeld = usageAccess.isHeld(),
                installTimeGranted = installTimePermissions(),
            ),
        )
    }

    /**
     * Every permission this app declares that the platform grants outright at install,
     * keyed by [PermissionRow.shortName] in the order they should render. `QUERY_ALL_PACKAGES`
     * is appended only when [queryAllPackagesDeclared] is true -- that flag tracks exactly
     * the `full`/`play` split this one permission follows (see `AppsModule` in each
     * flavour source set and `app/src/full/AndroidManifest.xml`'s own doc on why `play`
     * must never declare it), so reusing it here means this list needs no flavour source
     * set of its own.
     *
     * Read with [isGranted] rather than assumed true: every one of these is normal or
     * signature-consistent protection and will read granted on any device that installed
     * successfully, but asking the platform is one call and keeps this section's promise
     * that nothing here is a claim the app did not check.
     */
    private fun installTimePermissions(): Map<String, Boolean> = buildMap {
        put("FOREGROUND_SERVICE", isGranted(Manifest.permission.FOREGROUND_SERVICE))
        put("FOREGROUND_SERVICE_SPECIAL_USE", isGranted(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE))
        put("RECEIVE_BOOT_COMPLETED", isGranted(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        put("ACCESS_NETWORK_STATE", isGranted(Manifest.permission.ACCESS_NETWORK_STATE))
        put("WAKE_LOCK", isGranted(Manifest.permission.WAKE_LOCK))
        if (queryAllPackagesDeclared) {
            put("QUERY_ALL_PACKAGES", isGranted(Manifest.permission.QUERY_ALL_PACKAGES))
        }
    }

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private data class PermissionState(
        val notificationsGranted: Boolean,
        val rows: List<PermissionRow>,
    )
}

package com.alaminahamed.batteryhealth.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.framework.GrantedReadings
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
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
    @param:Named("privilegedTierSupported") private val privilegedTierSupported: Boolean,
    @param:ApplicationContext private val context: Context,
    private val granted: GrantedReadings,
    designCapacity: DesignCapacityProvider,
) : ViewModel() {

    /**
     * Permission state, which is not a flow because nothing emits when it changes.
     *
     * Both permissions here are granted from outside this app -- `BATTERY_STATS` from a
     * computer over adb, notifications from the system settings screen -- and neither
     * produces a broadcast or callback this process would otherwise see. [refresh] is
     * called from the screen on every resume, which is what notices the user coming back
     * having changed either. The same reasoning, and the same fix, as
     * `HealthScreen`'s `refreshPrivilegedTier()`.
     */
    private val permissions = MutableStateFlow(readPermissions())

    val state: StateFlow<SettingsUiState> = combine(
        designCapacity.effective,
        settings.adbPort,
        settings.designLanguageChoice,
        settings.cycleCountBaseline,
        settings.unlockCardDismissed,
    ) { capacity, port, language, baseline, dismissed ->
        SettingsUiState(
            designCapacity = capacity,
            adbPort = port,
            designLanguage = language,
            cycleBaseline = baseline,
            unlockCardDismissed = dismissed,
            privilegedTierSupported = privilegedTierSupported,
        )
    }.combine(permissions) { base, perms ->
        base.copy(
            batteryStatsGranted = perms.batteryStatsGranted,
            notificationsGranted = perms.notificationsGranted,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * `mah` has already passed `DesignCapacityValidation` in the dialog -- this is a
     * plain write, not a second place that could reject it differently. Mirrors
     * `HealthViewModel.setDesignCapacityOverride`; both write through the same
     * `SettingsStore`, so either screen's edit is visible from the other immediately.
     */
    fun setDesignCapacityOverride(mah: Int) {
        viewModelScope.launch { settings.setDesignCapacityOverride(mah) }
    }

    /** Removes the override; `DesignCapacityProvider` falls back to the model table. */
    fun clearDesignCapacityOverride() {
        viewModelScope.launch { settings.setDesignCapacityOverride(null) }
    }

    /**
     * `port` has already passed `AdbPortValidation` in the dialog. `SettingsStore.setAdbPort`
     * also refuses an out-of-range port on its own (defensively, since this is the only
     * production caller so far), but the validated dialog is what gives the user a visible
     * reason rather than a write that silently didn't happen.
     */
    fun setAdbPort(port: Int) {
        viewModelScope.launch { settings.setAdbPort(port) }
    }

    /**
     * Applies immediately: `MainActivity` collects the same flow, so the whole app
     * recomposes into the chosen language without a restart. That instant feedback is what
     * makes the setting useful for checking both languages on one device.
     */
    fun setDesignLanguage(choice: DesignLanguageChoice) {
        viewModelScope.launch { settings.setDesignLanguageChoice(choice) }
    }

    /**
     * Mirrors `HealthViewModel.setCycleBaseline`; both write through the same
     * `SettingsStore`, so an edit on either screen is visible from the other at once.
     *
     * Null clears the baseline rather than storing zero. `CycleCountResolver` treats a
     * supplied zero as a real claim -- a battery genuinely at zero cycles -- so writing
     * one to mean "never mind" would silently assert something the user did not say.
     */
    fun setCycleBaseline(cycles: Int?) {
        viewModelScope.launch { settings.setCycleCountBaseline(cycles) }
    }

    /**
     * Brings back the unlock card the user dismissed from Health or Apps.
     *
     * Dismissal was otherwise irreversible, which is the sort of thing nobody notices
     * until they want it back: the only control that sets the flag lives on the card
     * itself, so once it is hidden there is nothing left to press. A reinstall was the
     * only route, and that costs every recorded session.
     */
    fun restoreUnlockCard() {
        viewModelScope.launch { settings.setUnlockCardDismissed(false) }
    }

    /** See [permissions]. Called from the screen on every resume. */
    fun refreshPermissions() {
        permissions.value = readPermissions()
    }

    private fun readPermissions() = PermissionState(
        batteryStatsGranted = granted.isGranted,
        // Below API 33 there is no runtime notification permission and posting is
        // unconditional, so reporting "granted" is the accurate answer, not a fallback.
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        },
    )

    private data class PermissionState(
        val batteryStatsGranted: Boolean,
        val notificationsGranted: Boolean,
    )
}

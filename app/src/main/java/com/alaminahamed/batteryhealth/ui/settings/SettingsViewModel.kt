package com.alaminahamed.batteryhealth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import dagger.hilt.android.lifecycle.HiltViewModel
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
    designCapacity: DesignCapacityProvider,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        designCapacity.effective,
        settings.adbPort,
        settings.designLanguageChoice,
    ) { capacity, port, language ->
        SettingsUiState(
            designCapacity = capacity,
            adbPort = port,
            designLanguage = language,
            privilegedTierSupported = privilegedTierSupported,
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
}

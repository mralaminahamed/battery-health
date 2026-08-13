package com.mralaminahamed.batteryhealth.ui.health

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mralaminahamed.batteryhealth.data.repo.BatteryRepository
import com.mralaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.sampling.ChargeRecorderService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HealthViewModel @Inject constructor(
    repository: BatteryRepository,
    private val settings: SettingsStore,
    designCapacity: DesignCapacityProvider,
    // @ApplicationContext, explicitly: lint's StaticFieldLeak check flags any bare
    // Context field on a long-lived class like a ViewModel, since it can't tell
    // whether an injected Context is Activity- or Application-scoped just from the
    // type. This qualifier is both correct (AppModule binds it to the application
    // context, which genuinely does outlive this ViewModel with no leak) and what the
    // check specifically recognises as safe.
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Ephemeral, not persisted: these describe "what just happened when the user acted
    // on this screen", not a durable setting. recorderStartFailed in particular must
    // stay separate from recorderEnabled -- the flag records intent (Task 6), not
    // whether the service is actually running, so a refusal has to be its own signal or
    // it disappears into `Log.w` with nothing visible in the UI.
    private val recorderStartFailed = MutableStateFlow(false)
    private val notificationsDenied = MutableStateFlow(false)

    val state: StateFlow<HealthUiState> =
        combine(
            repository.snapshots(),
            repository.measuredHealth(),
            settings.recorderEnabled,
            recorderStartFailed,
            notificationsDenied,
        ) { snapshot, measured, enabled, startFailed, notifDenied ->
            // combine() has no six-flow overload, so the design-capacity flow is joined
            // separately below rather than folded into this one -- not because it's less
            // important, only because the five-arg form is what the library provides.
            HealthUiState(snapshot, measured, enabled, startFailed, notifDenied)
        }.combine(designCapacity.effective) { partial, capacity ->
            partial.copy(designCapacity = capacity)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HealthUiState(snapshot = null, measured = Reading.NotYetMeasured),
        )

    init {
        // Re-arms after a low-memory kill or an app update -- neither stops the app, so
        // nothing else notices and restarts the service: the setting would otherwise
        // keep reading on while the recorder is silently dead. This runs once per
        // ViewModel instance, tied to the Health screen being the app's start
        // destination, so it fires from a context the start is unambiguously permitted
        // in. ChargeRecorderService.start is idempotent against an already-running
        // instance (a harmless extra onStartCommand), so calling it unconditionally
        // whenever the flag is on is simpler and no less correct than first checking
        // whether the service happens to already be alive.
        viewModelScope.launch {
            if (settings.recorderEnabled.first()) {
                recorderStartFailed.value = !ChargeRecorderService.start(context)
            }
        }
    }

    fun setRecorderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val started = settings.setRecorderEnabled(enabled)
            recorderStartFailed.value = enabled && !started
        }
    }

    /** Called with the result of requesting POST_NOTIFICATIONS (API 33+) from the screen. */
    fun onNotificationPermissionResult(granted: Boolean) {
        notificationsDenied.value = !granted
    }

    /**
     * `mah` has already passed `DesignCapacityValidation` in the dialog -- this is a
     * plain write, not a second place that could reject it differently.
     */
    fun setDesignCapacityOverride(mah: Int) {
        viewModelScope.launch { settings.setDesignCapacityOverride(mah) }
    }

    /** Removes the override; `DesignCapacityProvider` falls back to the model table. */
    fun clearDesignCapacityOverride() {
        viewModelScope.launch { settings.setDesignCapacityOverride(null) }
    }
}

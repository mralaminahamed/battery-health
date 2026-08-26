package com.alaminahamed.batteryhealth.ui.health

import android.content.Context
import com.alaminahamed.batteryhealth.data.framework.GrantedReadings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.alaminahamed.batteryhealth.data.repo.BatteryRepository
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.sampling.ChargeRecorderService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class HealthViewModel @Inject constructor(
    private val granted: GrantedReadings,
    @param:Named("privilegedTierSupported") private val privilegedTierSupported: Boolean,
    private val repository: BatteryRepository,
    private val settings: SettingsStore,
    designCapacity: DesignCapacityProvider,
    private val privileged: PrivilegedBatterySource,
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
        }.combine(privileged.state) { partial, availability ->
            partial.copy(privilegedAvailability = availability)
        }.combine(repository.privilegedDumpFailed) { partial, dumpFailed ->
            partial.copy(privilegedDumpFailed = dumpFailed)
        }.combine(settings.unlockCardDismissed) { partial, dismissed ->
            partial.copy(unlockCardDismissed = dismissed)
        }.map { state ->
            // Read on every emission rather than once at construction: `pm grant` can run
            // while the app is open, and a card that kept offering a setup already done
            // would be the very staleness this field exists to remove. It is a cheap
            // PackageManager check, not a query.
            state.copy(
                batteryStatsGranted = granted.isGranted,
                privilegedTierSupported = privilegedTierSupported,
            )
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

    /**
     * Establishes the privileged tier -- see `PrivilegedBatterySource.connect`'s own doc
     * for exactly what that means and which prompt, if any, it raises. `connect()` is
     * `suspend`, so this launches it on [viewModelScope] rather than calling it directly;
     * `UnlockCard` only wires its action button to this while `state.privilegedAvailability`
     * is a state that actually has something to connect.
     */
    /**
     * Permanent, not per-session: the point of dismissing an offer is not being pitched
     * it again. There is deliberately no un-dismiss here -- the card only ever explained
     * a setup the user can still start from Settings, and the states that matter
     * (awaiting authorization, connecting, a failed read needing retry) are shown
     * regardless of dismissal. See `UnlockCardVisibility`.
     */
    fun dismissUnlockCard() {
        viewModelScope.launch { settings.setUnlockCardDismissed(true) }
    }

    fun connectPrivilegedTier() {
        viewModelScope.launch { privileged.connect() }
    }

    /**
     * Re-checks or re-establishes both transports since this ViewModel was created -- the
     * facts `privileged.state` cannot learn on its own; see `PrivilegedBatterySource.refresh`'s
     * doc. Called from the Health screen's own resume, not just once here, so enabling
     * wireless debugging, granting root, and switching back to this app all update the
     * same session without a restart.
     *
     * Also re-dumps the privileged fields (`repository.retryPrivilegedDump()`): Battery
     * Protect's mode and threshold are a live Samsung Settings toggle the user could have
     * just changed in the background, and `privileged.refresh()` alone would not notice --
     * see `BatteryRepository.redumpRequests`'s doc for why both that and a stuck failed
     * dump share this one entry point.
     */
    fun refreshPrivilegedTier() {
        privileged.refresh()
        repository.retryPrivilegedDump()
    }

    /**
     * The Health screen's manual "Retry" action on `UnlockCard` once it is `Ready` but
     * the last privileged dump attempt failed -- see `BatteryRepository.retryPrivilegedDump`.
     * A thin pass-through by design: this ViewModel is not where the retry/resume policy
     * decision lives, the repository is.
     */
    fun retryPrivilegedDump() {
        repository.retryPrivilegedDump()
    }
}

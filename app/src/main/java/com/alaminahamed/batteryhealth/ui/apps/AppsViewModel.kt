package com.alaminahamed.batteryhealth.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.apps.AppRowMapper
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.alaminahamed.batteryhealth.data.repo.BatteryRepository
import com.alaminahamed.batteryhealth.domain.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: BatteryRepository,
    private val privileged: PrivilegedBatterySource,
    private val rowMapper: AppRowMapper,
    @param:Named("privilegedTierSupported") private val privilegedTierSupported: Boolean,
) : ViewModel() {

    val state: StateFlow<AppsUiState> = combine(
        repository.appPower(),
        repository.appPowerFailed,
        repository.appPowerLoading,
        privileged.state,
    ) { entries, failed, loading, availability ->
        AppsUiState(
            privilegedAvailability = availability,
            // Reading.map, the same extension every other screen's Reading transforms
            // go through -- label resolution happens per row here, not inside
            // BatteryRepository, because AppRowMapper needs AppLabelResolver
            // (PackageManager), which the repository layer has no dependency on.
            rows = entries.map { list -> list.map(rowMapper::toRow) },
            appPowerFailed = failed,
            privilegedTierSupported = privilegedTierSupported,
            isLoading = loading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppsUiState(),
    )

    /** Same no-op-unless-there-is-something-to-connect contract as
     * `HealthViewModel.connectPrivilegedTier` -- see `PrivilegedBatterySource.connect`'s
     * own doc. `connect()` is `suspend`, so this launches it on [viewModelScope]. */
    fun connectPrivilegedTier() {
        viewModelScope.launch { privileged.connect() }
    }

    /**
     * Called from every `ON_RESUME`, mirroring `HealthViewModel.refreshPrivilegedTier`:
     * enabling wireless debugging or granting root happens outside this app entirely, so
     * re-checking on resume is what notices it without needing this screen recreated.
     * Also retries the privileged read via [BatteryRepository.retryPrivilegedDump] -- the
     * same shared trigger `BatteryRepository.appPower` and `BatteryRepository.snapshots`
     * both react to, so this one call refreshes both screens' privileged data, not just
     * this one's.
     */
    fun refreshPrivilegedTier() {
        privileged.refresh()
        repository.retryPrivilegedDump()
    }

    /** The Apps screen's manual "Retry" action on `UnlockCard` once it is `Ready` but the
     * last checkin attempt failed -- see `BatteryRepository.appPowerFailed`. */
    fun retryPrivilegedDump() {
        repository.retryPrivilegedDump()
    }
}

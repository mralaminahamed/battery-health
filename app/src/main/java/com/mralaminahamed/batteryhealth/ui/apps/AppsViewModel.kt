package com.mralaminahamed.batteryhealth.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mralaminahamed.batteryhealth.data.apps.AppRowMapper
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.mralaminahamed.batteryhealth.data.repo.BatteryRepository
import com.mralaminahamed.batteryhealth.domain.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val repository: BatteryRepository,
    private val shizuku: PrivilegedBatterySource,
    private val rowMapper: AppRowMapper,
) : ViewModel() {

    val state: StateFlow<AppsUiState> = combine(
        repository.appPower(),
        repository.appPowerFailed,
        repository.appPowerLoading,
        shizuku.state,
    ) { entries, failed, loading, availability ->
        AppsUiState(
            shizukuAvailability = availability,
            // Reading.map, the same extension every other screen's Reading transforms
            // go through -- label resolution happens per row here, not inside
            // BatteryRepository, because AppRowMapper needs AppLabelResolver
            // (PackageManager), which the repository layer has no dependency on.
            rows = entries.map { list -> list.map(rowMapper::toRow) },
            appPowerFailed = failed,
            isLoading = loading,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppsUiState(),
    )

    /** Same no-op-outside-PermissionNotGranted contract as
     * `HealthViewModel.requestShizukuPermission` -- see `PrivilegedBatterySource.requestPermission`'s
     * own doc. */
    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    /**
     * Called from every `ON_RESUME`, mirroring `HealthViewModel.refreshShizuku`: installing
     * or granting Shizuku happens outside this app entirely, so re-checking on resume is
     * what notices it without needing this screen recreated. Also retries the privileged
     * read via [BatteryRepository.retryPrivilegedDump] -- the same shared trigger
     * `BatteryRepository.appPower` and `BatteryRepository.snapshots` both react to, so
     * this one call refreshes both screens' privileged data, not just this one's.
     */
    fun refreshShizuku() {
        shizuku.refresh()
        repository.retryPrivilegedDump()
    }

    /** The Apps screen's manual "Retry" action on `UnlockCard` once it is `Bound` but the
     * last checkin attempt failed -- see `BatteryRepository.appPowerFailed`. */
    fun retryPrivilegedDump() {
        repository.retryPrivilegedDump()
    }
}

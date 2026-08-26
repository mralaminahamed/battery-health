package com.alaminahamed.batteryhealth.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.apps.AppCpuRowMapper
import com.alaminahamed.batteryhealth.data.apps.UidCpuTimeSource
import com.alaminahamed.batteryhealth.domain.AppCpuRanking
import com.alaminahamed.batteryhealth.domain.map
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val cpuTimes: UidCpuTimeSource,
    private val cpuRowMapper: AppCpuRowMapper,
) : ViewModel() {

    /**
     * [UidCpuTimeSource.cpuTimes] is a plain, synchronous read -- not a `Flow` -- so
     * unlike the old privileged-state-driven `combine`, nothing re-invokes it on its own.
     * This ticks once on construction (replay 1, so the very first collector still gets
     * an answer) and again every time [refresh] is called, which is what lets the Apps
     * screen's own `ON_RESUME` pick up CPU time that accumulated while the app was
     * backgrounded.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    val state: StateFlow<AppsUiState> = refreshRequests
        .map {
            cpuTimes.cpuTimes()
                .map(AppCpuRanking::ranked)
                .map { ranked -> ranked.map(cpuRowMapper::toRow) }
        }
        .map { cpuRows -> AppsUiState(cpuRows = cpuRows) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppsUiState(),
        )

    /**
     * Re-reads per-uid CPU time. Called from the Apps screen's own `ON_RESUME`: CPU time
     * accumulates while this app is backgrounded, and [UidCpuTimeSource.cpuTimes] has no
     * way to push an update on its own.
     */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }
}

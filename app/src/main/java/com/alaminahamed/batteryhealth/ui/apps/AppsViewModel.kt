package com.alaminahamed.batteryhealth.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alaminahamed.batteryhealth.data.apps.AppCpuRowMapper
import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.data.apps.ForegroundUsageSource
import com.alaminahamed.batteryhealth.data.apps.UidCpuTimeSource
import com.alaminahamed.batteryhealth.data.local.SampleDao
import com.alaminahamed.batteryhealth.data.repo.EstimateWindow
import com.alaminahamed.batteryhealth.data.repo.EstimatedDrainReading
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.settings.UsageAccessState
import com.alaminahamed.batteryhealth.domain.AppCpuRanking
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.map
import com.alaminahamed.batteryhealth.sampling.NowMs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val cpuTimes: UidCpuTimeSource,
    private val cpuRowMapper: AppCpuRowMapper,
    private val sampleDao: SampleDao,
    private val designCapacityProvider: DesignCapacityProvider,
    private val usageAccessState: UsageAccessState,
    private val foregroundUsageSource: ForegroundUsageSource,
    private val labelResolver: AppLabelResolver,
    private val nowMs: NowMs,
) : ViewModel() {

    /**
     * [UidCpuTimeSource.cpuTimes] is a plain, synchronous read -- not a `Flow` -- so
     * unlike a privileged-state-driven `combine`, nothing re-invokes it on its own.
     * This ticks once on construction (replay 1, so the very first collector still gets
     * an answer) and again every time [refresh] is called, which is what lets the Apps
     * screen's own `ON_RESUME` pick up CPU time -- and the per-app drain estimate below --
     * that changed while the app was backgrounded.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    val state: StateFlow<AppsUiState> = refreshRequests
        .map {
            val cpuRows = cpuTimes.cpuTimes()
                .map(AppCpuRanking::ranked)
                .map { ranked -> ranked.map(cpuRowMapper::toRow) }
            AppsUiState(cpuRows = cpuRows, estimatedDrainRows = estimatedDrain())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppsUiState(),
        )

    /**
     * The per-app drain estimate, recomputed in memory every tick -- nothing here is ever
     * persisted (see the task report). Reads no sample and makes no `UsageStatsManager`
     * call at all when usage access is not held: there would be nothing to do with either
     * result but discard it, and both are real I/O (a Room query, a live Binder call) this
     * screen does not need to pay for on every resume just to report the same
     * [Reading.NeedsUsageAccess] it already knows without them.
     */
    private suspend fun estimatedDrain(): Reading<EstimatedDrain> {
        val usageAccessGranted = usageAccessState.isHeld()
        val result = if (usageAccessGranted) {
            val nowMillis = nowMs.get()
            val requestedStartMs = nowMillis - LOOKBACK_MS
            EstimateWindow.compute(
                samples = sampleDao.samplesSince(requestedStartMs),
                requestedStartMs = requestedStartMs,
                requestedEndMs = nowMillis,
                designCapacityMah = designCapacityProvider.designCapacityMah.first(),
                usageAccessGranted = true,
                queryForegroundMs = foregroundUsageSource::query,
            )
        } else {
            EMPTY_RESULT
        }
        return EstimatedDrainReading.from(usageAccessGranted, result, labelResolver)
    }

    /**
     * Re-reads per-uid CPU time and the per-app drain estimate. Called from the Apps
     * screen's own `ON_RESUME`: both accumulate while this app is backgrounded, and
     * neither has a way to push an update on its own.
     */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    private companion object {
        /**
         * How far back the estimate looks for samples to derive a window from.
         * [EstimateWindow.compute] narrows this down to the samples' own actual span, so
         * this is only ever the upper bound of what could be considered, never the span
         * reported to the user -- see that object's own doc.
         */
        const val LOOKBACK_MS = 24 * 60 * 60 * 1_000L

        /**
         * Stands in for [EstimateWindow.Result] when usage access is not held, so
         * [estimatedDrain] never has to construct one from real (but immediately
         * discarded) data just to hand it to [EstimatedDrainReading.from], which reads
         * only [usageAccessGranted] first anyway and ignores this entirely in that case.
         */
        val EMPTY_RESULT = EstimateWindow.Result(
            entries = emptyList(),
            totalDischargeMah = null,
            windowStartMs = 0L,
            windowEndMs = 0L,
        )
    }
}

package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.domain.ChargeSession
import com.mralaminahamed.batteryhealth.domain.HistoryRange
import com.mralaminahamed.batteryhealth.domain.LevelPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val sampleDao: SampleDao,
    private val sessionDao: SessionDao,
) {
    /**
     * Points are returned exactly as recorded. Gaps caused by Doze stay gaps; the chart
     * breaks its line rather than interpolating data that was never sampled.
     */
    fun levelSeries(range: HistoryRange, nowMs: Long): Flow<List<LevelPoint>> =
        sampleDao.observeSamplesSince(nowMs - range.windowMs)
            .map { samples -> samples.map { it.toLevelPoint() } }

    fun sessions(limit: Int): Flow<List<ChargeSession>> =
        sessionDao.observeCompletedSessions(limit).map { entities ->
            entities.mapNotNull { it.toDomain() }
        }
}

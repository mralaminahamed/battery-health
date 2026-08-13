package com.mralaminahamed.batteryhealth.sampling

import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RetentionPruner @Inject constructor(
    private val sampleDao: SampleDao,
    private val sessionDao: SessionDao,
    private val nowMs: NowMs,
) {
    /** Returns the number of samples removed. */
    suspend fun prune(): Int {
        val now = nowMs.get()
        val removedSamples = sampleDao.deleteOlderThan(now - SAMPLE_RETENTION_MS)
        sessionDao.deleteOlderThan(now - SESSION_RETENTION_MS)
        return removedSamples
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Deliberately longer than the History screen's 30-day range, so the oldest
         * visible point is never sitting in a partially pruned window.
         */
        const val SAMPLE_RETENTION_MS = 45 * DAY_MS
        const val SESSION_RETENTION_MS = 365 * DAY_MS
    }
}

package com.alaminahamed.batteryhealth.sampling

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The always-on baseline. Fifteen minutes is WorkManager's floor for periodic work, and
 * it is too coarse to measure capacity — that is what the charge recorder is for. This
 * worker exists so the long-term level trend survives without the app being opened.
 */
@HiltWorker
class BaselineSampleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sampleWriter: SampleWriter,
    private val retentionPruner: RetentionPruner,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        sampleWriter.writeOne()
        retentionPruner.prune()
        return Result.success()
    }
}

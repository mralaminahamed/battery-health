package com.alaminahamed.batteryhealth.sampling

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SamplingScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    fun scheduleBaseline() {
        workManager.enqueueUniquePeriodicWork(
            BASELINE_WORK_NAME,
            // KEEP, so reopening the app never resets the interval and loses a period.
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BaselineSampleWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    companion object {
        const val BASELINE_WORK_NAME = "baseline-sampling"
    }
}

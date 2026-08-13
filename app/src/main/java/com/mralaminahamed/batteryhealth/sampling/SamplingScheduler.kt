package com.mralaminahamed.batteryhealth.sampling

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
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

    /**
     * Arms the charge recorder. WorkManager (via JobScheduler) holds the charging
     * constraint across process death and reboot, starts the worker's process itself
     * when charging begins, and stops it the moment charging ends -- no broadcast
     * receiver is involved at any point. KEEP so an already-enqueued or already-running
     * recording is never disrupted by a redundant call (e.g. the settings value being
     * re-written to the same `true`).
     *
     * Suspends until WorkManager's own enqueue actually completes, rather than firing it
     * and returning: `enqueueUniqueWork` only submits the request to WorkManager's own
     * background executor, so a caller that does not wait can race a short-lived process
     * lifetime -- observed directly on-device, where a bare instrumentation process could
     * exit before an unawaited operation had propagated to the system JobScheduler.
     */
    suspend fun scheduleChargeRecorder() {
        workManager.enqueueUniqueWork(
            CHARGE_RECORDER_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ChargeRecorderWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build(),
        ).await()
    }

    /**
     * Disarms the charge recorder; a currently running recording is stopped. Awaited for
     * the same reason as `scheduleChargeRecorder`: a caller must be able to trust that the
     * cancellation has actually reached WorkManager, not merely been requested.
     */
    suspend fun cancelChargeRecorder() {
        workManager.cancelUniqueWork(CHARGE_RECORDER_WORK_NAME).await()
    }

    companion object {
        const val BASELINE_WORK_NAME = "baseline-sampling"
        const val CHARGE_RECORDER_WORK_NAME = "charge-recorder"
    }
}

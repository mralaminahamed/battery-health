package com.mralaminahamed.batteryhealth.sampling

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mralaminahamed.batteryhealth.R
import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.data.local.SessionEntity
import com.mralaminahamed.batteryhealth.data.repo.SESSION_TYPE_CHARGE
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.PlugType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Samples every five seconds while the cable is in. Capacity measurement needs this
 * resolution; the 15-minute baseline cannot produce it. Running only while charging is
 * the whole point -- a battery app with a permanent notification and continuous wakeups
 * argues against itself.
 *
 * This is a `CoroutineWorker`, not a `Service`, because `ACTION_POWER_CONNECTED` and
 * `ACTION_POWER_DISCONNECTED` are not on Android's implicit-broadcast exception list --
 * that has been the rule since API 26, and the background-optimization guide names
 * `ACTION_POWER_CONNECTED` explicitly as a receiver apps must remove from the manifest.
 * `SamplingScheduler` enqueues this as unique work constrained on `setRequiresCharging`,
 * so WorkManager (via JobScheduler) starts the worker's process itself when charging
 * begins, even from a cold, previously-killed process -- confirmed directly on-device
 * (Pixel_10, API 37) via `adb shell am kill` followed by `dumpsys battery set ac 1`.
 *
 * Two platform behaviours observed directly on-device do not match what the constraint
 * alone would suggest, and both are compensated for here rather than assumed away:
 *
 * 1. JobScheduler does not promptly preempt this worker's coroutine once the charging
 *    constraint stops being satisfied -- a live recording kept sampling for over 90
 *    seconds after `dumpsys battery unplug` with no sign of being stopped. The sampling
 *    loop below checks the live plug state itself on every tick and exits on its own
 *    initiative the first time the cable reads as out, closing the session from the same
 *    `finally` either way.
 *
 * 2. A cold start's foreground-service-start exemption is not reliable: `setForeground()`
 *    threw `ForegroundServiceStartNotAllowedException` on the majority of cold-start
 *    attempts in testing (`ActivityManager` logged `BFGS denied: true`,
 *    `tempAllowListReason: <null>` -- no temporary allowlist entry had actually been
 *    granted for the process at the moment doWork() reached it, even though it is the very
 *    first suspending call). This looks like a genuine platform reliability limit for a
 *    plain constrained one-time worker rather than something fixable by call ordering
 *    alone, and is reported as such rather than hidden behind a workaround that only
 *    sometimes works. What *is* fixed here is the compounding failure this exposed: this
 *    path used to return `Result.failure()`, which is terminal for unique work -- one
 *    unlucky race would have silently and permanently disabled the recorder until the
 *    setting was manually toggled off and on. It returns `Result.retry()` instead, so
 *    WorkManager retries (respecting backoff and the still-charging constraint) rather
 *    than abandoning the feature after a single lost race.
 */
@HiltWorker
class ChargeRecorderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sampleWriter: SampleWriter,
    private val broadcasts: BatteryBroadcastSource,
    private val sampleDao: SampleDao,
    private val sessionDao: SessionDao,
    private val nowMs: NowMs,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // setForeground() must be the very first suspending call in doWork(), before
        // anything else -- including the recorderEnabled check below. Verified directly
        // on-device: a JobScheduler cold start only grants a narrow foreground-start
        // exemption window, and reading recorderEnabled first (DataStore disk I/O) was
        // enough by itself to push setForeground() past it, failing with
        // ForegroundServiceStartNotAllowedException on every cold-start run.
        try {
            setForeground(foregroundInfo())
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // API 31+ can refuse a background promotion to foreground -- observed
            // directly on-device to happen on a real fraction of cold starts, not just
            // as a theoretical edge case. Result.retry() rather than Result.failure():
            // a terminal failure would permanently end this unique work, silently
            // disabling the recorder until the user re-toggled the setting. Retrying
            // costs nothing while still charging and gives the next attempt a chance to
            // land in a process state the exemption is actually granted for.
            return Result.retry()
        }

        // A constraint-triggered run can start well after the settings screen changed
        // the flag -- WorkManager may already have this queued from before that change,
        // so the gate must be re-checked here, not just at enqueue time. Checked only
        // now, after setForeground() has already succeeded, never before it.
        if (!settings.recorderEnabled.first()) return Result.success()

        var sessionId: Long? = null
        try {
            val id = openSession() ?: return Result.success()
            sessionId = id
            while (true) {
                sampleWriter.writeOne(sessionId = id)
                // Self-checked, not left to WorkManager: see the class doc for the
                // on-device evidence that constraint loss alone is not a reliable stop
                // signal for an already-running job.
                if (broadcasts.broadcasts().first().plugType == PlugType.None) {
                    return Result.success()
                }
                delay(SAMPLE_INTERVAL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            return Result.success()
        } finally {
            // WorkManager stops this coroutine by cancelling it (constraints no longer
            // met, or the app process being reclaimed) -- that is the normal, expected
            // way this loop ends, not an exceptional one. A `finally` runs on
            // cancellation, but any further suspending call inside it would immediately
            // rethrow unless shielded: closing the session is one Room UPDATE and must
            // complete, so it runs under `NonCancellable`. This is the coroutine
            // equivalent of the service's user-approved blocking close, and is strictly
            // better -- it suspends properly instead of blocking a thread.
            withContext(NonCancellable) {
                sessionId?.let { closeSession(it) }
            }
        }
    }

    private suspend fun openSession(): Long? {
        // A session left open by an earlier crash is closed by its own id, before this
        // one is inserted -- closeSession() below only ever acts on the specific id it
        // is given, so it cannot be confused with the session about to be opened here.
        sessionDao.openSession()?.let { closeSession(it.id) }

        val firstSampleId = sampleWriter.writeOne() ?: return null
        // Fetched by the id just returned, not "the latest sample": the baseline worker
        // can insert its own row between this insert and any later query, so "latest"
        // can silently resolve to someone else's sample instead of this one.
        val first = sampleDao.byId(firstSampleId) ?: return null

        val id = sessionDao.insert(
            SessionEntity(
                type = SESSION_TYPE_CHARGE,
                startedAtMs = first.timestampMs,
                endedAtMs = null,
                startLevelPct = first.levelPct,
                endLevelPct = null,
                startCounterUah = first.chargeCounterUah,
                endCounterUah = null,
                coulombUah = null,
                peakTempDeciC = first.tempDeciC,
                avgMilliwatts = null,
                screenOnMs = 0,
            )
        )
        sampleDao.attachToSession(sessionId = id, fromMs = first.timestampMs)
        return id
    }

    private suspend fun closeSession(id: Long) {
        // Asks for the open session with this exact id, not "whichever session happens
        // to be open right now" -- so this can never close a different session than the
        // one the caller means.
        val open = sessionDao.openSessionById(id) ?: return
        val samples = sampleDao.samplesForSession(id)
        val aggregate = SessionAggregator.aggregate(samples)
        // The last sample's own timestamp is the honest end time. `nowMs.get()` would be
        // right for an unplug closing its own live session, but wrong for a stale
        // session recovered days later at the next charge -- it would stamp that
        // recovery moment as the session's end and inflate its duration by however long
        // the device sat unplugged in between.
        val endedAtMs = samples.lastOrNull()?.timestampMs ?: nowMs.get()
        sessionDao.update(
            open.copy(
                endedAtMs = endedAtMs,
                endLevelPct = aggregate.endLevelPct,
                endCounterUah = aggregate.endCounterUah,
                coulombUah = aggregate.coulombUah,
                peakTempDeciC = aggregate.peakTempDeciC ?: open.peakTempDeciC,
                avgMilliwatts = aggregate.avgMilliwatts,
                screenOnMs = aggregate.screenOnMs,
            )
        )
    }

    private fun foregroundInfo(): ForegroundInfo {
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Charge recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while a charge session is being measured" }
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Battery Health")
            .setContentText("Recording charge session")
            // R.drawable.ic_notification, from Task 14 -- a 24dp white silhouette. The
            // status bar draws only the alpha channel, so a colour launcher icon here
            // would render as a featureless white blob.
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "charge-recorder"
        const val NOTIFICATION_ID = 1
        const val SAMPLE_INTERVAL_MS = 5_000L
    }
}

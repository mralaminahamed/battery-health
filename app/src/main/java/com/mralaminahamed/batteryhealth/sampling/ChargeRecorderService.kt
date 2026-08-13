package com.mralaminahamed.batteryhealth.sampling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.mralaminahamed.batteryhealth.R
import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.data.local.SessionEntity
import com.mralaminahamed.batteryhealth.data.repo.SESSION_TYPE_CHARGE
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Samples every five seconds while the cable is in. Capacity measurement needs this
 * resolution; the 15-minute baseline cannot produce it. Running only while charging is
 * the whole point — a battery app with a permanent notification and continuous wakeups
 * argues against itself.
 */
@AndroidEntryPoint
class ChargeRecorderService : Service() {

    @Inject lateinit var sampleWriter: SampleWriter
    @Inject lateinit var sampleDao: SampleDao
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var nowMs: NowMs

    private val scope = CoroutineScope(SupervisorJob())
    private var samplingJob: Job? = null
    private var sessionId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Recording charge session"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (samplingJob != null) return START_STICKY

        samplingJob = scope.launch {
            val id = openSession() ?: return@launch
            sessionId = id
            while (true) {
                sampleWriter.writeOne(sessionId = id)
                delay(SAMPLE_INTERVAL_MS)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        // Closing the session must complete before the process is torn down, otherwise a
        // recorded session stays open forever and never reaches the estimator. Approved
        // as an exception to the no-runBlocking-on-teardown rule: the write is one Room
        // UPDATE, and an unclosed session silently breaks the health number.
        runBlocking { sessionId?.let { closeSession(it) } }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun openSession(): Long? {
        // A session left open by an earlier crash is closed first, so there is never more
        // than one. This must run to completion before the insert below, otherwise the
        // close could race the very session this call is about to open.
        sessionDao.openSession()?.let { closeSession(it.id) }

        val firstSampleId = sampleWriter.writeOne() ?: return null
        val first = sampleDao.latest() ?: return null
        if (first.id != firstSampleId) return null

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
        val open = sessionDao.openSession()?.takeIf { it.id == id } ?: return
        val aggregate = SessionAggregator.aggregate(sampleDao.samplesForSession(id))
        sessionDao.update(
            open.copy(
                endedAtMs = nowMs.get(),
                endLevelPct = aggregate.endLevelPct,
                endCounterUah = aggregate.endCounterUah,
                coulombUah = aggregate.coulombUah,
                peakTempDeciC = aggregate.peakTempDeciC ?: open.peakTempDeciC,
                avgMilliwatts = aggregate.avgMilliwatts,
                screenOnMs = aggregate.screenOnMs,
            )
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Charge recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while a charge session is being measured" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Health")
            .setContentText(text)
            // R.drawable.ic_notification, from Task 14 — a 24dp white silhouette. The
            // status bar draws only the alpha channel, so a colour launcher icon here
            // would render as a featureless white blob.
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "charge-recorder"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChargeRecorderService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeRecorderService::class.java))
        }
    }
}

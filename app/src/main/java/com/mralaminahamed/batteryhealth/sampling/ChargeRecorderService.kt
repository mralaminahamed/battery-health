package com.mralaminahamed.batteryhealth.sampling

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.mralaminahamed.batteryhealth.R
import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.data.local.SessionEntity
import com.mralaminahamed.batteryhealth.data.repo.SESSION_TYPE_CHARGE
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.PlugType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Lives for as long as `recorderEnabled` is on, not only while the cable is in --
 * sampling every five seconds is still gated on actually charging, but the process
 * itself is kept alive as a foreground service the whole time the setting is on.
 *
 * This exists because the alternatives were both tried and both failed on real
 * measurement, not on theory:
 *
 * - A manifest `BroadcastReceiver` for `ACTION_POWER_CONNECTED`/`ACTION_POWER_DISCONNECTED`
 *   never fires: those two actions are not on Android's implicit-broadcast exception
 *   list, and have not been since API 26.
 * - A `CoroutineWorker` constrained on `setRequiresCharging`, started cold by
 *   JobScheduler, hit Android 12+'s foreground-service-start restriction on the
 *   majority of cold-start attempts in testing (`ActivityManager` logged
 *   `BFGS denied: true, tempAllowListReason:<null>`) -- a charging-constrained
 *   background job simply is not granted the exemption reliably.
 *
 * Starting the service is different: `SettingsStore.setRecorderEnabled` starts it from
 * the app's own foreground call site (the settings toggle, or the boot receiver, both of
 * which are recognised start contexts), never from a background trigger, so the
 * restriction above never applies here. Once running, holding the process at foreground
 * importance is what makes plug/unplug detection reliable: a runtime-registered receiver
 * (via `BatteryBroadcastSource`, already used elsewhere in this codebase) only has to
 * survive for as long as the process does, and a foreground service is the one thing
 * Android will not casually reclaim.
 *
 * Stopping is asymmetric with starting, on purpose: `SettingsStore` never calls
 * `Context.stopService()` on this class -- there is no such method to call. Disabling
 * the setting only writes the flag; the service watches that same flag itself (see
 * `onCreate()`) and calls `stopSelf()` once it turns false. An external stop call was
 * tried first and rejected: it can race ahead of a just-started service's own
 * `onCreate()`, and did so directly on-device -- enabling immediately followed by
 * disabling, with no delay between the two, crashed the app with
 * `ForegroundServiceDidNotStartInTimeException` (`SettingsStoreTest.recorderFlagRoundTrips`
 * reproduced it as an ordinary DataStore round-trip test, with no awareness it was also
 * exercising a service lifecycle). Self-stopping cannot hit that race, because the
 * suspend point that observes `false` is only reachable after this same service's
 * `startForeground()` call has already completed.
 */
@AndroidEntryPoint
class ChargeRecorderService : Service() {

    @Inject lateinit var sampleWriter: SampleWriter
    @Inject lateinit var broadcasts: BatteryBroadcastSource
    @Inject lateinit var sampleDao: SampleDao
    @Inject lateinit var sessionDao: SessionDao
    @Inject lateinit var nowMs: NowMs
    @Inject lateinit var settings: SettingsStore

    private val scope = CoroutineScope(SupervisorJob())
    private var samplingJob: Job? = null
    private var sessionId: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // startForeground() must happen promptly regardless of what the flag check
        // below decides; the idle notification is corrected within milliseconds if the
        // flag turns out to be false, once the check completes.
        startForeground(NOTIFICATION_ID, buildNotification(isCharging = false))

        scope.launch {
            // A start can arrive after the setting has already flipped back to false --
            // e.g. a rapid on/off toggle, or a stale start Intent redelivered by the
            // system. This is the general rule ("the service is running") that must not
            // preempt the specific one ("but the setting says no"), so the flag is
            // re-read here rather than trusted from whoever called start().
            if (!settings.recorderEnabled.first()) {
                stopSelf()
                return@launch
            }
            observePlugState()

            // Stops itself the moment the setting turns off, rather than being stopped
            // from outside via Context.stopService(). This suspend point is reached
            // only after startForeground() above has already completed, which matters:
            // SettingsStore used to call ChargeRecorderService.stop() directly on
            // disable, and that external stop could race ahead of a just-started
            // service's own onCreate() -- confirmed directly on-device, where
            // SettingsStoreTest.recorderFlagRoundTrips (enable immediately followed by
            // disable, no delay) crashed the app with
            // ForegroundServiceDidNotStartInTimeException. Watching the same flag from
            // inside the already-running service cannot hit that race: by the time this
            // line can observe `false`, this service's own startForeground() call is
            // necessarily long done.
            settings.recorderEnabled.first { !it }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

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

    /**
     * The single place plug state is watched and acted on, for as long as the service
     * lives. `distinctUntilChanged()` collapses the stream to real transitions -- a
     * duplicate "still plugged in" reading from an unrelated battery-changed broadcast
     * (level ticking down a percent, temperature drifting) never reaches the handler
     * below, so it can never be mistaken for a second connect event. The `if
     * (samplingJob == null)` / `else` guards are a second, independent line of defence
     * against the same failure mode -- a rapid unplug-replug still opens a *new* session
     * for the new plug-in (correct), but can never open a second session on top of one
     * already running, and a spurious duplicate disconnect is a safe no-op.
     */
    private fun observePlugState() {
        broadcasts.broadcasts()
            .map { it.plugType != PlugType.None }
            .distinctUntilChanged()
            .onEach { plugged ->
                if (plugged) {
                    if (samplingJob != null) return@onEach
                    val id = openSession() ?: return@onEach
                    sessionId = id
                    updateNotification(isCharging = true)
                    samplingJob = scope.launch {
                        while (true) {
                            sampleWriter.writeOne(sessionId = id)
                            delay(SAMPLE_INTERVAL_MS)
                        }
                    }
                } else {
                    samplingJob?.cancel()
                    samplingJob = null
                    sessionId?.let { closeSession(it) }
                    sessionId = null
                    updateNotification(isCharging = false)
                }
            }
            .launchIn(scope)
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

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Charge recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the charge recorder is switched on" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(isCharging: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(isCharging))
    }

    private fun buildNotification(isCharging: Boolean): Notification {
        // The user switched this on deliberately (Task 6's opt-in setting); a
        // notification they asked for is honest, and it is the visible signal that
        // measurement is happening. What it must not do is claim to be working when it
        // is idle, so the text reflects which of the two states the service is in.
        val text = if (isCharging) "Recording charge session" else "Waiting for the charger"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Health")
            .setContentText(text)
            // R.drawable.ic_notification, from Task 14 -- a 24dp white silhouette. The
            // status bar draws only the alpha channel, so a colour launcher icon here
            // would render as a featureless white blob.
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "charge-recorder"
        private const val NOTIFICATION_ID = 1
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val TAG = "ChargeRecorderService"

        /**
         * Called only from a recognised foreground start context (the settings toggle,
         * or the boot receiver responding to `ACTION_BOOT_COMPLETED`) -- never from a
         * background trigger, which is what made the previous two designs unreliable.
         * The catch is defensive, not expected to fire from either call site, but stays
         * because a caller mistake here should not crash the app.
         *
         * There is deliberately no companion `stop()`: an external `Context.stopService()`
         * call can race ahead of a just-started service's own `onCreate()` and crash with
         * `ForegroundServiceDidNotStartInTimeException` if the two land close enough
         * together -- confirmed directly on-device. The service watches its own
         * `recorderEnabled` flag (see `onCreate()`) and calls `stopSelf()` once that flag
         * turns false, which cannot hit that race: by the time it can observe `false`,
         * this same service's `startForeground()` call is necessarily long done.
         */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ChargeRecorderService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Charge recorder could not start", e)
            }
        }
    }
}

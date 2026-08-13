package com.mralaminahamed.batteryhealth.sampling

import android.annotation.SuppressLint
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
 * `onCreate()`) and calls `stopSelfResult()` once it turns false. An external stop call
 * was tried first and rejected: it can race ahead of a just-started service's own
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

    // Volatile: written from the plug-state collector coroutine, read from onDestroy()
    // on the main thread. Plain fields here is the regression this project already made
    // once -- Task 12's WorkManager round had these as doWork()'s own local variables,
    // which made the cross-thread question moot by construction; moving the state back
    // into a Service revived the fields, and with them, the need for a real
    // happens-before edge. A stale null read at onDestroy() skips closing the session
    // and strands it open, invisible to completedSessions until a lucky future
    // openSession() recovers it.
    @Volatile private var samplingJob: Job? = null
    @Volatile private var sessionId: Long? = null

    // The most recently delivered startId, used to make a self-stop resilient to a
    // start that lands concurrently with it (see onStartCommand and the watcher below).
    @Volatile private var lastStartId: Int = 0

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
                stopSelfResult(lastStartId)
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
            //
            // stopSelfResult(lastStartId), not a bare stopSelf(): a bare call tears the
            // service down unconditionally, even if a newer start (a re-enable) has
            // already been delivered by the time this line runs -- stopSelfResult only
            // proceeds if lastStartId is still the most recent one the system recorded,
            // so a concurrent re-enable is not silently discarded.
            settings.recorderEnabled.first { !it }
            stopSelfResult(lastStartId)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Re-validated independently of onCreate()'s watcher, tied to this specific
        // startId: a start that lands just as the setting flips back off corrects
        // itself immediately, rather than waiting for the watcher's own next flag read
        // (which, for a service that was already running, might not come at all if
        // nothing else changes the flag afterward).
        scope.launch {
            if (!settings.recorderEnabled.first()) {
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        // Closing the session must complete before the process is torn down, otherwise a
        // recorded session stays open forever and never reaches the estimator. Approved
        // as an exception to the no-runBlocking-on-teardown rule: the write is one Room
        // UPDATE, and an unclosed session silently breaks the health number.
        runBlocking { sessionId?.let { closeSession(it, endedAtMs = nowMs.get()) } }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The single place plug state is watched and acted on, for as long as the service
     * lives. Built on `rawBroadcasts()`, not the conflated `broadcasts()`: this handler
     * treats each transition itself as the payload, and conflation upstream of the
     * `distinctUntilChanged()` below can silently collapse a genuine
     * disconnect-then-reconnect (or the reverse) that lands while this handler is still
     * busy with the previous event into a single unchanged value -- turning a real
     * unplug into silence rather than a visible gap, with no error and nothing for the
     * 30-second integration guard to catch, since no gap that size occurs. A cable
     * wiggle at plug-in is exactly the kind of event pair that lands close enough
     * together to hit this if conflated.
     *
     * `distinctUntilChanged()` collapses the (unconflated) stream to real transitions --
     * a duplicate "still plugged in" reading from an unrelated battery-changed broadcast
     * (level ticking down a percent, temperature drifting) never reaches the handler
     * below, so it can never be mistaken for a second connect event. The `if
     * (samplingJob == null)` / `else` guards are a second, independent line of defence
     * against the same failure mode -- a rapid unplug-replug still opens a *new* session
     * for the new plug-in (correct), but can never open a second session on top of one
     * already running, and a spurious duplicate disconnect is a safe no-op.
     */
    private fun observePlugState() {
        broadcasts.rawBroadcasts()
            .map { it.plugType != PlugType.None }
            .distinctUntilChanged()
            .onEach { plugged ->
                if (plugged) {
                    if (samplingJob != null) return@onEach
                    val id = openSession()
                    if (id == null) {
                        // Left unlogged before, this failure was invisible: the cable is
                        // in, nothing above changes, and the notification keeps saying
                        // "Waiting for the charger" -- actively wrong about the state
                        // the device is actually in.
                        Log.w(TAG, "Could not open a session while plugged in; will retry on the next transition")
                        return@onEach
                    }
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
                    // nowMs.get(), not the last sample's timestamp: this is the live
                    // disconnect happening now, and the most recent sample can be up to
                    // one sampling interval stale -- stamping from it truncates a short
                    // session by however long has elapsed since that last 5-second tick
                    // (measured directly: 5,126ms recorded for a session that was
                    // actually connected for roughly 8s). The stale-session-recovery
                    // path in openSession() is different and deliberately keeps the
                    // last-sample fallback: there, "now" would be whenever the recovery
                    // happened to run, which can be arbitrarily later than the session
                    // actually ended.
                    sessionId?.let { closeSession(it, endedAtMs = nowMs.get()) }
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
        // No explicit endedAtMs: the honest end time for a session recovered this way is
        // whenever it was last actually recorded, not whenever this recovery happened to
        // run, so closeSession falls back to the last sample's own timestamp.
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
        // Published as soon as the row exists, not after attachToSession below returns:
        // onDestroy() reads this field on a completely different call path, and the
        // window between the insert above and the assignment previously sitting at the
        // call site was a real, deterministic strand -- a destroy landing in that window
        // closed nothing, then cancelled this coroutine before it could ever assign the
        // id, leaving the row open forever (recoverable only by a future openSession(),
        // which needs the recorder re-enabled *and* a plug event).
        sessionId = id
        sampleDao.attachToSession(sessionId = id, fromMs = first.timestampMs)
        return id
    }

    /**
     * @param endedAtMs The honest end time, chosen by the caller. The live unplug path
     * passes `nowMs.get()`; the stale-session-recovery path in [openSession] passes
     * nothing and falls back to the last attached sample's own timestamp (or, if the
     * session somehow has no samples at all, its own start time -- never "now", which
     * for a session recovered days later would inflate its duration by however long the
     * device sat unplugged in between).
     */
    private suspend fun closeSession(id: Long, endedAtMs: Long? = null) {
        // Asks for the open session with this exact id, not "whichever session happens
        // to be open right now" -- so this can never close a different session than the
        // one the caller means.
        val open = sessionDao.openSessionById(id) ?: return
        val samples = sampleDao.samplesForSession(id)
        val aggregate = SessionAggregator.aggregate(samples)
        val resolvedEndedAtMs = endedAtMs ?: samples.lastOrNull()?.timestampMs ?: open.startedAtMs
        sessionDao.update(
            open.copy(
                endedAtMs = resolvedEndedAtMs,
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
         * the boot receiver responding to `ACTION_BOOT_COMPLETED`, or the Health screen
         * re-arming on launch) -- never from a background trigger, which is what made
         * the previous two designs unreliable. Returns whether the start call itself
         * succeeded, so a caller can reflect a refusal instead of it disappearing into
         * a log line.
         *
         * There is deliberately no companion `stop()`: an external `Context.stopService()`
         * call can race ahead of a just-started service's own `onCreate()` and crash with
         * `ForegroundServiceDidNotStartInTimeException` if the two land close enough
         * together -- confirmed directly on-device. The service watches its own
         * `recorderEnabled` flag (see `onCreate()`) and calls `stopSelfResult()` once
         * that flag turns false, which cannot hit that race: by the time it can observe
         * `false`, this same service's `startForeground()` call is necessarily long done.
         *
         * `ForegroundServiceStartNotAllowedException` is API 31+, but minSdk here is 26
         * -- lint flags this (`NewApi`) as it would for a call to a new *method*, but
         * catching an exception *type* introduced later is a different, safe case:
         * ART's verifier resolves catch-handler types lazily, and on API 26-30 this
         * exception can never actually be thrown (the class does not exist in the
         * framework there yet, and nothing on those platforms constructs one), so the
         * catch clause is simply dead code on those versions, not a crash risk.
         */
        @SuppressLint("NewApi")
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ChargeRecorderService::class.java))
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Charge recorder could not start", e)
            false
        }
    }
}

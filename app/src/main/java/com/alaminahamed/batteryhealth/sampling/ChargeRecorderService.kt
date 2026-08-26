package com.alaminahamed.batteryhealth.sampling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.alaminahamed.batteryhealth.R
import com.alaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.alaminahamed.batteryhealth.data.framework.CurrentScaleDetector
import com.alaminahamed.batteryhealth.data.local.SampleDao
import com.alaminahamed.batteryhealth.data.local.SessionDao
import com.alaminahamed.batteryhealth.data.local.SessionEntity
import com.alaminahamed.batteryhealth.data.repo.SESSION_TYPE_CHARGE
import com.alaminahamed.batteryhealth.data.repo.SESSION_TYPE_DISCHARGE
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.domain.PlugType
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
 * `watchRecorderEnabled()`) and calls `stopSelfResult()` once it turns false. An
 * external stop call was tried first and rejected: it can race ahead of a
 * just-started service's own `onCreate()`, and did so directly on-device -- enabling
 * immediately followed by disabling, with no delay between the two, crashed the app
 * with `ForegroundServiceDidNotStartInTimeException` (`SettingsStoreTest.recorderFlagRoundTrips`
 * reproduced it as an ordinary DataStore round-trip test, with no awareness it was also
 * exercising a service lifecycle). Self-stopping cannot hit that race, because the
 * suspend point that observes `false` is only reachable after this same service's
 * `startForeground()` call has already completed. `watchRecorderEnabled()` also loops
 * rather than watching once -- see its own doc for why a one-shot watcher is a second,
 * subtler version of the same "who is allowed to stop this" question.
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

        scope.launch { watchRecorderEnabled() }
    }

    /**
     * The only continuous watcher this service has for `recorderEnabled`; everything
     * about stopping goes through here, and it loops rather than running once. That
     * loop is the fix for a real failure mode: `stopSelfResult(id)` is refused
     * whenever `id` is not AMS's *current* latest start id, and AMS bumps that id at
     * start-request time -- before `onStartCommand` ever runs and updates
     * [lastStartId]. A disable that reaches the `stopSelfResult` call just as a
     * re-enable lands (10-100ms of scheduling delay on `Dispatchers.Default` is
     * unremarkable under load) makes the stop attempt fail, correctly: a newer start
     * really is in flight. But a watcher that simply returns after that refusal, as
     * an earlier version of this method did, leaves the service with *no observer at
     * all* from then on -- the flag can turn false again later and nothing will ever
     * notice, so nothing will ever call `stopSelfResult` again. For an opt-in
     * recorder with a persistent notification, a setting that reads off while
     * recording continues forever (short of a force-stop, reboot, or process death)
     * is a consent problem, not just a stray foreground service. Looping back after
     * a refusal, instead of returning, is what keeps an observer alive for the *next*
     * disable, which the earlier one implicitly promised to watch for.
     *
     * The same loop also covers the narrower version of this bug at creation time: if
     * the flag is already false when this runs, [lastStartId] may still be `0`
     * (`onStartCommand` normally wins this race -- its own first suspension point, a
     * DataStore read, is slower than this coroutine reaching the check below -- but
     * "normally" is not "always"). `0` was never issued by AMS, so `stopSelfResult(0)`
     * is guaranteed to be refused; looping back and retrying, rather than returning
     * unconditionally after one attempt, is what stops that from stranding a
     * foreground service that observes nothing and that nothing can ever stop.
     */
    private suspend fun watchRecorderEnabled() {
        var observing = false
        while (true) {
            if (settings.recorderEnabled.first()) {
                if (!observing) {
                    observePlugState()
                    observing = true
                }
                settings.recorderEnabled.first { !it }
            }
            // The flag reads false right now, either on entry or because the suspend
            // above just returned. Try to stop.
            val startId = lastStartId
            if (startId == 0) {
                delay(STOP_RETRY_DELAY_MS)
                continue
            }
            if (stopSelfResult(startId)) return
            // Refused: a newer start is already in flight, which can only mean
            // recorderEnabled has already been written back to true by that start's
            // caller. Loop back to the top instead of exiting -- the check there will
            // see the true value, leave `observing` (and the already-running
            // observePlugState() collector, if one exists) alone, and suspend again
            // on the *next* disable rather than retrying this one.
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Re-validated independently of the watcher above, tied to this specific
        // startId: a start that lands just as the setting flips back off corrects
        // itself immediately, rather than waiting for the watcher's own next flag
        // read. A refusal here is harmless and left unhandled -- the watcher above is
        // the durable observer and will catch a genuine false reading on its own.
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
                    val id = openSession(SESSION_TYPE_CHARGE)
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
                            delay(CHARGE_SAMPLE_INTERVAL_MS)
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

                    // A discharge measures full capacity exactly as a charge does -- see
                    // `Mappers.toObservation` -- and until now every one of them was lived
                    // through and thrown away, leaving the estimate waiting on charges
                    // alone.
                    //
                    // Sampled far less often than a charge, because it is a different
                    // shape of event: a charge is minutes and its counter moves fast, a
                    // discharge is hours and moves slowly. Keeping the 5-second cadence
                    // here would write thousands of near-identical rows a day and spend
                    // the battery this app exists to measure, for resolution nothing uses.
                    val dischargeId = openSession(SESSION_TYPE_DISCHARGE)
                    if (dischargeId == null) {
                        Log.w(TAG, "Could not open a discharge session; will retry on the next transition")
                        return@onEach
                    }
                    sessionId = dischargeId
                    samplingJob = scope.launch {
                        while (true) {
                            sampleWriter.writeOne(sessionId = dischargeId)
                            delay(DISCHARGE_SAMPLE_INTERVAL_MS)
                        }
                    }
                }
            }
            .launchIn(scope)
    }

    private suspend fun openSession(type: String): Long? {
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
                type = type,
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
        detectAndPersistCurrentScale(
            startCounterUah = open.startCounterUah,
            endCounterUah = aggregate.endCounterUah,
            rawCurrentIntegral = aggregate.rawCurrentIntegral,
        )
    }

    /**
     * Cross-validates the device's CURRENT_NOW unit scale against this same session's own
     * charge-counter movement -- the measurement `BatteryManagerSource.currentUa()` prefers
     * over its own per-reading magnitude guess once one exists. Runs on every session close,
     * not just the first: the scale is a hardware/firmware characteristic that should not
     * change between charges, but re-confirming it is one cheap write, and it is what lets a
     * genuine change (an OS update touching the HAL) correct itself rather than being stuck
     * behind a stale answer forever.
     *
     * A counter that decreased over the session means the fuel gauge reset, not that charge
     * flowed backwards -- the same guard `Mappers.toObservation()` applies for the health
     * estimator's own counterDeltaUah, so a synthesised or reset counter cannot be mistaken
     * for validating evidence here either.
     */
    private suspend fun detectAndPersistCurrentScale(
        startCounterUah: Long?,
        endCounterUah: Long?,
        rawCurrentIntegral: Long?,
    ) {
        if (startCounterUah == null || endCounterUah == null || rawCurrentIntegral == null) return
        val counterDeltaUah = (endCounterUah - startCounterUah).takeIf { it > 0 } ?: return
        val scale = CurrentScaleDetector.fromCounterAgreement(rawCurrentIntegral, counterDeltaUah) ?: return
        settings.setCurrentScale(scale)
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
        /**
         * A charge is minutes long and its counter moves fast, so it is sampled densely:
         * the capacity measurement is built from counter deltas across it, and at a
         * coarser cadence those deltas are dominated by noise.
         */
        private const val CHARGE_SAMPLE_INTERVAL_MS = 5_000L

        /**
         * A discharge is hours long and moves slowly, so it is sampled at a twelfth of the
         * rate. The same 5-second cadence would write over seventeen thousand
         * near-identical rows a day and spend the battery this app exists to measure, for
         * resolution the estimate never uses -- only the endpoints of a session enter the
         * capacity calculation.
         */
        private const val DISCHARGE_SAMPLE_INTERVAL_MS = 60_000L
        private const val TAG = "ChargeRecorderService"

        /** Bounds the retry spin in [watchRecorderEnabled] for the startId-not-yet-set case. */
        private const val STOP_RETRY_DELAY_MS = 50L

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
         * `recorderEnabled` flag (see `watchRecorderEnabled()`) and calls
         * `stopSelfResult()` once that flag turns false, which cannot hit that race: by
         * the time it can observe `false`, this same service's `startForeground()` call
         * is necessarily long done.
         *
         * Catches `IllegalStateException`, not the API-31-only
         * `ForegroundServiceStartNotAllowedException`: on API 26-30, a disallowed
         * `startForegroundService()` throws plain `IllegalStateException` (the same
         * documented condition -- "the application is in a state where the service can
         * not be started" -- API 31 just gave it a dedicated subclass for easier
         * catching). `HealthViewModel.init` calls this after an async DataStore read, so
         * a user backgrounding the app inside that window on a pre-31 device hits this
         * for real; catching only the newer subtype would let that crash. Catching the
         * broad supertype is safe specifically here because this call site's only
         * documented reason to throw `IllegalStateException` at all, on any API level, is
         * this exact refusal -- it is not a catch-all masking some unrelated bug.
         */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, ChargeRecorderService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Charge recorder could not start", e)
            false
        }
    }
}

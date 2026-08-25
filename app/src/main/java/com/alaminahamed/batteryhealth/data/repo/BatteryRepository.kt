package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.alaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.alaminahamed.batteryhealth.data.local.SessionDao
import com.alaminahamed.batteryhealth.data.privileged.BatteryStatsCheckinParser
import com.alaminahamed.batteryhealth.data.privileged.DumpsysBatteryParser
import com.alaminahamed.batteryhealth.data.privileged.ParsedBatteryDump
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.domain.AppPowerEntry
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.domain.isActivelyCharging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single seam between data sources and the UI. Screens receive Readings and never
 * learn which tier produced them, which is what lets the privileged tier be added
 * without touching a composable.
 */
@Singleton
class BatteryRepository @Inject constructor(
    private val broadcasts: BatteryBroadcastSource,
    private val properties: BatteryManagerSource,
    private val sessionDao: SessionDao,
    private val estimator: HealthEstimator,
    private val designCapacity: DesignCapacityProvider,
    private val privileged: PrivilegedBatterySource,
) {
    /**
     * Every reason this app wants a fresh `dumpsys battery` beyond the bind-boundary
     * trigger [privilegedDump] already reacts to on its own -- see that function's own
     * doc for why boundary-only is right for ASOC, BSOH and first-use but wrong on its
     * own for the rest. [HealthViewModel][com.alaminahamed.batteryhealth.ui.health.HealthViewModel]
     * calls [retryPrivilegedDump] from every `ON_RESUME`, because Battery Protect's mode
     * and threshold are a live Samsung Settings toggle the user can flip while this app
     * is backgrounded, and nothing broadcasts that change back to this process (Critical
     * 2). The same call, exposed as a user-visible retry action, is also what stops one
     * failed attempt -- a transport error, a blank shell response -- from pinning
     * every privileged row at `NeedsPrivilegedAccess` until the connection state happens to toggle
     * on its own, which it may never do while the tier stays genuinely connected (Important 1).
     * Replay depth 1 so the very first collector still gets an initial tick without
     * waiting on a resume or a retry tap that may never come.
     */
    private val redumpRequests = MutableSharedFlow<Unit>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    ).apply { tryEmit(Unit) }

    /**
     * True only while [PrivilegedAvailability.Ready] and the most recent dump attempt
     * still came back empty -- never true merely because the tier is not connected at
     * all, which is an entirely different, already-explained state `UnlockCard` covers
     * on its own. Lets the Health screen show "the read failed, retry" instead of a
     * `NeedsPrivilegedAccess` row that would otherwise be indistinguishable from "the privileged
     * tier was never connected."
     */
    private val _privilegedDumpFailed = MutableStateFlow(false)
    val privilegedDumpFailed: StateFlow<Boolean> = _privilegedDumpFailed.asStateFlow()

    /**
     * The Apps screen's own equivalent of [privilegedDumpFailed], deliberately a second,
     * independent flag rather than folding a checkin-call failure into
     * [privilegedDumpFailed]: `dumpsys battery` and `dumpsys batterystats --checkin` are
     * two separate Binder calls that can fail independently of each other, and the Health
     * and Apps screens each own an `UnlockCard` instance describing only *their own*
     * privileged read. Collapsing the two into one flag would show Health's "read failed,
     * retry" card while only the Apps data was actually stale, or vice versa -- the exact
     * "one general signal standing in for two distinct failures" shape this codebase's
     * history keeps warning about.
     */
    private val _appPowerFailed = MutableStateFlow(false)
    val appPowerFailed: StateFlow<Boolean> = _appPowerFailed.asStateFlow()

    /**
     * True from the moment [appPower] observes the tier ready until that same shell call
     * resolves (success or failure) -- unlike every other privileged read in this
     * repository, a `dumpsys batterystats --checkin` call is not fast enough for its
     * caller to treat "no result yet" and "no result at all" as the same, one-frame-long
     * gap: it took 200-350ms for a live ~94KB capture and up to several seconds under
     * load on the device this was verified against, which is long enough for a naive UI
     * to flash `NeedsPrivilegedAccess` -- "connect the privileged tier, it might help" -- while
     * the tier is, in fact, already connected and simply still working on the answer. A separate
     * `StateFlow`, not folded into [appPower]'s own `Reading`, because [Reading] has no
     * "loading" case by design (see its own doc) and adding one there would blur every
     * other screen's absence vocabulary for a problem specific to this one call; the Apps
     * screen alone reads this to choose a skeleton over `ReadingSlot`'s ordinary
     * `NeedsPrivilegedAccess` rendering, precisely while there is not yet a real answer to render.
     */
    private val _appPowerLoading = MutableStateFlow(false)
    val appPowerLoading: StateFlow<Boolean> = _appPowerLoading.asStateFlow()

    /** See [redumpRequests]'s doc: a resume-triggered refresh and a user-facing retry
     * button both call this. */
    fun retryPrivilegedDump() {
        redumpRequests.tryEmit(Unit)
    }

    fun snapshots(): Flow<BatterySnapshot> = combine(
        broadcasts.broadcasts(),
        privilegedDump(),
    ) { broadcast, dump ->
        // A dump actually in hand (even one whose fields all came back null) is the
        // difference between "ask the user to connect the privileged tier, it might
        // help" and "this was tried with full shell privilege and the device still does
        // not have it" --
        // see privilegedReading's own doc for why that distinction is load-bearing, not
        // cosmetic.
        val dumpAvailable = dump != null
        BatterySnapshot(
            levelPct = broadcast.levelPct.asReading(),
            chargeState = Reading.Available(broadcast.chargeState, Source.Framework),
            plugType = Reading.Available(broadcast.plugType, Source.Framework),
            voltageMv = broadcast.voltageMv.asReading(),
            currentUa = properties.currentUa(isCharging = broadcast.chargeState.isActivelyCharging),
            temperatureDeciC = broadcast.temperatureDeciC.asReading(),
            technology = broadcast.technology.asReading(),
            chargeCounterUah = properties.chargeCounterUah(),
            cycleCount = CycleCountResolver.resolve(
                privilegedCycles = dump?.cycleCount,
                dumpAvailable = dumpAvailable,
                broadcastCycles = broadcast.cycleCount,
            ),
            stateOfHealthPct = dump?.asocPct.privilegedReading(dumpAvailable),
            firstUsageDateEpochDay = dump?.firstUseDateEpochDay.privilegedReading(dumpAvailable),
            // No key resembling a manufacturing date appears anywhere in the real dump
            // this app was built against (see DumpsysBatteryParser's doc) -- the
            // framework tier categorically cannot supply it (BATTERY_STATS,
            // @SystemApi/@hide) and the privileged tier's own dumpsys output, read in
            // full, simply does not carry it either. That is already known, unconditionally,
            // before the privileged tier is ever connected -- so this is `Unsupported` in
            // every state, connected or not, never `NeedsPrivilegedAccess`. Routing it through
            // `privilegedAbsence(dumpAvailable)` the way every other privileged field above
            // does would tell an unconnected user that connecting the tier might produce
            // this date, when the eleven lines above already prove it never will: a
            // known-false instruction, not merely an
            // optimistic one. See the task report for the "LLB CAL" line considered and
            // rejected as a substitute: it reads as a bootloader/firmware calibration
            // date, not this physical unit's manufacturing date, and nothing in the dump
            // labels it as the latter.
            manufacturingDateEpochDay = Reading.Unsupported,
            chargeTimeRemainingMs = properties.chargeTimeRemainingMs(),
            bsohPct = dump?.bsohPct.privilegedReading(dumpAvailable),
            protectBatteryModeEnabled = dump?.protectBatteryModeEnabled.privilegedReading(dumpAvailable),
            protectionThresholdPct = dump?.protectionThresholdPct.privilegedReading(dumpAvailable),
        )
    }

    /**
     * Re-dumps on the boundary into (or out of) [PrivilegedAvailability.Ready] -- not on
     * every broadcast this combines against: `dumpsys battery` is a shell spawn across a
     * privileged transport, and ASOC/BSOH/first-use, the three fields
     * this dump is the *only* source for, change on the order of firmware updates, not
     * seconds, so a five-second sampler cadence would buy nothing they change fast
     * enough to need -- and additionally on every [redumpRequests] tick, which is what
     * keeps Battery Protect's mode/threshold and a merely-transient dump failure from
     * being pinned to that same boundary-only cadence (see [redumpRequests]'s own doc).
     * The extra re-dumps this costs the three stable fields on a resume that changed
     * nothing are a real but small price -- one more subprocess spawn on a human-paced
     * event, not a hot loop -- for not needing a second, parallel dump flow.
     */
    private fun privilegedDump(): Flow<ParsedBatteryDump?> = privilegedTrigger()
        .map { ready ->
            if (!ready) {
                _privilegedDumpFailed.value = false
                null
            } else {
                // A lambda, not a bare method reference: `DumpsysBatteryParser.parse` now
                // takes a second, defaulted `todayEpochDay` parameter, and Kotlin callable
                // references do not pick up default arguments -- `DumpsysBatteryParser::parse`
                // would bind to the two-parameter overload and fail to satisfy `let`'s
                // `(String) -> R` here.
                val dump = privileged.dumpBattery()?.let { DumpsysBatteryParser.parse(it) }
                _privilegedDumpFailed.value = dump == null
                dump
            }
        }

    /**
     * The ready-boundary-or-redump-request trigger [privilegedDump] and [appPower] both
     * fetch a fresh shell read on -- pulled out once both needed it, rather than each
     * combining its own copy of the same two flows. This is the trigger being shared, not
     * the field-level absence semantics each caller derives from what comes back: see
     * [privilegedDump]'s and [appPower]'s own bodies for why those stay independent.
     */
    private fun privilegedTrigger(): Flow<Boolean> = combine(
        privileged.state.map { it is PrivilegedAvailability.Ready }.distinctUntilChanged(),
        redumpRequests,
    ) { ready, _ -> ready }

    /**
     * The Apps screen's own privileged read: `dumpsys batterystats --checkin`, parsed and
     * reduced to per-uid power. Mirrors [privilegedDump]'s not-ready-vs-ready-but-failed
     * handling deliberately (both are "no dump to parse" from a shell call that can fail
     * independently of whether the tier is ready), but does **not** mirror its
     * dumpAvailable-then-Unsupported field-level pattern, because there is no equivalent
     * of "this Samsung-only field is genuinely absent from an otherwise-successful dump"
     * at the whole-list level here -- every Android device has a `batterystats` system, so
     * a successful call that simply found no `pwi,uid` rows (e.g. right after the device's
     * own history was cleared, or a --checkin format this parser does not recognise) is a
     * real, honestly-reportable fact (`Available(emptyList())`), not an absence. Only a
     * failed call (ready but `dumpBatteryStatsCheckin()` returned `null`) reports
     * [Reading.NeedsPrivilegedAccess] -- the same "this might still work, retry" signal
     * [privilegedDump] gives a failed `dumpBattery()` call, for the same reason: a shell
     * call failing while ready reads exactly like never having been ready at all, and the
     * existing [retryPrivilegedDump] flow is what tells the two apart for the user (via
     * [appPowerFailed]).
     */
    fun appPower(): Flow<Reading<List<AppPowerEntry>>> = privilegedTrigger()
        .map { ready ->
            if (!ready) {
                _appPowerLoading.value = false
                _appPowerFailed.value = false
                Reading.NeedsPrivilegedAccess
            } else {
                _appPowerLoading.value = true
                val checkin = privileged.dumpBatteryStatsCheckin()
                _appPowerFailed.value = checkin == null
                // Cleared after dumpBatteryStatsCheckin() returns, not in a `finally`:
                // every path below is a plain value, nothing here can throw past the
                // suspend call above, so a `finally` would only add ceremony without
                // covering a real failure mode this simpler shape misses.
                _appPowerLoading.value = false
                if (checkin == null) {
                    Reading.NeedsPrivilegedAccess
                } else {
                    val entries = AppPowerAggregator.aggregate(BatteryStatsCheckinParser.parse(checkin))
                    Reading.Available(entries, Source.Privileged)
                }
            }
        }

    /**
     * `null` means two different things depending on [dumpAvailable], and only this
     * function is allowed to collapse that ambiguity into a `Reading`:
     *
     * - No dump was ever obtained ([dumpAvailable] false: the privileged tier is not
     *   ready, or the shell-side call itself failed) -- [Reading.NeedsPrivilegedAccess].
     *   Connecting/restoring the privileged tier might still produce this value; nothing
     *   has ruled it out yet.
     * - A real dump was parsed and this specific field's regex still found nothing in it
     *   ([dumpAvailable] true) -- [Reading.Unsupported]. Full shell privilege was
     *   already in hand and the number still was not there, so promising the user that
     *   connecting the tier would fix it would be false.
     */
    private fun <T> T?.privilegedReading(dumpAvailable: Boolean): Reading<T> = when {
        this != null -> Reading.Available(this, Source.Privileged)
        else -> privilegedAbsence(dumpAvailable)
    }

    private fun privilegedAbsence(dumpAvailable: Boolean): Reading<Nothing> =
        if (dumpAvailable) Reading.Unsupported else Reading.NeedsPrivilegedAccess

    fun measuredHealth(): Flow<Reading<HealthReport>> = combine(
        // Filtered to charge sessions in SQL, not after the LIMIT: the window must be
        // spent on rows that can actually qualify. A recency window across both session
        // types burns half its rows on discharges before Kotlin ever sees them, which can
        // push genuinely qualifying charges outside the window forever for a user whose
        // recent charges are frequent top-ups.
        sessionDao.observeCompletedSessionsOfType(SESSION_TYPE_CHARGE, limit = SESSION_WINDOW),
        designCapacity.designCapacityMah,
    ) { sessions, designMah ->
        val observations = sessions
            .mapNotNull { it.toDomain() }
            .map { it.toObservation() }
        estimator.estimate(observations, designMah)
    }

    /** A present framework value is Framework-sourced; absence is Unsupported, never zero. */
    private fun <T> T?.asReading(): Reading<T> =
        if (this == null) Reading.Unsupported else Reading.Available(this, Source.Framework)

    private companion object {
        const val SESSION_WINDOW = 50
    }
}

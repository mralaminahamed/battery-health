package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.data.privileged.DumpsysBatteryParser
import com.mralaminahamed.batteryhealth.data.privileged.ParsedBatteryDump
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuAvailability
import com.mralaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.domain.isActivelyCharging
import kotlinx.coroutines.flow.Flow
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
    private val shizuku: PrivilegedBatterySource,
) {
    fun snapshots(): Flow<BatterySnapshot> = combine(
        broadcasts.broadcasts(),
        privilegedDump(),
    ) { broadcast, dump ->
        // A dump actually in hand (even one whose fields all came back null) is the
        // difference between "ask the user to grant Shizuku, it might help" and "this
        // was tried with full shell privilege and the device still does not have it" --
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
            cycleCount = broadcast.cycleCount.asReading(),
            stateOfHealthPct = dump?.asocPct.privilegedReading(dumpAvailable),
            firstUsageDateEpochDay = dump?.firstUseDateEpochDay.privilegedReading(dumpAvailable),
            // No key resembling a manufacturing date appears anywhere in the real dump
            // this app was built against (see DumpsysBatteryParser's doc) -- the
            // framework tier categorically cannot supply it (BATTERY_STATS,
            // @SystemApi/@hide) and the privileged tier's own dumpsys output, read in
            // full, simply does not carry it either. So this stays NeedsShizuku only
            // until Shizuku is actually bound and dumped once, and Unsupported after --
            // never a fabricated date, and never a false promise that granting
            // permission would produce one once that promise is already known to be
            // empty. See the task report for the "LLB CAL" line considered and rejected
            // as a substitute: it reads as a bootloader/firmware calibration date, not
            // this physical unit's manufacturing date, and nothing in the dump labels it
            // as the latter.
            manufacturingDateEpochDay = privilegedAbsence(dumpAvailable),
            chargeTimeRemainingMs = properties.chargeTimeRemainingMs(),
            bsohPct = dump?.bsohPct.privilegedReading(dumpAvailable),
            protectBatteryModeEnabled = dump?.protectBatteryModeEnabled.privilegedReading(dumpAvailable),
            protectionThresholdPct = dump?.protectionThresholdPct.privilegedReading(dumpAvailable),
        )
    }

    /**
     * Re-dumps only on the boundary into (or out of) [ShizukuAvailability.Bound], not on
     * every broadcast this combines against: `dumpsys battery` is a subprocess spawn
     * across a Binder call into the shell UID, and every field it carries here changes
     * on the order of firmware updates, not seconds -- running it on a five-second
     * sampler cadence would buy nothing this data ever changes fast enough to need.
     */
    private fun privilegedDump(): Flow<ParsedBatteryDump?> = shizuku.state
        .map { it is ShizukuAvailability.Bound }
        .distinctUntilChanged()
        .map { bound -> if (bound) shizuku.dumpBattery()?.let(DumpsysBatteryParser::parse) else null }

    /**
     * `null` means two different things depending on [dumpAvailable], and only this
     * function is allowed to collapse that ambiguity into a `Reading`:
     *
     * - No dump was ever obtained ([dumpAvailable] false: Shizuku is not bound, or the
     *   shell-side call itself failed) -- [Reading.NeedsShizuku]. Granting/restoring the
     *   privileged tier might still produce this value; nothing has ruled it out yet.
     * - A real dump was parsed and this specific field's regex still found nothing in it
     *   ([dumpAvailable] true) -- [Reading.Unsupported]. Full shell privilege was
     *   already in hand and the number still was not there, so promising the user that
     *   Shizuku would fix it would be false.
     */
    private fun <T> T?.privilegedReading(dumpAvailable: Boolean): Reading<T> = when {
        this != null -> Reading.Available(this, Source.Privileged)
        else -> privilegedAbsence(dumpAvailable)
    }

    private fun privilegedAbsence(dumpAvailable: Boolean): Reading<Nothing> =
        if (dumpAvailable) Reading.Unsupported else Reading.NeedsShizuku

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

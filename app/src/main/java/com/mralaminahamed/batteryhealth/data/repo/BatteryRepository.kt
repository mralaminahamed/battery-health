package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import com.mralaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.SessionType
import com.mralaminahamed.batteryhealth.domain.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
) {
    fun snapshots(): Flow<BatterySnapshot> = broadcasts.broadcasts().map { broadcast ->
        BatterySnapshot(
            levelPct = broadcast.levelPct.asReading(),
            chargeState = Reading.Available(broadcast.chargeState, Source.Framework),
            plugType = Reading.Available(broadcast.plugType, Source.Framework),
            voltageMv = broadcast.voltageMv.asReading(),
            currentUa = properties.currentUa(),
            temperatureDeciC = broadcast.temperatureDeciC.asReading(),
            technology = broadcast.technology.asReading(),
            chargeCounterUah = properties.chargeCounterUah(),
            cycleCount = broadcast.cycleCount.asReading(),
            // Gated behind signature-level BATTERY_STATS, so unreachable here by design.
            // The privileged tier reads these from dumpsys; saying "needs Shizuku" is true,
            // where "unsupported on this device" would not be.
            stateOfHealthPct = Reading.NeedsShizuku,
            firstUsageDateEpochDay = Reading.NeedsShizuku,
            manufacturingDateEpochDay = Reading.NeedsShizuku,
            chargeTimeRemainingMs = properties.chargeTimeRemainingMs(),
        )
    }

    fun measuredHealth(): Flow<Reading<HealthReport>> = combine(
        sessionDao.observeCompletedSessions(limit = SESSION_WINDOW),
        designCapacity.designCapacityMah,
    ) { sessions, designMah ->
        val observations = sessions
            .mapNotNull { it.toDomain() }
            .filter { it.type == SessionType.Charge }
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

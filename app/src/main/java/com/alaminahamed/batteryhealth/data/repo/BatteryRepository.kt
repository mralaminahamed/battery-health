package com.alaminahamed.batteryhealth.data.repo

import android.os.BatteryManager
import com.alaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.alaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.alaminahamed.batteryhealth.data.local.SessionDao
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.framework.PowerManagerSource
import com.alaminahamed.batteryhealth.data.vendor.VendorReadings
import com.alaminahamed.batteryhealth.data.vendor.discovery.FrameworkStateOfHealth
import com.alaminahamed.batteryhealth.domain.BatterySnapshot
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import com.alaminahamed.batteryhealth.domain.isActivelyCharging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single seam between data sources and the UI. Screens receive Readings and never
 * learn which source produced them.
 *
 * This used to also front a privileged tier -- an on-device adb client or a `su` shell,
 * unlocking `dumpsys battery`/`dumpsys batterystats --checkin` for state of health, both
 * dates, BSOH, Battery Protect's fields and per-app power. That tier is gone: this app now
 * asks for nothing beyond what a normal Android permission flow can grant, and neither adb
 * nor root is either. Every field that had no source but that tier now reports
 * [Reading.Unsupported] rather than [Reading.NeedsPrivilegedAccess] -- there is no
 * transport left for the user to connect, so promising one would be dishonest. See the
 * task report for exactly which fields lost their only source and what that costs.
 */
@Singleton
class BatteryRepository @Inject constructor(
    private val broadcasts: BatteryBroadcastSource,
    private val properties: BatteryManagerSource,
    private val sessionDao: SessionDao,
    private val estimator: HealthEstimator,
    private val designCapacity: DesignCapacityProvider,
    private val vendorSettings: VendorReadings,
    private val power: PowerManagerSource,
    private val batteryManager: BatteryManager,
) {
    /**
     * This app's own cycle count, from charge it recorded going in.
     *
     * Unwindowed on purpose -- see `SessionDao.observeChargeAddedUah`. Combined with the
     * design capacity because a cycle is meaningless without one, and refused rather than
     * guessed when that is unknown.
     */
    private fun measuredCycleCount(): Flow<Reading<Int>> = combine(
        sessionDao.observeChargeAddedUah(SESSION_TYPE_CHARGE),
        designCapacity.designCapacityMah,
    ) { chargedUah, designMah ->
        MeasuredCycles.fromSessions(chargedUah, designMah)
    }

    fun snapshots(): Flow<BatterySnapshot> = combine(
        broadcasts.broadcasts(),
        measuredCycleCount(),
    ) { broadcast, measuredCycles ->
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
                broadcastCycles = broadcast.cycleCount,
                measured = measuredCycles,
            ),
            // AOSP checks `stateOfHealthPublic()` before enforcing BATTERY_STATS on this
            // one property, so on a device/build with that flag on, this reads with no
            // permission of any kind -- see FrameworkStateOfHealth's own doc. Where the
            // flag is off, this throws SecurityException and FrameworkStateOfHealth maps
            // that to NeedsPrivilegedAccess, describing the platform's own gate rather
            // than anything this app can still offer to unlock.
            stateOfHealthPct = FrameworkStateOfHealth.read { id -> batteryManager.getIntProperty(id) },
            // BATTERY_PROPERTY_FIRST_USAGE_DATE and BATTERY_PROPERTY_MANUFACTURING_DATE
            // have no equivalent public-exception flag -- they are unconditionally gated
            // behind BATTERY_STATS, which this app can no longer be granted at any price.
            // Unsupported, not NeedsPrivilegedAccess: there is nothing left to unlock.
            firstUsageDateEpochDay = Reading.Unsupported,
            manufacturingDateEpochDay = Reading.Unsupported,
            chargeTimeRemainingMs = properties.chargeTimeRemainingMs(),
            // Samsung's own second health figure, read only from `dumpsys battery` over
            // the now-removed shell tier. No public API exposes it, so this is a
            // permanent Unsupported rather than a promise this app can no longer keep.
            bsohPct = Reading.Unsupported,
            // The vendor's own Settings key, unprivileged and unconditional -- no shell
            // fallback needed or possible any more.
            protectBatteryModeEnabled = vendorSettings.batteryProtectEnabled(),
            protectionThresholdPct = vendorSettings.batteryProtectThresholdPct(),
            // Public API, no permission, present on every device -- so these are populated
            // unconditionally rather than behind any tier. Each is API-gated inside
            // PowerManagerSource and reports Unsupported below its floor.
            thermalStatus = power.thermalStatus(),
            dischargePredictionMs = power.dischargePredictionMs(),
        )
    }

    fun measuredHealth(): Flow<Reading<HealthReport>> = combine(
        // Each direction gets its own window rather than the two sharing one.
        //
        // A single recency window across both types burns rows on whichever direction
        // happened more recently, which for a user who tops up often means charges could
        // push every discharge out of view (or the reverse on a phone left off the
        // charger for days). Separate windows mean neither direction can starve the other,
        // and both are still filtered in SQL rather than after the LIMIT, so a window is
        // never spent on rows that cannot qualify.
        sessionDao.observeCompletedSessionsOfType(SESSION_TYPE_CHARGE, limit = SESSION_WINDOW),
        sessionDao.observeCompletedSessionsOfType(SESSION_TYPE_DISCHARGE, limit = SESSION_WINDOW),
        designCapacity.designCapacityMah,
    ) { charges, discharges, designMah ->
        // Both directions measure the same quantity -- see `toObservation`, which is where
        // they are made comparable -- so the estimator sees one pooled list and its
        // MIN_SESSIONS requirement is satisfied by independent sessions of either kind.
        // That roughly halves the wait for a first reading on a phone that is charged
        // infrequently.
        val observations = (charges + discharges)
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

package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.StateFlow

/**
 * What [BatteryRepository][com.mralaminahamed.batteryhealth.data.repo.BatteryRepository]
 * and the Health screen actually depend on, kept separate from the concrete
 * [ShizukuGateway] so both can be exercised without the real Shizuku singleton: an
 * instrumented test can inject a fake that always reports [ShizukuAvailability.NotInstalled]
 * regardless of whether the physical test device happens to have Shizuku installed and
 * running (see `BatteryRepositoryTest`), and this app's own compile-time dependency graph
 * has exactly one place that could ever call into Shizuku's static API.
 */
interface PrivilegedBatterySource {

    val state: StateFlow<ShizukuAvailability>

    /** `dumpsys battery`, or `null` if the tier is not [ShizukuAvailability.Bound] or the
     * call itself failed. Never throws. */
    suspend fun dumpBattery(): String?

    /** `dumpsys batterystats --checkin` -- [BatteryStatsCheckinParser]'s own input, and
     * the source of the Apps screen's per-uid power attribution. Same `null`-on-not-bound-
     * or-failed contract as [dumpBattery]; a distinct method because the two commands are
     * independent shell calls with independent timeouts and independent parsers, not one
     * dump with a second, optional shape. Never throws. */
    suspend fun dumpBatteryStatsCheckin(): String?

    /** Shows Shizuku's own permission prompt. A no-op unless [state] is already
     * [ShizukuAvailability.PermissionNotGranted] -- calling it from any other state
     * would either do nothing useful (already bound) or nothing possible (not
     * installed/running yet). */
    fun requestPermission()

    /**
     * Re-checks the one fact [state] cannot otherwise learn about on its own: whether
     * the Shizuku package is installed. Binder and permission changes reach [state] live
     * through Shizuku's own listeners without this being called, but installing the
     * separate Shizuku app while this app is already running produces no broadcast this
     * app listens for, so the Health screen calls this again on every resume.
     */
    fun refresh()
}

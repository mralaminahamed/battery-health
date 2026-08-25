package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.StateFlow

/**
 * What [BatteryRepository][com.mralaminahamed.batteryhealth.data.repo.BatteryRepository],
 * the ViewModels and the Health screen actually depend on, kept separate from the concrete
 * [AdbGateway] so all of them can be exercised without either real transport: an
 * instrumented test can inject a fake that always reports [PrivilegedAvailability.Unavailable]
 * regardless of whether the physical test device happens to have adb debugging enabled or
 * root granted, and this app's own compile-time dependency graph has exactly one place
 * that could ever dial a transport socket or shell out to `su`.
 */
interface PrivilegedBatterySource {

    val state: StateFlow<PrivilegedAvailability>

    /** `dumpsys battery`, or `null` if [state] is not currently [PrivilegedAvailability.Ready]
     * or the call itself failed. Never throws. */
    suspend fun dumpBattery(): String?

    /** `dumpsys batterystats --checkin` -- [BatteryStatsCheckinParser]'s own input, and
     * the source of the Apps screen's per-uid power attribution. Same `null`-on-not-ready-
     * or-failed contract as [dumpBattery]; a distinct method because the two commands are
     * independent shell calls with independent timeouts and independent parsers, not one
     * dump with a second, optional shape. Never throws. */
    suspend fun dumpBatteryStatsCheckin(): String?

    /**
     * Establishes whichever transport(s) this app is configured to use -- see
     * [AdbGateway.connect]'s own doc for exactly which transports that means and when.
     * There is no single "the" dialog this always raises: it may dial adb's loopback
     * socket silently, surface adb's wireless-pairing prompt, raise Magisk's root grant
     * dialog, or do nothing at all, depending on [state] and on facts this interface does
     * not expose. Safe to call repeatedly -- both transports guard themselves against
     * overlapping calls.
     */
    suspend fun connect()

    /**
     * Re-checks or re-establishes both transports -- called on every Health-screen
     * `ON_RESUME`. Deliberately does not loop or schedule itself: a background reconnect
     * loop draining battery inside a battery-health app is the one failure mode this
     * design guards against hardest, so retrying is this method's caller's job, not this
     * method's own.
     */
    fun refresh()
}

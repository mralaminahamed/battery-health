package com.mralaminahamed.batteryhealth.data.privileged

import kotlinx.coroutines.flow.StateFlow

/**
 * `dumpsys battery`'s own textual dump -- [DumpsysBatteryParser]'s input. Small (~11KB on
 * the fixture this app was verified against) and fast, which is why its transport-side
 * timeout is the short one; see the two transports' own timeout constants for the numbers.
 */
const val CMD_DUMP_BATTERY = "dumpsys battery"

/**
 * `dumpsys batterystats --checkin` -- [BatteryStatsCheckinParser]'s input, and the source
 * of the Apps screen's per-uid power attribution. Roughly 50x [CMD_DUMP_BATTERY]'s payload
 * on the same device, which is why it gets the longer of the two transport-side timeouts.
 */
const val CMD_DUMP_CHECKIN = "dumpsys batterystats --checkin"

/**
 * The seam `AdbGateway` fronts. Two transports implement this -- `AdbShell` over a
 * hand-rolled ADB client, `RootShell` over `su` -- and everything above this interface
 * (the repository, the ViewModel, the Health screen) depends on it rather than on either
 * concrete class, exactly as it depends on [PrivilegedBatterySource] rather than
 * `AdbGateway` directly.
 *
 * **This interface is the allowlist.** There is deliberately no method that takes a
 * command string -- [CMD_DUMP_BATTERY] and [CMD_DUMP_CHECKIN] are the only two strings
 * that are ever handed to a shell by this app, and adding a third method (even one meant
 * only for debugging) would widen that allowlist. Do not add one.
 */
interface PrivilegedShell {

    val state: StateFlow<TransportState>

    /** [CMD_DUMP_BATTERY]'s output, or `null` if the transport is not [TransportState.Ready]
     * or the call itself failed. Never throws. */
    suspend fun runDump(): String?

    /** [CMD_DUMP_CHECKIN]'s output. Same `null`-on-not-ready-or-failed contract as
     * [runDump]; a distinct method because the two commands are independent shell calls
     * with independent timeouts, not one dump with a second, optional shape. Never throws. */
    suspend fun runCheckin(): String?

    /**
     * Establishes (or re-establishes) this transport. For `AdbShell` this dials the
     * loopback socket and runs the ADB auth handshake; for `RootShell` this runs the `su`
     * probe that raises Magisk's own grant dialog. Both transports guard this against
     * overlapping calls, so issuing it repeatedly (e.g. from [refresh]) is safe.
     */
    suspend fun connect()

    /** Re-checks or re-establishes this transport, the way [PrivilegedBatterySource]'s own
     * `refresh()` re-checks both transports -- called on every Health-screen `ON_RESUME`.
     * What "re-check" means is transport-specific; see each implementation. */
    fun refresh()
}

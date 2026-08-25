package com.alaminahamed.batteryhealth.ui.apps

import com.alaminahamed.batteryhealth.data.apps.AppRow
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.domain.Reading

data class AppsUiState(
    /** Drives `UnlockCard`, reused as-is from the Health screen rather than a second
     * unlock affordance -- see `AppsScreen`'s own doc. Defaults to
     * [PrivilegedAvailability.Unavailable] only as the cold-start placeholder before
     * `AdbGateway`'s real state first emits, the same convention `HealthUiState`
     * already uses. */
    val privilegedAvailability: PrivilegedAvailability = PrivilegedAvailability.Unavailable,
    /** [Reading.NeedsPrivilegedAccess] until ready, then either the classified, sorted rows or --
     * see `BatteryRepository.appPower`'s own doc -- [Reading.NeedsPrivilegedAccess] again if the
     * checkin call itself failed while ready. Never [Reading.Unsupported] or
     * [Reading.NotYetMeasured]: every Android device has a `batterystats` system, and
     * this is a live privileged read, not a measurement gathered over time. */
    val rows: Reading<List<AppRow>> = Reading.NeedsPrivilegedAccess,
    /** True only while [privilegedAvailability] is [PrivilegedAvailability.Ready] and the
     * most recent checkin attempt still came back empty -- see
     * `BatteryRepository.appPowerFailed`'s own doc. Drives `UnlockCard`'s "read failed,
     * retry" case for this screen specifically, independent of the Health screen's own
     * `privilegedDumpFailed`. */
    val appPowerFailed: Boolean = false,
    /** See `BatteryRepository.appPowerLoading`'s own doc: true while a checkin call is
     * actually in flight. `AppsScreen` reads this alongside [rows] rather than instead of
     * it -- see that screen's own doc for why a skeleton only replaces [rows]'s ordinary
     * rendering when there is no [Reading.Available] result already in hand. */
    val isLoading: Boolean = false,
)

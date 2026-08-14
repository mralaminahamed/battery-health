package com.mralaminahamed.batteryhealth.ui.apps

import com.mralaminahamed.batteryhealth.data.apps.AppRow
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuAvailability
import com.mralaminahamed.batteryhealth.domain.Reading

data class AppsUiState(
    /** Drives `UnlockCard`, reused as-is from the Health screen rather than a second
     * unlock affordance -- see `AppsScreen`'s own doc. Defaults to
     * [ShizukuAvailability.NotInstalled] only as the cold-start placeholder before
     * `ShizukuGateway`'s real state first emits, the same convention `HealthUiState`
     * already uses. */
    val shizukuAvailability: ShizukuAvailability = ShizukuAvailability.NotInstalled,
    /** [Reading.NeedsShizuku] until bound, then either the classified, sorted rows or --
     * see `BatteryRepository.appPower`'s own doc -- [Reading.NeedsShizuku] again if the
     * checkin call itself failed while bound. Never [Reading.Unsupported] or
     * [Reading.NotYetMeasured]: every Android device has a `batterystats` system, and
     * this is a live privileged read, not a measurement gathered over time. */
    val rows: Reading<List<AppRow>> = Reading.NeedsShizuku,
    /** True only while [shizukuAvailability] is [ShizukuAvailability.Bound] and the most
     * recent checkin attempt still came back empty -- see
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

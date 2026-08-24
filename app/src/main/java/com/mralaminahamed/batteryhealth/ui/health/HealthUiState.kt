package com.mralaminahamed.batteryhealth.ui.health

import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.mralaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.map

data class HealthUiState(
    val snapshot: BatterySnapshot?,
    val measured: Reading<HealthReport>,
    /** Mirrors `SettingsStore.recorderEnabled`; opt-in, defaults off. */
    val recorderEnabled: Boolean = false,
    /**
     * True only right after an enable attempt was refused (e.g.
     * `ForegroundServiceStartNotAllowedException`). `recorderEnabled` can still read
     * true in that case -- the flag records intent, not whether the service is
     * currently running -- so this is what lets the screen say "on, but not actually
     * recording" instead of silently agreeing with the flag.
     */
    val recorderStartFailed: Boolean = false,
    /** True once the user has denied POST_NOTIFICATIONS (API 33+) for this session. */
    val notificationsDenied: Boolean = false,
    /**
     * The design capacity `HealthEstimator` is actually measuring against, and whether
     * that came from the model table or the user's override. Defaults to
     * [EffectiveDesignCapacity.None] only as the cold-start placeholder before the real
     * flow first emits -- production always replaces it immediately.
     */
    val designCapacity: EffectiveDesignCapacity = EffectiveDesignCapacity.None,
    /**
     * Drives [UnlockCard][com.mralaminahamed.batteryhealth.ui.components.UnlockCard].
     * Defaults to [PrivilegedAvailability.Unavailable] only as the cold-start placeholder
     * before `AdbGateway`'s real state first emits -- the same convention
     * [designCapacity] already uses above.
     */
    val privilegedAvailability: PrivilegedAvailability = PrivilegedAvailability.Unavailable,
    /**
     * True only while [privilegedAvailability] is [PrivilegedAvailability.Ready] and the
     * most recent privileged dump attempt still came back empty -- see
     * `BatteryRepository.privilegedDumpFailed`'s own doc. Drives `UnlockCard`'s
     * otherwise-unreachable "Ready but the read failed, retry" case.
     */
    val privilegedDumpFailed: Boolean = false,
) {
    /**
     * A value the platform reports directly beats one this app inferred, but beyond
     * that, absence is not all the same kind of absence. This used to be moot in
     * practice: `BatteryRepository` set `stateOfHealthPct` to `NeedsShizuku`
     * unconditionally (this device's own unprivileged `BatteryManager` can never report
     * it, signature-permission-gated), so `framework is Reading.Available` was never
     * true and every other case fell straight through to `measured` alone. That is no
     * longer true now that the privileged tier exists: once it is connected, a real
     * dump makes `framework` genuinely `Reading.Available` (Samsung's ASOC, sourced
     * `Privileged`), and this precedence is what a real device now exercises rather than
     * only a hypothetical one. Before that fix landed, the fallthrough also silently
     * discarded `NeedsShizuku` whenever `measured` was `Unsupported` — nearly every
     * Samsung model, since the design-capacity table has ten entries and no override UI
     * — and rendered "Not available on this device" one row above a "Needs Shizuku" line
     * about the exact same underlying data. Explicit precedence fixes both: Available
     * beats NotYetMeasured beats NeedsShizuku beats Unsupported, so a real measurement
     * still wins when one exists, and a `NeedsShizuku` is never displaced by a plain
     * `Unsupported`.
     */
    val headlinePct: Reading<Int>
        get() {
            val framework = snapshot?.stateOfHealthPct ?: Reading.Unsupported
            val measuredPct = measured.map { it.healthPct }
            return if (framework.precedence() >= measuredPct.precedence()) framework else measuredPct
        }

    private fun Reading<*>.precedence(): Int = when (this) {
        is Reading.Available -> 3
        Reading.NotYetMeasured -> 2
        Reading.NeedsShizuku -> 1
        Reading.Unsupported -> 0
    }
}

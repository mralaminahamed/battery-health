package com.mralaminahamed.batteryhealth.ui.health

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
) {
    /**
     * A value the platform reports directly beats one this app inferred. When neither
     * exists, the measurement's own reason is preserved so the UI can say why — falling
     * back to a generic "unavailable" would discard the distinction between "this device
     * cannot" and "not enough charges yet".
     */
    val headlinePct: Reading<Int>
        get() {
            val framework = snapshot?.stateOfHealthPct
            if (framework is Reading.Available) return framework
            return measured.map { it.healthPct }
        }
}

package com.mralaminahamed.batteryhealth.ui.health

import com.mralaminahamed.batteryhealth.domain.BatterySnapshot
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.map

data class HealthUiState(
    val snapshot: BatterySnapshot?,
    val measured: Reading<HealthReport>,
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

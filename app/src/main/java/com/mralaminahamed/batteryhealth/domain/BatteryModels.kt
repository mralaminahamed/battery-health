package com.mralaminahamed.batteryhealth.domain

enum class SessionType { Charge, Discharge }

enum class CapacityMethod { Counter, Coulomb }

enum class ChargeState { Charging, Discharging, Full, NotCharging, Unknown }

enum class PlugType { None, Ac, Usb, Wireless, Dock }

enum class HealthBand {
    Good, Fair, Poor;

    companion object {
        fun of(percent: Int): HealthBand = when {
            percent >= 80 -> Good
            percent >= 65 -> Fair
            else -> Poor
        }
    }
}

/** One instant of battery state, every field independently available or not. */
data class BatterySnapshot(
    val levelPct: Reading<Int>,
    val chargeState: Reading<ChargeState>,
    val plugType: Reading<PlugType>,
    val voltageMv: Reading<Int>,
    val currentUa: Reading<Int>,
    val temperatureDeciC: Reading<Int>,
    val technology: Reading<String>,
    val chargeCounterUah: Reading<Long>,
    val cycleCount: Reading<Int>,
    val stateOfHealthPct: Reading<Int>,
    val firstUsageDateEpochDay: Reading<Long>,
    val manufacturingDateEpochDay: Reading<Long>,
    val chargeTimeRemainingMs: Reading<Long>,
) {
    /** Instantaneous power in milliwatts, present only when both inputs are. */
    val milliwatts: Reading<Int>
        get() {
            val v = voltageMv.valueOrNull() ?: return Reading.Unsupported
            val i = currentUa.valueOrNull() ?: return Reading.Unsupported
            return Reading.Available((v.toLong() * i / 1_000_000L).toInt(), Source.Framework)
        }
}

data class HealthReport(
    val healthPct: Int,
    val measuredFullUah: Long,
    val designCapacityMah: Int,
    val method: CapacityMethod,
    val sessionsUsed: Int,
) {
    val band: HealthBand get() = HealthBand.of(healthPct)
    val measuredFullMah: Int get() = (measuredFullUah / 1000L).toInt()
}

/** A completed charge or discharge session as stored and displayed. */
data class ChargeSession(
    val id: Long,
    val type: SessionType,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val startLevelPct: Int,
    val endLevelPct: Int,
    val startCounterUah: Long?,
    val endCounterUah: Long?,
    val peakTempDeciC: Int?,
    val avgMilliwatts: Int?,
    val screenOnMs: Long,
) {
    val durationMs: Long get() = endedAtMs - startedAtMs
    val deltaLevelPct: Int get() = endLevelPct - startLevelPct
}

/** The estimator's input: one session reduced to what the capacity math needs. */
data class CapacityObservation(
    val sessionId: Long,
    val deltaLevelPct: Int,
    val counterDeltaUah: Long?,
    val coulombUah: Long?,
)

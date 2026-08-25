package com.alaminahamed.batteryhealth.domain

enum class SessionType { Charge, Discharge }

enum class CapacityMethod { Counter, Coulomb }

enum class ChargeState { Charging, Discharging, Full, NotCharging, Unknown }

/**
 * Whether the device is actively pushing current into the battery right now, as
 * distinct from merely being plugged in. [ChargeState.Full] is deliberately excluded:
 * a battery topped off at Full draws only a trickle, if anything, even though the
 * charger is still connected -- treating it as "charging" would let a near-zero current
 * masquerade as the substantial one a real charge implies.
 *
 * This is the one signal specific enough to let
 * `CurrentScaleDetector.fromMagnitude` resolve its otherwise-ambiguous mid-range band --
 * see that function's own doc for why a genuine charging current rules out one of its
 * two unit hypotheses, while a merely-plugged-in Full reading does not.
 */
val ChargeState.isActivelyCharging: Boolean get() = this == ChargeState.Charging

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
    /**
     * Samsung's `mSavedBatteryBsoh` -- a second, independent privileged health figure,
     * deliberately kept apart from [stateOfHealthPct] (Samsung's ASOC). The two can and
     * do disagree on real hardware (86 vs 95 on the device this was verified against);
     * folding one into the other would hide that Samsung itself tracks two numbers.
     * Defaults to [Reading.NeedsPrivilegedAccess] so every call site written before this field
     * existed keeps compiling and keeps its honest meaning without being touched.
     */
    val bsohPct: Reading<Int> = Reading.NeedsPrivilegedAccess,
    /** Samsung Battery Protect's on/off state, from `mProtectBatteryMode`. */
    val protectBatteryModeEnabled: Reading<Boolean> = Reading.NeedsPrivilegedAccess,
    /** The charge percentage Battery Protect caps charging at when enabled, from
     * `mProtectionThreshold`. */
    val protectionThresholdPct: Reading<Int> = Reading.NeedsPrivilegedAccess,
) {
    /**
     * Instantaneous power in milliwatts, derived from voltage (mV) and current (µA).
     *
     * Absent inputs propagate their own reason rather than collapsing to Unsupported:
     * a caller must be able to tell "this device cannot report power" from "connecting
     * the privileged tier would report it". Provenance is the least direct of the two
     * inputs, so a derived number never claims to be more directly measured than its
     * weakest input.
     */
    val milliwatts: Reading<Int>
        get() {
            if (voltageMv !is Reading.Available) return voltageMv
            if (currentUa !is Reading.Available) return currentUa
            val milliwatts = (voltageMv.value.toLong() * currentUa.value / 1_000_000L).toInt()
            return Reading.Available(milliwatts, leastDirectOf(voltageMv.source, currentUa.source))
        }
}

/**
 * How directly a source observed its value. Framework is the most direct claim (read
 * straight from the platform), then Privileged (read through a shell), then Measured
 * (the app derived it).
 *
 * Exhaustive over [Source] deliberately. The rule this replaced ended in
 * `else -> Source.Framework`, so a new provenance value would have compiled cleanly and
 * been labelled with the most authoritative source this app has. Adding one now fails
 * the build here instead, which is the only place that check can live: no test can
 * observe an enum value nobody has written yet.
 */
private val Source.directness: Int
    get() = when (this) {
        Source.Framework -> 0
        Source.Privileged -> 1
        Source.Measured -> 2
    }

/**
 * Provenance of a value derived from two readings: the least direct of the two, so a
 * derived number never claims to be more directly measured than its weakest input.
 */
private fun leastDirectOf(first: Source, second: Source): Source =
    if (first.directness >= second.directness) first else second

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
    /** Integrated current over the session, in uAh; null until a later task populates it. */
    val coulombUah: Long? = null,
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

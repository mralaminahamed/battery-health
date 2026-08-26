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
     *
     * `dumpsys battery`, over the now-removed adb/root shell tier, was this field's only
     * source; no public API exposes it. Defaults to [Reading.Unsupported] -- there is no
     * transport left to promise, so [Reading.NeedsPrivilegedAccess] would be a claim this
     * app can no longer make good on.
     */
    val bsohPct: Reading<Int> = Reading.Unsupported,
    /** Samsung Battery Protect's on/off state, from `Settings.Global.protect_battery`
     * (`VendorSettingsSource`) -- unprivileged and unconditional. */
    val protectBatteryModeEnabled: Reading<Boolean> = Reading.Unsupported,
    /** The charge percentage Battery Protect caps charging at when enabled, likewise from
     * the vendor's own Settings key. */
    val protectionThresholdPct: Reading<Int> = Reading.Unsupported,
    /**
     * Thermal throttling state from `PowerManager`, 0 (none) to 6 (shutdown). Public API,
     * no permission, every device. Sustained heat is the largest driver of capacity loss
     * after time, so this is battery data rather than a curiosity.
     *
     * Defaults to [Reading.Unsupported] rather than [Reading.NeedsPrivilegedAccess]: no
     * privilege can supply it, so inviting the user to unlock something would be a lie.
     */
    val thermalStatus: Reading<Int> = Reading.Unsupported,
    /** The platform's own estimate of time left on this charge, in milliseconds. */
    val dischargePredictionMs: Reading<Long> = Reading.Unsupported,
) {
    /**
     * Instantaneous power in milliwatts, derived from voltage (mV) and current (µA).
     *
     * Absent inputs propagate their own reason rather than collapsing to Unsupported: a
     * caller must be able to tell one input's specific absence from another's.
     * Provenance is the least direct of the two inputs, so a derived number never claims
     * to be more directly measured than its weakest input.
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
        // A vendor's own published state, sitting just behind a standard Android API in
        // directness: it is the manufacturer reporting its own setting rather than this
        // app deriving anything, but it exists only where that vendor chose to publish it.
        Source.Vendor -> 1
        Source.Privileged -> 2
        Source.Measured -> 3
        // The least direct claim this app makes. Measured is this app's own direct
        // counter/level arithmetic on data that genuinely is charge; Inferred multiplies
        // that same arithmetic by a proxy (foreground screen time) that has no direct
        // bearing on energy at all. Ranked last so a derived number never claims to be as
        // direct as even the weakest of the other four just because an Inferred value
        // happened to be one of its inputs.
        Source.Inferred -> 4
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

    /**
     * The measured capacity came out above the design figure it was compared against.
     *
     * Not an error, and not necessarily a remarkable battery: vendors publish a rated and
     * a typical capacity that differ by a few per cent, so a healthy new cell measured
     * against the rated one lands here routinely. It is, however, the clearest signal this
     * app has that the design capacity it is using is the wrong one for the device -- so
     * the screen says so rather than presenting an unexplained figure over 100%.
     */
    val exceedsDesign: Boolean get() = healthPct > 100
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
    /**
     * The hottest the cell got during the session, in tenths of a degree Celsius, or null
     * where the session recorded no temperature.
     *
     * Defaulted so every existing construction stays valid: an observation with no
     * temperature is not a cold or hot one, it is one this app knows nothing about, and
     * `HealthEstimator` treats those two cases differently.
     */
    val peakTempDeciC: Int? = null,
)

package com.mralaminahamed.batteryhealth.data.framework

import kotlin.math.abs

/**
 * Which unit a device's CURRENT_NOW readings are actually in.
 *
 * Android documents microamps. Some OEMs -- including the Galaxy A35 this was found on --
 * return milliamps, which makes every derived figure wrong by 1000x. The scale is therefore
 * measured rather than assumed, and until it is known the app says so instead of guessing.
 */
enum class CurrentScale(val microampsPerUnit: Long) {
    Microamps(1L),
    Milliamps(1_000L),
    ;

    fun toMicroamps(raw: Int): Long = raw.toLong() * microampsPerUnit
}

/**
 * Two independent mechanisms for the same question, because they answer different needs.
 * [fromMagnitude] is an immediate, per-reading guess -- gated by charge state, see its own
 * doc -- so the Live screen can show something before any session has ever completed.
 * [fromCounterAgreement] is the slower, authoritative check the capacity math is allowed to
 * trust -- a measurement against the charge counter, not a heuristic. Once the counter check
 * has an answer it must win: nothing here lets the magnitude guess override a scale
 * [fromCounterAgreement] has already confirmed.
 */
object CurrentScaleDetector {

    /**
     * Immediate, display-oriented guess from magnitude alone, refined by whether the
     * device is actively charging right now.
     *
     * Exactly one assertion is safe from magnitude with no other context at all: at or
     * above [MIN_UNAMBIGUOUS_MICROAMPS], only a microamp-scale device could plausibly
     * report the value, because the milliamp interpretation of that magnitude is an
     * impossible current (20 A or more) that no phone produces. Below that ceiling a
     * single reading genuinely cannot decide the unit: an idle device drawing a few
     * hundred *micro*amps and one drawing a few hundred *milli*amps produce
     * indistinguishable raw magnitudes, and picking one is a guess dressed as a
     * measurement -- [fromCounterAgreement] exists precisely because that guess is
     * sometimes wrong. Note the direction of the risk at the top of this band: for a raw
     * value like 19,999, the milliamp reading (20 A) is the *less* plausible one, while
     * the microamp reading (20 mA) is an ordinary idle draw -- so a rule that defaulted
     * to milliamps there, as an earlier version of this function did, was asserting into
     * exactly the case where it was least likely to be right.
     *
     * [isCharging] is the one signal specific enough to resolve that band, and it adds
     * nothing at or above the ceiling, where magnitude alone already decides. While the
     * device is actively pushing current into the battery -- not merely plugged in, see
     * [com.mralaminahamed.batteryhealth.domain.isActivelyCharging] -- that current is
     * never a trickle: real chargers regulate to currents from roughly a hundred
     * milliamps up to a few amps, never the 0.2-20 mA a microamp-scale reading in this
     * band would represent. A genuinely microamp-scale device that was actually charging
     * would therefore read in the millions here, not inside this band at all. A reading
     * that lands in this band *while charging* thus rules out the microamp hypothesis on
     * physical-plausibility grounds -- not on magnitude alone -- and leaves only
     * milliamps. Without knowing charging state, that elimination is unavailable: the
     * same magnitude could just as easily be a genuine microamp-scale idle or discharge
     * reading, so the function must abstain (null) rather than guess. This is why the
     * parameter exists: widening this band back out to an unconditional assertion, as a
     * previous version of this function did, is exactly the failure this app keeps
     * reintroducing -- a general rule answering where only a more specific one (charge
     * state, or better yet [fromCounterAgreement]'s real measurement) was entitled to.
     */
    fun fromMagnitude(rawCurrent: Int, isCharging: Boolean): CurrentScale? {
        val magnitude = abs(rawCurrent.toLong())
        return when {
            magnitude >= MIN_UNAMBIGUOUS_MICROAMPS -> CurrentScale.Microamps
            isCharging && magnitude >= UNAMBIGUOUS_MILLIAMPS -> CurrentScale.Milliamps
            else -> null
        }
    }

    /**
     * Authoritative check against an independent measurement of the same physical quantity.
     *
     * [integratedRawMicroampMillis] is Sigma(rawReading * intervalMs) over a session, using
     * the device's untouched register value -- never a value this app has already scaled --
     * so the two candidate interpretations below are genuine competing hypotheses, not one
     * hypothesis confirming itself. Integrated current over a window must equal the charge
     * counter's delta over that same window; whichever interpretation lands closer to the
     * counter is the real unit.
     *
     * Returns null when the evidence is too weak to decide: too little charge moved for
     * quantisation to stay out of the way, or neither interpretation is plausible, which
     * usually means the counter itself is synthetic rather than that the scale is unknowable.
     */
    fun fromCounterAgreement(
        integratedRawMicroampMillis: Long,
        counterDeltaUah: Long,
    ): CurrentScale? {
        if (counterDeltaUah < MIN_COUNTER_DELTA_UAH) return null
        val asMicroamps = integratedRawMicroampMillis / MS_PER_HOUR
        val asMilliamps = asMicroamps * CurrentScale.Milliamps.microampsPerUnit
        val microampError = ratioError(asMicroamps, counterDeltaUah)
        val milliampError = ratioError(asMilliamps, counterDeltaUah)
        val best = minOf(microampError, milliampError)
        if (best > MAX_ACCEPTED_ERROR) return null
        return if (microampError <= milliampError) CurrentScale.Microamps else CurrentScale.Milliamps
    }

    private fun ratioError(candidate: Long, actual: Long): Double =
        abs(candidate - actual).toDouble() / actual

    private const val MS_PER_HOUR = 3_600_000L

    /**
     * Below this, even a genuine charging current is implausible, so [fromMagnitude]
     * abstains regardless of [CurrentScale]-vs-charging-state reasoning above it -- this
     * floor is not itself validated against real low-current charging data (see the
     * Task 15 report's note on that), so it is deliberately left generous rather than
     * tightened on no evidence.
     */
    private const val UNAMBIGUOUS_MILLIAMPS = 200L

    /** At or above this, only a microamp-scale device could plausibly report the value. */
    private const val MIN_UNAMBIGUOUS_MICROAMPS = 20_000L

    /** Less charge than this and quantisation swamps the comparison. */
    private const val MIN_COUNTER_DELTA_UAH = 50_000L

    /** Integration and the fuel gauge will not agree perfectly; 40% is generous but decisive. */
    private const val MAX_ACCEPTED_ERROR = 0.40
}

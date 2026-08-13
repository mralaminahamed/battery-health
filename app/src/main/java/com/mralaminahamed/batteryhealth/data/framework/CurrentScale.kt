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
 * [fromMagnitude] is an immediate, per-reading guess so the Live screen can show something
 * before any session has ever completed. [fromCounterAgreement] is the slower, authoritative
 * check the capacity math is allowed to trust -- a measurement against the charge counter,
 * not a heuristic. Once the counter check has an answer it must win: nothing here lets the
 * magnitude guess override a scale [fromCounterAgreement] has already confirmed.
 */
object CurrentScaleDetector {

    /**
     * Immediate, display-oriented guess from magnitude alone.
     *
     * Only answers when the reading is unambiguous: a device actively charging or
     * discharging at more than [UNAMBIGUOUS_MILLIAMPS] mA must report at least that many
     * thousand microamps, so a small absolute value at a real, non-trivial rate can only be
     * milliamps. Below [UNAMBIGUOUS_MILLIAMPS] the reading could plausibly be idle at either
     * scale, so the caller gets null and reports the metric absent rather than picking.
     */
    fun fromMagnitude(rawCurrent: Int): CurrentScale? {
        val magnitude = abs(rawCurrent.toLong())
        return when {
            magnitude >= MIN_UNAMBIGUOUS_MICROAMPS -> CurrentScale.Microamps
            magnitude >= UNAMBIGUOUS_MILLIAMPS -> CurrentScale.Milliamps
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

    /** Below this the device is idle enough that magnitude proves nothing. */
    private const val UNAMBIGUOUS_MILLIAMPS = 200L

    /** At or above this, only a microamp-scale device could plausibly report the value. */
    private const val MIN_UNAMBIGUOUS_MICROAMPS = 20_000L

    /** Less charge than this and quantisation swamps the comparison. */
    private const val MIN_COUNTER_DELTA_UAH = 50_000L

    /** Integration and the fuel gauge will not agree perfectly; 40% is generous but decisive. */
    private const val MAX_ACCEPTED_ERROR = 0.40
}

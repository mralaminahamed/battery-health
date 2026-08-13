package com.mralaminahamed.batteryhealth.sampling

import com.mralaminahamed.batteryhealth.data.local.SampleEntity

data class SessionAggregate(
    val endLevelPct: Int?,
    val endCounterUah: Long?,
    val peakTempDeciC: Int?,
    val avgMilliwatts: Int?,
    val screenOnMs: Long,
    /**
     * Sigma(I * dt) across the session, in uAh. Populated only when CHARGE_COUNTER is
     * unusable or synthesised but CURRENT_NOW is real (see HealthEstimator). Null, never
     * zero, when no interval was usable -- an unmeasured session and a session that
     * measured no charge are different facts.
     */
    val coulombUah: Long?,
)

/**
 * Reduces a session's samples to its stored summary. Pure, so every rule below is unit
 * tested: aggregates over an empty or incomplete set are null rather than zero, because
 * "no reading" and "a reading of zero" mean different things on the History screen.
 */
object SessionAggregator {

    /**
     * A 5-second sampling cadence honestly supports gaps up to a few cadences wide; six
     * cadences (Doze, process death, a killed service) is treated as sampling having
     * stalled rather than a plateau, so the interval is dropped from every attributed
     * total instead of counted -- doing otherwise would fabricate charge, or screen-on
     * time, that was never observed. The rule applies identically to both metrics: a gap
     * is missing data, not a plateau, regardless of which quantity would be attributed.
     */
    private const val MAX_ATTRIBUTABLE_GAP_MS = 30_000L
    private const val MS_PER_HOUR = 3_600_000L

    fun aggregate(samples: List<SampleEntity>): SessionAggregate {
        if (samples.isEmpty()) {
            return SessionAggregate(
                endLevelPct = null,
                endCounterUah = null,
                peakTempDeciC = null,
                avgMilliwatts = null,
                screenOnMs = 0L,
                coulombUah = null,
            )
        }

        val ordered = samples.sortedBy { it.timestampMs }
        val last = ordered.last()

        val powerSamples = ordered.mapNotNull { sample ->
            val voltage = sample.voltageMv ?: return@mapNotNull null
            val current = sample.currentUa ?: return@mapNotNull null
            (voltage.toLong() * current / 1_000_000L).toInt()
        }

        // Each interval is attributed to the state at its start, which is all the sampling
        // cadence can honestly support.
        var screenOnMs = 0L
        var coulombUah = 0L
        var hasUsableInterval = false
        for (index in 0 until ordered.lastIndex) {
            val current = ordered[index]
            val next = ordered[index + 1]
            val intervalMs = next.timestampMs - current.timestampMs

            // The gap rule is shared and evaluated first, so it excludes a stalled
            // interval from both metrics identically. The null-current rule is separate
            // and independent: it only ever disqualifies coulomb for its own interval,
            // never the screen-on attribution, and never the interval that follows.
            if (intervalMs > MAX_ATTRIBUTABLE_GAP_MS) continue

            if (current.screenOn) {
                screenOnMs += intervalMs
            }

            val currentUa = current.currentUa
            if (currentUa != null) {
                coulombUah += currentUa.toLong() * intervalMs / MS_PER_HOUR
                hasUsableInterval = true
            }
        }

        return SessionAggregate(
            endLevelPct = last.levelPct,
            endCounterUah = last.chargeCounterUah,
            peakTempDeciC = ordered.mapNotNull { it.tempDeciC }.maxOrNull(),
            avgMilliwatts = powerSamples.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            screenOnMs = screenOnMs,
            coulombUah = coulombUah.takeIf { hasUsableInterval },
        )
    }
}

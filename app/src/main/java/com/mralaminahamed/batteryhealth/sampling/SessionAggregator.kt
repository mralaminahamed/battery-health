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
     * stalled rather than a plateau, so the interval is dropped instead of integrated --
     * doing otherwise would fabricate charge that was never measured.
     */
    private const val MAX_INTEGRATION_GAP_MS = 30_000L
    private const val MS_PER_HOUR = 3_600_000L

    fun aggregate(samples: List<SampleEntity>): SessionAggregate {
        if (samples.isEmpty()) {
            return SessionAggregate(null, null, null, null, 0L, null)
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

            if (current.screenOn) {
                screenOnMs += intervalMs
            }

            // Each interval is judged independently against both rules, on the original
            // sample order -- neither rule consumes or shifts the other's view of the
            // list, so a null-current sample skips only the interval it starts, and a
            // stalled interval skips only itself, never the interval that follows it.
            val currentUa = current.currentUa
            if (currentUa != null && intervalMs <= MAX_INTEGRATION_GAP_MS) {
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

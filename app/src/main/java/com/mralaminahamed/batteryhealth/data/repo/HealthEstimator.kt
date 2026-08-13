package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.domain.CapacityMethod
import com.mralaminahamed.batteryhealth.domain.CapacityObservation
import com.mralaminahamed.batteryhealth.domain.HealthReport
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Derives full capacity from charge-counter deltas across charge sessions.
 *
 * Pure by construction: no Android types, no clock, no IO. Every guard below exists
 * because the naive version of this calculation produces a confident wrong number, and
 * a confident wrong number is worse than an honest absence.
 */
@Singleton
class HealthEstimator @Inject constructor() {

    fun estimate(
        observations: List<CapacityObservation>,
        designCapacityMah: Int?,
    ): Reading<HealthReport> {
        // Without design capacity there is nothing to compare a measurement against.
        // (Checked directly on the parameter, not through a transformed chain, so the
        // compiler can smart-cast it to non-null Int for the rest of this function.)
        if (designCapacityMah == null || designCapacityMah <= 0) return Reading.Unsupported
        val designUah = designCapacityMah.toLong() * UAH_PER_MAH

        val qualifying = observations
            .filter { it.deltaLevelPct >= MIN_DELTA_LEVEL_PCT }
            .mapNotNull { it.toMeasurement() }

        val wide = qualifying.filter { it.deltaLevelPct >= WIDE_DELTA_LEVEL_PCT }
        // Selection narrows the set. It never weights it: there is no weighted average
        // anywhere in this calculation.
        val chosen = if (wide.size >= MIN_SESSIONS) wide else qualifying

        if (chosen.size < MIN_SESSIONS) return Reading.NotYetMeasured
        if (looksDerivedFromLevel(chosen, designUah)) return Reading.Unsupported

        val median = chosen.map { it.fullUah }.median()
        val healthPct = (median.toDouble() / designUah * 100).roundToInt().coerceIn(1, 100)
        val method =
            if (chosen.count { it.method == CapacityMethod.Counter } * 2 >= chosen.size) {
                CapacityMethod.Counter
            } else {
                CapacityMethod.Coulomb
            }

        return Reading.Available(
            HealthReport(
                healthPct = healthPct,
                measuredFullUah = median,
                designCapacityMah = designCapacityMah,
                method = method,
                sessionsUsed = chosen.size,
            ),
            Source.Measured,
        )
    }

    private fun CapacityObservation.toMeasurement(): Measurement? {
        val counter = counterDeltaUah?.takeIf { it > 0 }
        if (counter != null) {
            return Measurement(deltaLevelPct, counter * 100 / deltaLevelPct, CapacityMethod.Counter)
        }
        val coulomb = coulombUah?.takeIf { it > 0 } ?: return null
        return Measurement(deltaLevelPct, coulomb * 100 / deltaLevelPct, CapacityMethod.Coulomb)
    }

    /**
     * Some devices synthesise CHARGE_COUNTER from level multiplied by design capacity.
     * The formula then returns the design capacity exactly, and every battery looks
     * pristine. Genuine measurements drift with window width; a synthesised counter
     * does not. Requiring a wide spread of window sizes keeps a truly new battery from
     * being misclassified on three similar charges.
     */
    private fun looksDerivedFromLevel(measurements: List<Measurement>, designUah: Long): Boolean {
        val counterOnly = measurements.filter { it.method == CapacityMethod.Counter }
        if (counterOnly.size < MIN_SESSIONS) return false

        val widths = counterOnly.map { it.deltaLevelPct }
        val spread = (widths.max() - widths.min())
        if (spread < MIN_SPREAD_FOR_DERIVED_TEST) return false

        val tolerance = designUah * DERIVED_TOLERANCE
        return counterOnly.all { abs(it.fullUah - designUah) <= tolerance }
    }

    private fun List<Long>.median(): Long {
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private data class Measurement(
        val deltaLevelPct: Int,
        val fullUah: Long,
        val method: CapacityMethod,
    )

    companion object {
        /** Level is an integer percent, so narrow windows carry ruinous quantisation error. */
        const val MIN_DELTA_LEVEL_PCT = 20
        const val WIDE_DELTA_LEVEL_PCT = 40
        const val MIN_SESSIONS = 3

        private const val UAH_PER_MAH = 1_000L
        private const val MIN_SPREAD_FOR_DERIVED_TEST = 10
        private const val DERIVED_TOLERANCE = 0.005 // 0.5%
    }
}

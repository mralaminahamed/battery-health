package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.CapacityMethod
import com.alaminahamed.batteryhealth.domain.CapacityObservation
import com.alaminahamed.batteryhealth.domain.HealthReport
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
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
 *
 * Ordering matters here more than it looks like it should. The derived-counter check
 * must see every qualifying session, not just the ones a later, more general rule
 * (wide-set narrowing) decided to keep -- narrowing can discard exactly the narrow-window
 * evidence that would have convicted a synthesised counter. And once a counter is judged
 * synthesised, the coulomb retry must run before any later rule gets a chance to preempt
 * it. Read the `if`s below top to bottom before reordering any of them.
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

        // One row per charge session: a duplicated row would otherwise satisfy MIN_SESSIONS
        // and pull the median on its own repeated say, defeating the reason several
        // independent sessions are required to agree in the first place.
        val deduped = observations.distinctBy { it.sessionId }
        val levelQualifying = deduped.filter { it.deltaLevelPct >= MIN_DELTA_LEVEL_PCT }
        val qualifying = levelQualifying.mapNotNull { it.toMeasurement() }

        // The derived-counter check must run on every qualifying counter session, before
        // wide-set narrowing below throws any of them away. Narrowing can shrink the
        // window spread below the detection threshold, hiding a synthesised counter that
        // the discarded, narrower sessions would have exposed.
        val counterQualifying = qualifying.filter { it.method == CapacityMethod.Counter }
        if (looksDerivedFromLevel(counterQualifying, designUah)) {
            // The counter is synthesised. Integrated current is an independent measurement
            // of the same thing, so retry from it rather than discarding the session set.
            // It divides by the same deltaLevelPct the counter path does, so it carries the
            // same quantisation error and must be narrowed the same way, in the same order:
            // narrow first, then check MIN_SESSIONS against the narrowed set -- checking
            // count before narrowing would let a set that narrows below three through.
            val coulombOnly = levelQualifying.mapNotNull { it.toCoulombMeasurement() }
            val coulombChosen = preferWide(coulombOnly)
            if (coulombChosen.size < MIN_SESSIONS) return Reading.Unsupported
            return report(coulombChosen, designCapacityMah, designUah)
        }

        val chosen = preferWide(qualifying)
        if (chosen.size < MIN_SESSIONS) return Reading.NotYetMeasured
        return report(chosen, designCapacityMah, designUah)
    }

    /**
     * Prefers wide charge windows when there are enough of them. A measurement divided by a
     * small deltaLevelPct carries large quantisation error, so a set of wide sessions is
     * strictly better evidence than a mixed one. Selection narrows the set; it never weights
     * it. Shared by the counter path and the coulomb retry so there is one rule, not two
     * copies that can drift apart.
     */
    private fun preferWide(measurements: List<Measurement>): List<Measurement> {
        val wide = measurements.filter { it.deltaLevelPct >= WIDE_DELTA_LEVEL_PCT }
        return if (wide.size >= MIN_SESSIONS) wide else measurements
    }

    /** Shared by the counter path and the coulomb retry so neither duplicates this block. */
    private fun report(
        measurements: List<Measurement>,
        designCapacityMah: Int,
        designUah: Long,
    ): Reading<HealthReport> {
        val median = measurements.map { it.fullUah }.median()

        // A measurement this far from design is a unit or scale fault, not a battery: some
        // OEMs report CHARGE_COUNTER in mAh rather than uAh, which lands ~1000x low. A dead
        // battery and a broken unit are indistinguishable at that extreme, so this must not
        // clamp to a confident 1% -- it must decline to answer.
        val ratio = median.toDouble() / designUah
        if (ratio < MIN_PLAUSIBLE_RATIO || ratio > MAX_PLAUSIBLE_RATIO) return Reading.Unsupported

        // Not clamped to 100.
        //
        // A cell measuring above its design capacity is real and common -- vendors publish
        // both a rated and a typical figure and they differ by a few per cent -- but it is
        // also the loudest available signal that the design capacity this app is comparing
        // against is the wrong one for the device. Clamping turned that signal into a
        // serene "100%".
        //
        // It has already cost this project once. `power_profile.xml` on an SM-S948B
        // carries battery.capacity 4855 alongside Samsung's own battery.typical.capacity
        // 5000, and reading the first gave a health of 103.7% -- which the clamp would
        // have displayed as 100%, with nothing anywhere in the app disagreeing. The bug
        // was found by comparing spec sheets by hand, not by using the app.
        //
        // The plausibility guard above already refuses anything past MAX_PLAUSIBLE_RATIO,
        // so what survives here is a percentage between 40 and 130 -- a range in which
        // every value is worth showing, including the ones above 100.
        //
        // The old lower bound of 1 was unreachable in any case: MIN_PLAUSIBLE_RATIO of
        // 0.40 means nothing below 40 ever reached it.
        val healthPct = (ratio * 100).roundToInt()
        val method =
            if (measurements.count { it.method == CapacityMethod.Counter } * 2 >= measurements.size) {
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
                sessionsUsed = measurements.size,
            ),
            Source.Measured,
        )
    }

    private fun CapacityObservation.toMeasurement(): Measurement? {
        // Guarded locally, not just by the caller's filter: a future caller must not be
        // able to reintroduce a division by zero here by skipping that filter.
        if (deltaLevelPct <= 0) return null
        val counter = counterDeltaUah?.takeIf { it > 0 }
        if (counter != null) {
            return Measurement(deltaLevelPct, counter * 100 / deltaLevelPct, CapacityMethod.Counter)
        }
        return toCoulombMeasurement()
    }

    private fun CapacityObservation.toCoulombMeasurement(): Measurement? {
        if (deltaLevelPct <= 0) return null
        val coulomb = coulombUah?.takeIf { it > 0 } ?: return null
        return Measurement(deltaLevelPct, coulomb * 100 / deltaLevelPct, CapacityMethod.Coulomb)
    }

    /**
     * Some devices synthesise CHARGE_COUNTER from level multiplied by design capacity.
     * The formula then returns the design capacity exactly, and every battery looks
     * pristine. Genuine measurements drift with window width; a synthesised counter
     * does not. Requiring a wide spread of window sizes keeps a truly new battery from
     * being misclassified on three similar charges.
     *
     * Callers must pass the full qualifying population, not a narrowed subset: narrowing
     * can shrink the spread below the threshold and hide a synthesised counter that the
     * wider, discarded sessions would have exposed.
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

        /**
         * A measurement outside this band is more likely a unit or scale fault (e.g. a
         * counter reported in mAh instead of uAh, landing ~1000x low) than a real battery.
         */
        private const val MIN_PLAUSIBLE_RATIO = 0.40
        private const val MAX_PLAUSIBLE_RATIO = 1.30
    }
}

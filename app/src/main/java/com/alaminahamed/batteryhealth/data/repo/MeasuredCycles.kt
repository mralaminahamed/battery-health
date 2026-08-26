package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * A cycle count this app works out for itself, from charge it actually watched go in.
 *
 * Samsung publishes a lifetime figure, but only through `dumpsys` -- there is no
 * `BATTERY_PROPERTY` for it and `EXTRA_CYCLE_COUNT` reads 0 on the hardware this was
 * verified against. That single value was the last thing keeping the privileged shell on
 * the critical path for an ordinary user.
 *
 * One equivalent charge cycle is one design capacity of charge accumulated, however many
 * partial charges it took. The app already records each charge session's counter delta for
 * its health measurement, so the same data answers this.
 *
 * ## What this number is not
 *
 * It counts from the day the app was installed. Samsung's counts from the day the battery
 * was made. On a phone that is already a year old these will differ enormously, and
 * presenting them as the same quantity would be the most misleading thing this app could
 * do with them -- so the vendor's figure always wins where it is available, and this one
 * is labelled by its own [Source.Measured] provenance rather than blended in.
 */
internal object MeasuredCycles {

    /**
     * Charge accumulated across recorded sessions, expressed in equivalent full cycles.
     *
     * @param chargedUah per-session charge added, in microamp-hours. Negative or zero
     *   entries are ignored rather than subtracted: a discharge session is not a negative
     *   cycle, and letting one cancel a real charge would under-report wear.
     * @param designCapacityMah what one cycle is worth. Null when unknown, which makes the
     *   whole figure meaningless -- a count of cycles against an unknown cycle size is not
     *   a number this app is willing to show.
     *
     * Truncated, not rounded. Reporting "1 cycle" after six tenths of one would overstate
     * measured wear, and every other derived figure in this app errs the same way.
     */
    fun fromSessions(chargedUah: List<Long>, designCapacityMah: Int?): Reading<Int> {
        if (designCapacityMah == null || designCapacityMah <= 0) return Reading.Unsupported
        val positive = chargedUah.filter { it > 0L }
        if (positive.isEmpty()) return Reading.NotYetMeasured
        val perCycleUah = designCapacityMah.toLong() * 1_000L
        val cycles = positive.sumOf { it } / perCycleUah
        // Long -> Int is safe by construction: reaching Int.MAX_VALUE cycles would take
        // more charge than any battery will see, but the coercion is explicit rather than
        // assumed, because a silent wrap here would print a negative cycle count.
        return Reading.Available(cycles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), Source.Measured)
    }
}

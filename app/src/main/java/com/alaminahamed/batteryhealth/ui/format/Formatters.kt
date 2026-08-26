package com.alaminahamed.batteryhealth.ui.format

import java.time.LocalDate
import java.util.Locale

/**
 * All display conversion in one pure object. Kept out of composables so the unit
 * conversions that matter (deci-Celsius, microamps, milliwatts) are unit-tested rather
 * than eyeballed on a screen.
 */
object Formatters {

    fun temperature(deciC: Int): String =
        String.format(Locale.US, "%.1f °C", deciC / 10.0)

    fun milliamps(microAmps: Int): String =
        String.format(Locale.US, "%d mA", microAmps / 1000)

    fun watts(milliwatts: Int): String =
        String.format(Locale.US, "%.2f W", milliwatts / 1000.0)

    /** Bare number for hero metrics whose unit is rendered separately. */
    fun wattsValue(milliwatts: Int): String =
        String.format(Locale.US, "%.2f", milliwatts / 1000.0)

    fun duration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours h $minutes m" else "$minutes m"
    }

    /**
     * CPU time for the Apps screen, which needs finer resolution than [duration].
     *
     * [duration] floors to whole minutes, which is right for a charge session and wrong
     * here: the ranking already drops uids with no CPU time at all, so every row shown has
     * a genuinely non-zero value, and rendering "0 m" for one would make the list
     * contradict itself. Same reasoning as [milliampHours]' two decimals -- losing the one
     * number the row exists to show.
     */
    fun cpuTime(ms: Long): String {
        val seconds = ms / 1_000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "$hours h ${minutes % 60} m"
            minutes > 0 -> "$minutes m ${seconds % 60} s"
            seconds > 0 -> "$seconds s"
            // Non-zero but under a second. "0 s" would read as nothing at all, which is
            // exactly what the ranking already filtered out.
            else -> "<1 s"
        }
    }

    fun epochDay(day: Long): String = LocalDate.ofEpochDay(day).toString()

    /** Milliamp-hours for the Apps screen's per-uid power rows. Two decimals for the same
     * reason [watts] uses two: most of a real `batterystats --checkin` capture's ninety
     * per-uid rows are well under 1 mAh, and a single-decimal format would round most of
     * them to "0.0 mAh", losing the one number the row exists to show. */
    fun milliampHours(mAh: Double): String = String.format(Locale.US, "%.2f mAh", mAh)

    /** A uid's share of everything `batterystats` accounted for. One decimal: this is a
     * proportion for orientation ("roughly a tenth of everything"), not a precise
     * measurement that would justify more. */
    fun percentShare(pct: Double): String = String.format(Locale.US, "%.1f%%", pct)
}

package com.mralaminahamed.batteryhealth.ui.format

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

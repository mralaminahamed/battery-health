package com.alaminahamed.batteryhealth.data.framework

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Converts the epoch-second dates `BATTERY_STATS` unlocks into the epoch days the rest of
 * this app speaks.
 *
 * `BATTERY_PROPERTY_MANUFACTURING_DATE` and `BATTERY_PROPERTY_FIRST_USAGE_DATE` return
 * seconds since the epoch, while `BatterySnapshot` carries calendar days -- so a
 * conversion is mandatory, and it is not merely arithmetic: which calendar day a given
 * instant falls on depends on the zone it is read in.
 *
 * ## Why the system zone, and not UTC
 *
 * On an SM-S948B the first-usage property returned 1786557600. The app's privileged
 * `dumpsys` path, parsing a packed calendar date over a completely separate route,
 * rendered that same battery's first use as 2026-08-13. In the device's own zone (+06)
 * this value is 2026-08-13; in UTC it is 2026-08-12, a day earlier. The vendor's own
 * figure is what settles it, and matching it is what stops the two sources disagreeing
 * about one battery on one screen.
 *
 * A second, stronger signal points the same way: on that device both properties land on
 * exactly 00:00:00 in the local zone (and 18:00:00 in UTC). Two independent values hitting
 * local midnight to the second is not coincidence -- the vendor encodes these as local
 * midnight, so the local zone is the only reading that recovers the day it meant.
 *
 * That is still one device's evidence. If a device is ever found where the vendor's own
 * date disagrees with the system-zone reading, this is the one place that changes.
 */
internal object GrantedBatteryDates {

    /**
     * How far past today a date may still be accepted.
     *
     * Not zero, because "today" here and the device's own clock need not agree exactly --
     * an unsynced clock or a zone the user has just changed should not blank an otherwise
     * real manufacturing date. Not generous either: a battery genuinely manufactured next
     * year is a misread register, not a fact.
     */
    const val FUTURE_TOLERANCE_DAYS = 31L

    /**
     * The earliest date this app will believe. Lithium-ion phone batteries reporting a
     * manufacturing date predate this by nothing that matters, and a value below it is a
     * sentinel or a misread far more often than a very old battery.
     */
    private val EARLIEST: Long = LocalDate.of(2000, 1, 1).toEpochDay()

    /**
     * The calendar day [rawSeconds] falls on in [zone], or null when the platform had
     * nothing real to say.
     *
     * Pure, with [zone] and [todayEpochDay] passed in rather than read from the
     * environment, so every rejection rule is provable on the JVM -- the same shape
     * `DumpsysBatteryParser.parse` already uses for its own date handling.
     *
     * Zero and negative are rejected outright. A battery manufactured at the Unix epoch is
     * not a thing, and rendering "1 Jan 1970" would be presenting the platform's sentinel
     * as a fact -- which is the failure this whole codebase is built to avoid.
     */
    fun toEpochDay(rawSeconds: Long, zone: ZoneId, todayEpochDay: Long): Long? {
        if (rawSeconds <= 0L) return null
        val day = runCatching {
            Instant.ofEpochSecond(rawSeconds).atZone(zone).toLocalDate().toEpochDay()
        }.getOrNull() ?: return null
        if (day < EARLIEST) return null
        if (day > todayEpochDay + FUTURE_TOLERANCE_DAYS) return null
        return day
    }
}

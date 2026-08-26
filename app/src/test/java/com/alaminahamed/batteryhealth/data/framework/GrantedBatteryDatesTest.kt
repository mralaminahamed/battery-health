package com.alaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GrantedBatteryDatesTest {

    private val dhaka = ZoneId.of("Asia/Dhaka") // +06, the verification device's zone
    private val today = LocalDate.of(2026, 8, 26).toEpochDay()

    private fun convert(seconds: Long, zone: ZoneId = dhaka) =
        GrantedBatteryDates.toEpochDay(seconds, zone, today)

    /**
     * The reading taken from an SM-S948B with `BATTERY_STATS` granted:
     * `BATTERY_PROPERTY_FIRST_USAGE_DATE` returned 1786557600.
     *
     * The app's privileged `dumpsys` path, parsing a packed calendar date over a
     * completely separate route, rendered that same battery's first use as 2026-08-13.
     * Converting in the device's own zone reproduces it; converting in UTC yields
     * 2026-08-12, a day earlier. Agreement with the vendor's own figure is what settles
     * the zone question, so the two sources can never disagree about one battery.
     */
    @Test
    fun theDeviceReadingReproducesTheDateTheVendorItselfReports() {
        assertEquals(LocalDate.of(2026, 8, 13).toEpochDay(), convert(1_786_557_600))
    }

    /**
     * The manufacturing date from the same device and reading session.
     *
     * This is the reading that settles the zone question on its own. Both properties land
     * on exactly 00:00:00 in the device's own zone and on 18:00:00 in UTC. Two independent
     * values hitting local midnight to the second is not a coincidence -- the vendor
     * encodes these as local midnight, so the local zone is the only reading that recovers
     * the day the vendor meant.
     */
    @Test
    fun theManufacturingDateFromTheSameDeviceConverts() {
        assertEquals(LocalDate.of(2026, 3, 28).toEpochDay(), convert(1_774_634_400))
    }

    /**
     * The evidence above, stated as an assertion rather than left in a comment: both
     * device readings are local midnight, which is why [GrantedBatteryDates] converts in
     * the system zone.
     */
    @Test
    fun bothDeviceReadingsAreExactlyLocalMidnight() {
        listOf(1_774_634_400L, 1_786_557_600L).forEach { seconds ->
            val local = java.time.Instant.ofEpochSecond(seconds).atZone(dhaka).toLocalTime()
            assertEquals("$seconds", java.time.LocalTime.MIDNIGHT, local)
        }
    }

    /**
     * The zone is genuinely load-bearing rather than incidental, and this pins that a
     * future "simplification" to UTC would silently shift every date by a day for users
     * east of Greenwich.
     */
    @Test
    fun theZoneChangesTheAnswerSoItIsNotOptional() {
        assertEquals(
            LocalDate.of(2026, 8, 12).toEpochDay(),
            convert(1_786_557_600, ZoneId.of("UTC")),
        )
    }

    /**
     * Zero and negative are the platform saying it has nothing. A battery manufactured at
     * the Unix epoch is not a thing, and rendering "1 Jan 1970" as a manufacturing date
     * would be this app presenting a sentinel as a fact.
     */
    @Test
    fun sentinelsAreRejectedRatherThanRenderedAs1970() {
        assertNull(convert(0))
        assertNull(convert(-1))
        assertNull(convert(Long.MIN_VALUE))
    }

    /**
     * A date before lithium-ion phone batteries plausibly existed is a misread register,
     * not a very old battery.
     */
    @Test
    fun implausiblyOldDatesAreRejected() {
        assertNull(convert(LocalDate.of(1999, 12, 31).atStartOfDay(dhaka).toEpochSecond()))
    }

    /**
     * A battery cannot have been manufactured after today. A small forward tolerance is
     * allowed because the device clock and this app's idea of "today" need not agree
     * exactly -- a wrong timezone or an unsynced clock should not blank a real date.
     */
    @Test
    fun datesBeyondTheToleranceAreRejectedButNearFutureIsKept() {
        val tomorrow = LocalDate.ofEpochDay(today + 1).atStartOfDay(dhaka).toEpochSecond()
        assertEquals(today + 1, convert(tomorrow))

        val nextYear = LocalDate.ofEpochDay(today + 400).atStartOfDay(dhaka).toEpochSecond()
        assertNull(convert(nextYear))
    }

    @Test
    fun theToleranceBoundaryItselfIsAccepted() {
        val edge = LocalDate.ofEpochDay(today + GrantedBatteryDates.FUTURE_TOLERANCE_DAYS)
            .atStartOfDay(dhaka)
            .toEpochSecond()
        assertEquals(today + GrantedBatteryDates.FUTURE_TOLERANCE_DAYS, convert(edge))
    }
}

package com.alaminahamed.batteryhealth.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun temperatureRendersOneDecimalFromDeciCelsius() {
        assertEquals("37.1 °C", Formatters.temperature(371))
        assertEquals("40.0 °C", Formatters.temperature(400))
        assertEquals("-5.0 °C", Formatters.temperature(-50))
    }

    @Test
    fun currentConvertsMicroampsToMilliampsWithSign() {
        assertEquals("1066 mA", Formatters.milliamps(1_066_000))
        assertEquals("-450 mA", Formatters.milliamps(-450_000))
        assertEquals("0 mA", Formatters.milliamps(0))
    }

    @Test
    fun wattsRenderTwoDecimalsFromMilliwatts() {
        assertEquals("4.21 W", Formatters.watts(4_214))
        assertEquals("-1.78 W", Formatters.watts(-1_780))
    }

    @Test
    fun durationRendersHoursAndMinutes() {
        assertEquals("1 h 25 m", Formatters.duration(85 * 60 * 1000L))
        assertEquals("25 m", Formatters.duration(25 * 60 * 1000L))
        assertEquals("0 m", Formatters.duration(30 * 1000L))
        assertEquals("2 h 0 m", Formatters.duration(120 * 60 * 1000L))
    }

    @Test
    fun epochDayRendersAnIsoDate() {
        // 2024-06-30 is epoch day 19904.
        assertEquals("2024-06-30", Formatters.epochDay(19_904))
    }
}

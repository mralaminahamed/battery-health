package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DumpsysBatteryParserTest {

    private fun loadFixture(): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("dumpsys-battery-sm-a356e.txt")
            ?: error("fixture not found on the test classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun parsesEveryFieldFromTheRealDeviceFixture() {
        val dump = loadFixture()
        val parsed = DumpsysBatteryParser.parse(dump)

        // mSavedBatteryAsoc: [86] -- Samsung's ASOC, the real state-of-health percentage.
        assertEquals(86, parsed.asocPct)
        // mSavedBatteryBsoh: 95 -- a second, deliberately different Samsung health figure.
        assertEquals(95, parsed.bsohPct)
        // battery FirstUseDate: [20240630] -- see packedDateConversion... below for the
        // hand-computed check of this exact number.
        assertEquals(19_904L, parsed.firstUseDateEpochDay)
        // mProtectBatteryMode: 1
        assertEquals(true, parsed.protectBatteryModeEnabled)
        // mProtectionThreshold: 80 -- not 95, which is mMaximumProtectionThreshold one
        // line below it in the same real fixture. See
        // doesNotConfuseProtectionThresholdWithMaximumProtectionThreshold for the
        // regression this pins.
        assertEquals(80, parsed.protectionThresholdPct)
    }

    /**
     * Hand-computed check, independent of the parser: 1970-01-01 to 2024-06-30.
     *
     * 1970-01-01 to 2024-01-01 is 54 elapsed years. Leap years fully elapsed in that span
     * are 1972, 1976, ..., 2020 (2024's own Feb 29 has not happened yet at 2024-01-01) --
     * (2020-1972)/4 + 1 = 13 of them. 54*365 + 13 = 19710 + 13 = 19723 days to 2024-01-01.
     *
     * 2024-01-01 to 2024-06-30: January(31) + February(29, 2024 is a leap year) +
     * March(31) + April(30) + May(31) = 152 days to reach 2024-06-01, plus 29 more days to
     * reach the 30th = 181.
     *
     * 19723 + 181 = 19904.
     */
    @Test
    fun packedDateConversionMatchesAHandComputedEpochDay() {
        assertEquals(19_904L, DumpsysBatteryParser.packedDateToEpochDay(20_240_630))
    }

    @Test
    fun emptyDumpProducesAllAbsentFieldsRatherThanThrowing() {
        val parsed = DumpsysBatteryParser.parse("")

        assertNull(parsed.asocPct)
        assertNull(parsed.bsohPct)
        assertNull(parsed.firstUseDateEpochDay)
        assertNull(parsed.protectBatteryModeEnabled)
        assertNull(parsed.protectionThresholdPct)
    }

    @Test
    fun aRenamedAsocLineDegradesOnlyThatFieldNotTheWholeParse() {
        // Same fixture, but the one line this parser recognises for ASOC has been
        // mangled as if a One UI update renamed it. Every other field must still parse.
        val dump = loadFixture().replace("mSavedBatteryAsoc: [86]", "mSavedBatteryAsocV2: [86]")
        val parsed = DumpsysBatteryParser.parse(dump)

        assertNull(parsed.asocPct)
        assertEquals(95, parsed.bsohPct)
        assertEquals(19_904L, parsed.firstUseDateEpochDay)
        assertEquals(true, parsed.protectBatteryModeEnabled)
        assertEquals(80, parsed.protectionThresholdPct)
    }

    /**
     * Regression pin for the `\b` word-boundary guard on [DumpsysBatteryParser]'s regexes.
     * `mMaximumProtectionThreshold` genuinely contains `mProtectionThreshold` as a raw
     * substring ("Maximu**mProtectionThreshold**"). Without the leading `\b`, a dump that
     * happened to omit the real `mProtectionThreshold` line (or reorder it) would let this
     * match the wrong field and silently report a battery-protect threshold that is
     * actually the *maximum* threshold, a different Samsung setting.
     */
    @Test
    fun doesNotConfuseProtectionThresholdWithMaximumProtectionThreshold() {
        val dump = " mMaximumProtectionThreshold: 95\n"
        val parsed = DumpsysBatteryParser.parse(dump)

        assertNull(parsed.protectionThresholdPct)
    }

    @Test
    fun protectBatteryModeZeroParsesAsDisabledRatherThanAbsent() {
        val parsed = DumpsysBatteryParser.parse(" mProtectBatteryMode: 0\n")

        assertEquals(false, parsed.protectBatteryModeEnabled)
    }

    @Test
    fun malformedPackedDateReturnsNullInsteadOfThrowing() {
        // Month 13, day 32 -- not a real date under any calendar.
        val dump = "  battery FirstUseDate: [20241332]\n"
        val parsed = DumpsysBatteryParser.parse(dump)

        assertNull(parsed.firstUseDateEpochDay)
    }

    @Test
    fun bsohAndAsocAreReadIndependentlyEvenThoughBothAreCalledHealth() {
        // Regression against collapsing the two Samsung health figures into one field --
        // they are allowed to (and, on the real fixture, do) disagree.
        val dump = loadFixture()
        val parsed = DumpsysBatteryParser.parse(dump)

        assertTrue(parsed.asocPct != parsed.bsohPct)
    }
}

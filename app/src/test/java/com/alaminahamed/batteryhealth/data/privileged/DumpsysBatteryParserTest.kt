package com.alaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 775 days after the fixture's own `battery FirstUseDate` (epoch day 19_904, see
        // packedDateConversion... below) -- the exact sanity check the task this was built
        // against ran by hand: 19_904 + 775 = 20_679, which is 2026-08-14.
        val parsed = DumpsysBatteryParser.parse(dump, todayEpochDay = 20_679L)

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
        // mSavedBatteryUsage: [61919] -- 61_919 / 100 = 619.19, rounded to 619. The
        // plausibility guard passes: 619 cycles / 775 days = 0.7987... cycles/day, well
        // inside [0.1, 3.0].
        assertEquals(619, parsed.cycleCount)
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
        assertNull(parsed.cycleCount)
    }

    @Test
    fun aRenamedAsocLineDegradesOnlyThatFieldNotTheWholeParse() {
        // Same fixture, but the one line this parser recognises for ASOC has been
        // mangled as if a One UI update renamed it. Every other field must still parse.
        val dump = loadFixture().replace("mSavedBatteryAsoc: [86]", "mSavedBatteryAsocV2: [86]")
        val parsed = DumpsysBatteryParser.parse(dump, todayEpochDay = 20_679L)

        assertNull(parsed.asocPct)
        assertEquals(95, parsed.bsohPct)
        assertEquals(19_904L, parsed.firstUseDateEpochDay)
        assertEquals(true, parsed.protectBatteryModeEnabled)
        assertEquals(80, parsed.protectionThresholdPct)
        assertEquals(619, parsed.cycleCount)
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

    @Test
    fun cycleCountRoundsToTheNearestWholeCycleRatherThanTruncating() {
        // 12_360 hundredths = 123.60 cycles. Truncating (floor) would report 123;
        // rounding to the nearest whole cycle reports 124. No first-use date in this dump,
        // so the plausibility guard has nothing to cross-check against and cannot
        // interfere with this assertion -- see
        // missingFirstUseDateStillReportsAnOtherwiseImplausibleLookingCycleCount below for
        // that behaviour pinned on its own.
        val parsed = DumpsysBatteryParser.parse("  mSavedBatteryUsage: [12360]\n")

        assertEquals(124, parsed.cycleCount)
    }

    @Test
    fun negativeCycleUsageIsRejectedRegardlessOfFirstUseDate() {
        // A negative reading is not a smaller cycle count; rejected before the rate
        // cross-check even runs, and independent of whether a first-use date exists.
        val parsed = DumpsysBatteryParser.parse("  mSavedBatteryUsage: [-100]\n")

        assertNull(parsed.cycleCount)
    }

    @Test
    fun missingFirstUseDateStillReportsAnOtherwiseImplausibleLookingCycleCount() {
        // 999_999 hundredths = 9999.99, rounded to 10_000 cycles -- a rate the guard would
        // reject on any battery younger than roughly 9 years old, if it had a first-use
        // date to cross-check against. It does not, here, so the cross-check cannot run at
        // all and must not manufacture a rejection out of a different, absent field's
        // trouble.
        val parsed = DumpsysBatteryParser.parse("  mSavedBatteryUsage: [999999]\n")

        assertEquals(10_000, parsed.cycleCount)
    }

    @Test
    fun implausibleCycleRateReportsCycleCountAbsentButLeavesFirstUseDateIntact() {
        // 100 cycles accumulated in a single day (first use "yesterday" relative to
        // todayEpochDay = 1) is far outside [0.1, 3.0] cycles/day -- the ÷100
        // interpretation cannot be right here, so the honest answer is absent, not a
        // number that might be a hundredfold off.
        val dump = """
            mSavedBatteryUsage: [10000]
            battery FirstUseDate: [19700101]
        """.trimIndent()
        val parsed = DumpsysBatteryParser.parse(dump, todayEpochDay = 1L)

        assertNull(parsed.cycleCount)
        // The unrelated field parsed from the very same dump must not be collateral
        // damage from cycle count's own guard rejecting.
        assertEquals(0L, parsed.firstUseDateEpochDay)
    }

    @Test
    fun cycleRateExactlyAtTheLowerBoundIsPlausible() {
        // 100 cycles over 1_000 days = 0.1 cycles/day exactly.
        assertTrue(DumpsysBatteryParser.isPlausibleCycleRate(100, firstUseDateEpochDay = 0L, todayEpochDay = 1_000L))
    }

    @Test
    fun cycleRateJustBelowTheLowerBoundIsNotPlausible() {
        // 99 cycles over 1_000 days = 0.099 cycles/day, just under 0.1.
        assertFalse(DumpsysBatteryParser.isPlausibleCycleRate(99, firstUseDateEpochDay = 0L, todayEpochDay = 1_000L))
    }

    @Test
    fun cycleRateExactlyAtTheUpperBoundIsPlausible() {
        // 3_000 cycles over 1_000 days = 3.0 cycles/day exactly.
        assertTrue(
            DumpsysBatteryParser.isPlausibleCycleRate(3_000, firstUseDateEpochDay = 0L, todayEpochDay = 1_000L)
        )
    }

    @Test
    fun cycleRateJustAboveTheUpperBoundIsNotPlausible() {
        // 3_001 cycles over 1_000 days = 3.001 cycles/day, just over 3.0.
        assertFalse(
            DumpsysBatteryParser.isPlausibleCycleRate(3_001, firstUseDateEpochDay = 0L, todayEpochDay = 1_000L)
        )
    }

    @Test
    fun aMissingFirstUseDateCannotBeCrossCheckedSoTheRateIsTreatedAsPlausible() {
        // This guard validates the interpretation, not the battery: with no first-use
        // date to check against, there is no evidence the ÷100 interpretation is wrong,
        // so it is not rejected merely because the number looks unusually high.
        assertTrue(
            DumpsysBatteryParser.isPlausibleCycleRate(100_000, firstUseDateEpochDay = null, todayEpochDay = 1_000L)
        )
    }

    @Test
    fun aFirstUseDateNotYetReachedCannotBeCrossCheckedSoTheRateIsTreatedAsPlausible() {
        // firstUseDateEpochDay after todayEpochDay -- a corrupted or genuinely
        // future-dated field. daysSinceFirstUse is negative here; this must not crash and
        // must not manufacture a rejection out of a different field's bad data.
        assertTrue(
            DumpsysBatteryParser.isPlausibleCycleRate(100_000, firstUseDateEpochDay = 2_000L, todayEpochDay = 1_000L)
        )
    }

    @Test
    fun aFirstUseDateOfTodayCannotBeCrossCheckedSoTheRateIsTreatedAsPlausible() {
        // daysSinceFirstUse == 0 -- a same-day first use. A naive division would be by
        // zero; guarded the same way as a future date, not as its own special case.
        assertTrue(
            DumpsysBatteryParser.isPlausibleCycleRate(100_000, firstUseDateEpochDay = 1_000L, todayEpochDay = 1_000L)
        )
    }
}

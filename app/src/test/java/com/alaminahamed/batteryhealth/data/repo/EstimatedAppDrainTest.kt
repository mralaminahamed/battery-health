package com.alaminahamed.batteryhealth.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimatedAppDrainTest {

    @Test
    fun eachAppGetsItsShareOfTheMeasuredDischarge() {
        // 1200 mAh over the window; 3h/1h/2h of foreground time, so 1/2, 1/6, 1/3.
        val rows = EstimatedAppDrain.apportion(
            totalDischargeMah = 1200.0,
            foregroundMsByPackage = mapOf(
                "com.a" to 3 * 3_600_000L,
                "com.b" to 1 * 3_600_000L,
                "com.c" to 2 * 3_600_000L,
            ),
        )
        assertEquals(600.0, rows.first { it.packageName == "com.a" }.estimatedMah, 0.001)
        assertEquals(200.0, rows.first { it.packageName == "com.b" }.estimatedMah, 0.001)
        assertEquals(400.0, rows.first { it.packageName == "com.c" }.estimatedMah, 0.001)
    }

    @Test
    fun sharesSumToOneHundredPercentAndRowsAreSortedLargestFirst() {
        val rows = EstimatedAppDrain.apportion(
            totalDischargeMah = 1000.0,
            foregroundMsByPackage = mapOf("com.a" to 60_000L, "com.b" to 180_000L),
        )
        assertEquals(listOf("com.b", "com.a"), rows.map { it.packageName })
        assertEquals(100.0, rows.sumOf { it.sharePct }, 0.001)
    }

    @Test
    fun zeroTotalForegroundTimeYieldsNoRowsRatherThanDividingByZero() {
        // Also the divide-by-zero guard. A device that was asleep for the whole window has
        // nothing to apportion, and inventing rows of 0 mAh would claim every app drew
        // nothing when the truth is that nothing is known.
        assertTrue(
            EstimatedAppDrain.apportion(
                totalDischargeMah = 1000.0,
                foregroundMsByPackage = mapOf("com.a" to 0L, "com.b" to 0L),
            ).isEmpty(),
        )
        assertTrue(
            EstimatedAppDrain.apportion(totalDischargeMah = 1000.0, foregroundMsByPackage = emptyMap()).isEmpty(),
        )
    }

    @Test
    fun packagesWithNoForegroundTimeAreOmittedNotListedAsZero() {
        val rows = EstimatedAppDrain.apportion(
            totalDischargeMah = 500.0,
            foregroundMsByPackage = mapOf("com.a" to 60_000L, "com.idle" to 0L),
        )
        assertEquals(listOf("com.a"), rows.map { it.packageName })
    }

    @Test
    fun subMinuteForegroundTimeIsExcludedAsNoiseNotFlooredToZero() {
        // 59 seconds -- one tick under the display's one-minute floor. Without this guard
        // the row would read "0 m on screen" beside a non-zero mAh figure and a non-zero
        // share, a self-contradiction Formatters.duration's own floor would otherwise
        // produce for every sub-minute row. A regression to the old "any positive value"
        // filter would let "com.a" back in here.
        val rows = EstimatedAppDrain.apportion(
            totalDischargeMah = 1000.0,
            foregroundMsByPackage = mapOf("com.a" to 59_000L, "com.b" to 3_600_000L),
        )
        assertEquals(listOf("com.b"), rows.map { it.packageName })
    }
}

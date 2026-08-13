package com.mralaminahamed.batteryhealth.ui.charts

import com.mralaminahamed.batteryhealth.domain.LevelPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartGeometryTest {

    private val minute = 60_000L

    @Test
    fun contiguousPointsFormASingleSegment() {
        val points = listOf(
            LevelPoint(0, 40),
            LevelPoint(15 * minute, 41),
            LevelPoint(30 * minute, 42),
        )
        val segments = ChartGeometry.splitOnGaps(points, ChartGeometry.GAP_THRESHOLD_MS)
        assertEquals(1, segments.size)
        assertEquals(3, segments.first().size)
    }

    @Test
    fun aGapBecomesABreakRatherThanAnInterpolatedLine() {
        // Doze can suspend sampling for hours. Drawing straight through that gap would
        // invent data, so the series splits instead.
        val points = listOf(
            LevelPoint(0, 40),
            LevelPoint(15 * minute, 41),
            LevelPoint(300 * minute, 20),
            LevelPoint(315 * minute, 19),
        )
        val segments = ChartGeometry.splitOnGaps(points, ChartGeometry.GAP_THRESHOLD_MS)
        assertEquals(2, segments.size)
        assertEquals(2, segments[0].size)
        assertEquals(2, segments[1].size)
    }

    @Test
    fun emptyInputYieldsNoSegments() {
        assertTrue(ChartGeometry.splitOnGaps(emptyList(), ChartGeometry.GAP_THRESHOLD_MS).isEmpty())
    }

    @Test
    fun aSinglePointIsItsOwnSegment() {
        val segments = ChartGeometry.splitOnGaps(listOf(LevelPoint(0, 40)), ChartGeometry.GAP_THRESHOLD_MS)
        assertEquals(1, segments.size)
        assertEquals(1, segments.first().size)
    }

    @Test
    fun unorderedInputIsSortedBeforeSplitting() {
        val points = listOf(
            LevelPoint(30 * minute, 42),
            LevelPoint(0, 40),
            LevelPoint(15 * minute, 41),
        )
        val segments = ChartGeometry.splitOnGaps(points, ChartGeometry.GAP_THRESHOLD_MS)
        assertEquals(1, segments.size)
        assertEquals(listOf(40, 41, 42), segments.first().map { it.levelPct })
    }

    @Test
    fun thresholdIsExclusiveSoAnExactlyOnTimeSampleStaysConnected() {
        val points = listOf(LevelPoint(0, 40), LevelPoint(ChartGeometry.GAP_THRESHOLD_MS, 41))
        assertEquals(1, ChartGeometry.splitOnGaps(points, ChartGeometry.GAP_THRESHOLD_MS).size)
    }
}

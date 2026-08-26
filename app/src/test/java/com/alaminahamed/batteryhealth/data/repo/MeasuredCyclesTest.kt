package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class MeasuredCyclesTest {

    private val design = 5000 // mAh, the SM-S948B this was verified against

    @Test
    fun oneDesignCapacityOfChargeIsOneCycle() {
        assertEquals(
            Reading.Available(1, Source.Measured),
            MeasuredCycles.fromSessions(listOf(5_000_000L), design),
        )
    }

    /** The whole point: partial charges accumulate into whole cycles. */
    @Test
    fun partialChargesAccumulate() {
        val halves = listOf(2_500_000L, 2_500_000L)
        assertEquals(
            Reading.Available(1, Source.Measured),
            MeasuredCycles.fromSessions(halves, design),
        )
    }

    /**
     * Truncated rather than rounded. "1 cycle" after six tenths of one overstates measured
     * wear, and every other derived figure in this app errs the same way.
     */
    @Test
    fun aPartialCycleIsNotRoundedUp() {
        assertEquals(
            Reading.Available(0, Source.Measured),
            MeasuredCycles.fromSessions(listOf(3_000_000L), design),
        )
        assertEquals(
            Reading.Available(1, Source.Measured),
            MeasuredCycles.fromSessions(listOf(9_900_000L), design),
        )
    }

    /**
     * A discharge is not a negative cycle. Subtracting one would let ordinary use cancel
     * charge that genuinely went through the cell, under-reporting wear.
     */
    @Test
    fun dischargesDoNotCancelCharges() {
        assertEquals(
            Reading.Available(1, Source.Measured),
            MeasuredCycles.fromSessions(listOf(5_000_000L, -4_000_000L), design),
        )
    }

    @Test
    fun zeroLengthSessionsAreIgnored() {
        assertEquals(
            Reading.NotYetMeasured,
            MeasuredCycles.fromSessions(listOf(0L, 0L), design),
        )
    }

    @Test
    fun nothingRecordedYetIsNotYetMeasured() {
        assertEquals(Reading.NotYetMeasured, MeasuredCycles.fromSessions(emptyList(), design))
    }

    /**
     * A cycle count against an unknown cycle size is not a number, so it is refused
     * outright rather than computed against a guess -- the same rule the health percentage
     * follows.
     */
    @Test
    fun withoutADesignCapacityThereIsNoSuchThingAsACycle() {
        assertEquals(Reading.Unsupported, MeasuredCycles.fromSessions(listOf(5_000_000L), null))
        assertEquals(Reading.Unsupported, MeasuredCycles.fromSessions(listOf(5_000_000L), 0))
        assertEquals(Reading.Unsupported, MeasuredCycles.fromSessions(listOf(5_000_000L), -100))
    }

    @Test
    fun manyRealSessionsAddUp() {
        // Twenty 40% top-ups of a 5000 mAh cell: 20 * 2000 mAh = 8 full cycles.
        val topUps = List(20) { 2_000_000L }
        assertEquals(
            Reading.Available(8, Source.Measured),
            MeasuredCycles.fromSessions(topUps, design),
        )
    }
}

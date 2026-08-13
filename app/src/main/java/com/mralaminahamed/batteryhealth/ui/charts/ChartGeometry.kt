package com.mralaminahamed.batteryhealth.ui.charts

import com.mralaminahamed.batteryhealth.domain.LevelPoint

object ChartGeometry {

    /**
     * Three times the 15-minute baseline interval. Anything longer is a genuine gap in
     * sampling, usually Doze, and must be drawn as a break.
     */
    const val GAP_THRESHOLD_MS = 45L * 60 * 1000

    /**
     * Splits a series wherever sampling stopped. Drawing one continuous line across a
     * gap would assert a level trajectory that was never measured.
     *
     * Sorts first so an unordered input cannot be mistaken for a gap (or mask one): the
     * gap check only ever compares a point to its immediate, time-ordered predecessor.
     * The threshold comparison is strictly greater-than, so a gap exactly equal to
     * [thresholdMs] stays connected -- only a gap that exceeds it is a break.
     */
    fun splitOnGaps(points: List<LevelPoint>, thresholdMs: Long): List<List<LevelPoint>> {
        if (points.isEmpty()) return emptyList()

        val ordered = points.sortedBy { it.timestampMs }
        val segments = mutableListOf<MutableList<LevelPoint>>()
        var current = mutableListOf(ordered.first())

        for (index in 1 until ordered.size) {
            val gap = ordered[index].timestampMs - ordered[index - 1].timestampMs
            if (gap > thresholdMs) {
                segments += current
                current = mutableListOf()
            }
            current += ordered[index]
        }
        segments += current
        return segments
    }
}

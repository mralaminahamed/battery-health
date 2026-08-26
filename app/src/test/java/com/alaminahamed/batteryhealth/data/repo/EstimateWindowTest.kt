package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the window rule [EstimateWindow.compute] exists for: it must query foreground time
 * over the *samples'* own span, never the requested lookback -- a card reading "over the
 * past 3 h 0 m" above a row reading "10 h 2 m on screen" is impossible on its face, since
 * the two numbers would describe different periods. Every case the rule governs gets its
 * own test: a span entirely unplugged, a span entirely plugged, a span with one plug
 * transition, and a span shorter than the baseline sampling interval.
 */
class EstimateWindowTest {

    private fun sample(ts: Long, level: Int, counter: Long?, plugged: Int = 0) = SampleEntity(
        timestampMs = ts,
        levelPct = level,
        chargeCounterUah = counter,
        currentUa = null,
        voltageMv = null,
        tempDeciC = null,
        statusCode = if (plugged == 0) 3 else 2,
        pluggedCode = plugged,
        screenOn = true,
        sessionId = null,
    )

    /** Records every `(fromMs, toMs)` pair [fn] is invoked with, standing in for
     * `ForegroundUsageSource.query` with no `Context` in sight. */
    private class RecordingQuery(private val result: Map<String, Long> = emptyMap()) {
        val calls = mutableListOf<Pair<Long, Long>>()
        val fn: (Long, Long) -> Map<String, Long> = { fromMs, toMs ->
            calls += fromMs to toMs
            result
        }
    }

    // Requested bounds deliberately far from the samples' own span on both ends, so a
    // regression that queries the requested window instead of the actual one cannot
    // coincide with the correct answer by accident.
    private val REQUESTED_START = -3_600_000L
    private val REQUESTED_END = 86_400_000L

    @Test
    fun aSpanEntirelyUnpluggedQueriesTheActualSampleSpanNotTheRequestedLookback() {
        // The samples on hand span 3h (0 to 10_800_000), well inside the 24h request
        // window above -- a fresh install, a pruned history, or a device that has not run
        // a full day yet. Reverting the query below to use REQUESTED_START/REQUESTED_END
        // reproduces "over the past 3 h" above "10 h 2 m on screen" -- two numbers
        // describing different periods.
        val samples = listOf(sample(0, 90, 4_500_000), sample(10_800_000, 80, 4_000_000))
        val query = RecordingQuery(mapOf("com.a" to 3_600_000L))

        val result = EstimateWindow.compute(
            samples = samples,
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = true,
            queryForegroundMs = query.fn,
        )

        assertEquals(listOf(0L to 10_800_000L), query.calls)
        assertEquals(0L, result.windowStartMs)
        assertEquals(10_800_000L, result.windowEndMs)
        assertEquals(500.0, result.totalDischargeMah!!, 0.001)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun aSpanEntirelyPluggedHasNoDischargeFigureAndNeverQueriesForegroundTime() {
        // Every interval has both endpoints plugged, so WindowDischarge.mah excludes all
        // of them and neither the counter nor the level path is ever satisfied -- the
        // discharge total is unknown, not zero. An unknown rate means the foreground query
        // would only ever be discarded, so it must not run at all -- the live Binder call
        // EstimateWindow's own doc says an unknown-rate window must not make.
        val samples = listOf(
            sample(0, 90, 4_500_000, plugged = 2),
            sample(900_000, 85, 4_250_000, plugged = 2),
            sample(1_800_000, 80, 4_000_000, plugged = 2),
        )
        val query = RecordingQuery(mapOf("com.a" to 3_600_000L))

        val result = EstimateWindow.compute(
            samples = samples,
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = true,
            queryForegroundMs = query.fn,
        )

        assertTrue(query.calls.isEmpty())
        assertNull(result.totalDischargeMah)
        assertTrue(result.entries.isEmpty())
        // The span itself is still the samples' own, honest span -- an unknown discharge
        // rate does not mean an unknown window, and EstimatedDrainReading never reads
        // these bounds for this case anyway (entries is empty, so the estimate can never
        // become Available).
        assertEquals(0L, result.windowStartMs)
        assertEquals(1_800_000L, result.windowEndMs)
    }

    @Test
    fun aSpanWithOnePlugTransitionStillQueriesTheWholeSampleSpanNotJustTheUnpluggedPortion() {
        // u -> p -> p -> u (WindowDischargeTest's own fixture for this shape): the
        // fully-plugged middle interval is excluded from the *discharge* sum, but the
        // foreground-time query below still spans the samples' full 0..2_700_000 range,
        // deliberately, per EstimateWindow's own doc -- summing narrower
        // per-unplugged-interval queries back together would multiply
        // UsageStatsManager's own documented bucket-overshoot error instead of fixing it.
        // This test pins that choice: if a future change tries to narrow the query to the
        // unplugged sub-intervals only, this assertion catches it.
        val samples = listOf(
            sample(0, 80, 4_000_000),
            sample(900_000, 75, 3_900_000, plugged = 2),
            sample(1_800_000, 73, 3_800_000, plugged = 2),
            sample(2_700_000, 70, 3_700_000),
        )
        val query = RecordingQuery(mapOf("com.a" to 1_800_000L))

        val result = EstimateWindow.compute(
            samples = samples,
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = true,
            queryForegroundMs = query.fn,
        )

        assertEquals(listOf(0L to 2_700_000L), query.calls)
        assertEquals(200.0, result.totalDischargeMah!!, 0.001) // the two transitions only
    }

    @Test
    fun aSpanShorterThanTheBaselineSampleIntervalIsQueriedExactlyAsIs() {
        // 5 minutes apart -- shorter than BaselineSampleWorker's own 15-minute period, the
        // shortest span this app would ordinarily produce two samples for (e.g. right
        // after a manual sample or a plug/unplug event). The window rule does not pad or
        // round this up to any larger bucket; it queries exactly what the samples cover,
        // same as every other case above.
        val samples = listOf(sample(0, 50, 1_000_000), sample(300_000, 49, 990_000))
        val query = RecordingQuery(mapOf("com.a" to 120_000L))

        val result = EstimateWindow.compute(
            samples = samples,
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = true,
            queryForegroundMs = query.fn,
        )

        assertEquals(listOf(0L to 300_000L), query.calls)
        assertEquals(10.0, result.totalDischargeMah!!, 0.001) // 10,000 uAh
    }

    @Test
    fun notHoldingUsageAccessNeverQueriesForegroundTimeEitherEvenWithAKnownDischargeRate() {
        val samples = listOf(sample(0, 90, 4_500_000), sample(900_000, 80, 4_000_000))
        val query = RecordingQuery(mapOf("com.a" to 600_000L))

        val result = EstimateWindow.compute(
            samples = samples,
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = false,
            queryForegroundMs = query.fn,
        )

        assertTrue(query.calls.isEmpty())
        assertTrue(result.entries.isEmpty())
        // The discharge figure itself is still computed and reported -- only the
        // foreground split needs usage access, not WindowDischarge's own arithmetic.
        assertEquals(500.0, result.totalDischargeMah!!, 0.001)
    }

    @Test
    fun noSamplesFallsBackToTheRequestedBoundsAndNeverQueries() {
        val query = RecordingQuery(mapOf("com.a" to 600_000L))

        val result = EstimateWindow.compute(
            samples = emptyList(),
            requestedStartMs = REQUESTED_START,
            requestedEndMs = REQUESTED_END,
            designCapacityMah = 5_000,
            usageAccessGranted = true,
            queryForegroundMs = query.fn,
        )

        assertTrue(query.calls.isEmpty())
        assertNull(result.totalDischargeMah)
        assertTrue(result.entries.isEmpty())
        assertEquals(REQUESTED_START, result.windowStartMs)
        assertEquals(REQUESTED_END, result.windowEndMs)
    }
}

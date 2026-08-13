package com.mralaminahamed.batteryhealth.sampling

import com.mralaminahamed.batteryhealth.data.local.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAggregatorTest {

    private fun sample(
        timestampMs: Long,
        levelPct: Int = 50,
        chargeCounterUah: Long? = 2_000_000,
        currentUa: Int? = 1_000_000,
        voltageMv: Int? = 4_000,
        tempDeciC: Int? = 370,
        screenOn: Boolean = false,
    ) = SampleEntity(
        timestampMs = timestampMs,
        levelPct = levelPct,
        chargeCounterUah = chargeCounterUah,
        currentUa = currentUa,
        voltageMv = voltageMv,
        tempDeciC = tempDeciC,
        statusCode = 2,
        pluggedCode = 2,
        screenOn = screenOn,
        sessionId = 1,
    )

    @Test
    fun emptyInputAggregatesToNothingRatherThanZeroes() {
        val aggregate = SessionAggregator.aggregate(emptyList())
        assertNull(aggregate.endLevelPct)
        assertNull(aggregate.endCounterUah)
        assertNull(aggregate.peakTempDeciC)
        assertNull(aggregate.avgMilliwatts)
        assertEquals(0L, aggregate.screenOnMs)
        assertNull(aggregate.coulombUah)
    }

    @Test
    fun endValuesComeFromTheLastSample() {
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(1_000, levelPct = 20, chargeCounterUah = 1_000_000),
                sample(2_000, levelPct = 80, chargeCounterUah = 4_000_000),
            )
        )
        assertEquals(80, aggregate.endLevelPct)
        // Long suffix required: endCounterUah is Long?, so JUnit resolves the boxing
        // assertEquals(Object, Object) overload rather than the primitive-long one --
        // without the L, a bare Int literal boxes to Integer and never equals a Long.
        assertEquals(4_000_000L, aggregate.endCounterUah)
    }

    @Test
    fun peakTemperatureIsTheMaximumNotTheLast() {
        val aggregate = SessionAggregator.aggregate(
            listOf(sample(1_000, tempDeciC = 370), sample(2_000, tempDeciC = 402), sample(3_000, tempDeciC = 366))
        )
        assertEquals(402, aggregate.peakTempDeciC)
    }

    @Test
    fun averagePowerUsesOnlySamplesWithBothVoltageAndCurrent() {
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(1_000, voltageMv = 4_000, currentUa = 1_000_000), // 4000 mW
                sample(2_000, voltageMv = 4_000, currentUa = 2_000_000), // 8000 mW
                sample(3_000, voltageMv = null, currentUa = 3_000_000),  // ignored
                sample(4_000, voltageMv = 4_000, currentUa = null),      // ignored
            )
        )
        assertEquals(6_000, aggregate.avgMilliwatts)
    }

    @Test
    fun averagePowerIsNullWhenNoSampleHasBothInputs() {
        val aggregate = SessionAggregator.aggregate(listOf(sample(1_000, voltageMv = null)))
        assertNull(aggregate.avgMilliwatts)
    }

    @Test
    fun screenOnTimeSumsTheIntervalsThatBeganWithTheScreenOn() {
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, screenOn = true),      // 0 -> 5000 counts
                sample(5_000, screenOn = false), // 5000 -> 9000 does not
                sample(9_000, screenOn = true),  // final sample has no following interval
            )
        )
        assertEquals(5_000L, aggregate.screenOnMs)
    }

    @Test
    fun peakTemperatureIsNullWhenNoSampleRecordedOne() {
        assertNull(SessionAggregator.aggregate(listOf(sample(1_000, tempDeciC = null))).peakTempDeciC)
    }

    @Test
    fun screenOnTimeExcludesAStalledIntervalTheSameWayCoulombDoes() {
        // 0 -> 5_000 is a normal interval and counts (5_000). 5_000 -> 305_000 is a
        // 300_000ms (5 minute) stall -- a gap is missing data, not a plateau, for
        // screen-on time exactly as it is for coulombUah, so it must not count even
        // though the screen-on sample sits right at its left endpoint. Without the
        // shared gap guard this would total 305_000 instead of 5_000.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, screenOn = true),
                sample(5_000, screenOn = true),
                sample(305_000, screenOn = true),
            )
        )
        assertEquals(5_000L, aggregate.screenOnMs)
    }

    // --- coulombUah: Sigma(I * dt) across the session, left-endpoint attribution ---
    //
    // Charge(uAh) = Current(uA) * Time(h) = Current(uA) * Time(ms) / 3_600_000, since
    // there are 3_600_000 ms per hour. Every hand-computed value below uses currentUa =
    // 720_000 uA (0.72 A) over a 5_000 ms interval, chosen so the division is exact:
    // 720_000 * 5_000 / 3_600_000 = 3_600_000_000 / 3_600_000 = 1_000 uAh per interval.

    @Test
    fun integratesCurrentOverEqualIntervalsCleanRun() {
        // Three 5-second intervals at a constant 720_000 uA: 1_000 uAh each, 3_000 total.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000),
                sample(5_000, currentUa = 720_000),
                sample(10_000, currentUa = 720_000),
                sample(15_000, currentUa = 720_000),
            )
        )
        assertEquals(3_000L, aggregate.coulombUah)
    }

    @Test
    fun gapLongerThanThresholdIsExcludedFromIntegration() {
        // 0->5_000 (5s, included: 1_000 uAh), 5_000->305_000 (a 5-minute stall, excluded
        // entirely), 305_000->310_000 (5s, included: 1_000 uAh). The stalled interval must
        // not fabricate charge, but must also not suppress the interval that follows it.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000),
                sample(5_000, currentUa = 720_000),
                sample(305_000, currentUa = 720_000),
                sample(310_000, currentUa = 720_000),
            )
        )
        assertEquals(2_000L, aggregate.coulombUah)
    }

    @Test
    fun gapExactlyAtTheThresholdIsStillIncluded() {
        // "Longer than 30 seconds" excludes strictly-greater intervals only; exactly 30s
        // is the six-times-cadence boundary itself and must still integrate.
        // 720_000 * 30_000 / 3_600_000 = 6_000 uAh.
        val aggregate = SessionAggregator.aggregate(
            listOf(sample(0, currentUa = 720_000), sample(30_000, currentUa = 720_000))
        )
        assertEquals(6_000L, aggregate.coulombUah)
    }

    @Test
    fun gapOneMillisecondPastTheThresholdIsExcluded() {
        val aggregate = SessionAggregator.aggregate(
            listOf(sample(0, currentUa = 720_000), sample(30_001, currentUa = 720_000))
        )
        assertNull(aggregate.coulombUah)
    }

    @Test
    fun nullCurrentSkipsOnlyItsOwnIntervalNotTheFollowingOne() {
        // 0->5_000 attributed to sample0 (720_000, included: 1_000 uAh); 5_000->10_000
        // attributed to sample1 (null current, excluded); 10_000->15_000 attributed to
        // sample2 (720_000, included: 1_000 uAh). The null-current sample must not also
        // swallow the interval that starts at the following sample.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000),
                sample(5_000, currentUa = null),
                sample(10_000, currentUa = 720_000),
                sample(15_000, currentUa = 720_000),
            )
        )
        assertEquals(2_000L, aggregate.coulombUah)
    }

    @Test
    fun noUsableIntervalYieldsNullRatherThanZero() {
        val allNullCurrent = SessionAggregator.aggregate(
            listOf(sample(0, currentUa = null), sample(5_000, currentUa = null))
        )
        assertNull(allNullCurrent.coulombUah)

        val singleSampleHasNoInterval = SessionAggregator.aggregate(
            listOf(sample(0, currentUa = 720_000))
        )
        assertNull(singleSampleHasNoInterval.coulombUah)
    }
}

package com.alaminahamed.batteryhealth.sampling

import com.alaminahamed.batteryhealth.data.local.SampleEntity
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
        currentRawUnits: Int? = null,
        // Defaulted to true (an earned scale) so every test above the
        // currentScaleValidated section can exercise coulombUah without needing to know
        // this gate exists; the tests below are the ones that actually flip it.
        currentScaleValidated: Boolean? = true,
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
        currentRawUnits = currentRawUnits,
        currentScaleValidated = currentScaleValidated,
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
        assertNull(aggregate.rawCurrentIntegral)
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

    // --- rawCurrentIntegral: Sigma(rawUnits * dt), the untouched register value, kept
    // separately from coulombUah so CurrentScaleDetector.fromCounterAgreement always gets a
    // genuinely unscaled integral to test its two unit hypotheses against -- never a value
    // BatteryManagerSource has already multiplied by a per-reading magnitude guess, which
    // would let a correct guess get mistaken for confirmation of the wrong scale. Every
    // hand-computed value below uses currentRawUnits = 2_409 (the exact raw register value
    // this defect was found with) over 5_000 ms intervals: 2_409 * 5_000 = 12_045_000 per
    // interval.

    @Test
    fun integratesRawUnitsOverEqualIntervalsCleanRun() {
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentRawUnits = 2_409),
                sample(5_000, currentRawUnits = 2_409),
                sample(10_000, currentRawUnits = 2_409),
                sample(15_000, currentRawUnits = 2_409),
            )
        )
        assertEquals(36_135_000L, aggregate.rawCurrentIntegral)
    }

    @Test
    fun gapLongerThanThresholdIsExcludedFromTheRawIntegralTheSameWayCoulombIs() {
        // 0->5_000 (included: 12_045_000), 5_000->305_000 (a 5-minute stall, excluded
        // entirely), 305_000->310_000 (included: 12_045_000). Total 24_090_000.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentRawUnits = 2_409),
                sample(5_000, currentRawUnits = 2_409),
                sample(305_000, currentRawUnits = 2_409),
                sample(310_000, currentRawUnits = 2_409),
            )
        )
        assertEquals(24_090_000L, aggregate.rawCurrentIntegral)
    }

    @Test
    fun nullRawUnitsSkipsOnlyItsOwnIntervalNotTheFollowingOne() {
        // 0->5_000 attributed to sample0 (2_409, included: 12_045_000); 5_000->10_000
        // attributed to sample1 (null raw units, excluded); 10_000->15_000 attributed to
        // sample2 (2_409, included: 12_045_000). Total 24_090_000.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentRawUnits = 2_409),
                sample(5_000, currentRawUnits = null),
                sample(10_000, currentRawUnits = 2_409),
                sample(15_000, currentRawUnits = 2_409),
            )
        )
        assertEquals(24_090_000L, aggregate.rawCurrentIntegral)
    }

    @Test
    fun noUsableRawIntervalYieldsNullRatherThanZero() {
        val allNullRawUnits = SessionAggregator.aggregate(
            listOf(sample(0, currentRawUnits = null), sample(5_000, currentRawUnits = null))
        )
        assertNull(allNullRawUnits.rawCurrentIntegral)

        val singleSampleHasNoInterval = SessionAggregator.aggregate(
            listOf(sample(0, currentRawUnits = 2_409))
        )
        assertNull(singleSampleHasNoInterval.rawCurrentIntegral)
    }

    @Test
    fun rawIntegralSurvivesWhenCurrentUaIsUnsupportedButTheRegisterValueWasCaptured() {
        // The exact scenario the split exists for: magnitude was ambiguous at write time
        // (a small idle-range reading with no validated scale yet), so BatteryManagerSource
        // reported currentUa as Unsupported (null here) -- but the untouched register value
        // was still captured. coulombUah must stay null (no usable currentUa interval
        // anywhere in this session), while rawCurrentIntegral must still have real data:
        // 50 * 5_000 = 250_000.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = null, currentRawUnits = 50),
                sample(5_000, currentUa = null, currentRawUnits = 50),
            )
        )
        assertNull(aggregate.coulombUah)
        assertEquals(250_000L, aggregate.rawCurrentIntegral)
    }

    // --- currentScaleValidated: the coulomb fallback must not launder a guessed scale
    // into a health figure the UI calls "Measured" -- HealthEstimator turns to
    // coulombUah precisely when the charge counter itself cannot be trusted, so a
    // guessed currentUa reaching that fallback is worse than an honest absence.

    @Test
    fun guessedScaleIntervalsAreExcludedFromCoulombIntegrationEvenThoughCurrentUaIsNonNull() {
        // 0->5_000 is attributed to sample(0) (validated by default: included, 1_000 uAh).
        // 5_000->10_000 is attributed to sample(5_000), whose currentUa is real but came
        // from CurrentScaleDetector.fromMagnitude's guess, not a counter-confirmed scale --
        // that interval must be excluded even though currentUa itself is non-null.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000),
                sample(5_000, currentUa = 720_000, currentScaleValidated = false),
                sample(10_000, currentUa = 720_000),
            )
        )
        assertEquals(1_000L, aggregate.coulombUah)
    }

    @Test
    fun sessionWithOnlyGuessedScaleIntervalsHasNoCoulombFigureAtAllRatherThanAWrongOne() {
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000, currentScaleValidated = false),
                sample(5_000, currentUa = 720_000, currentScaleValidated = false),
            )
        )
        assertNull(aggregate.coulombUah)
    }

    @Test
    fun unknownProvenanceIsTreatedTheSameAsAGuessNotTheSameAsValidated() {
        // A pre-migration-4 row has currentScaleValidated = null, not false -- but this
        // fallback must refuse it exactly the same way: "we don't know this was earned"
        // is not "it was earned".
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000, currentScaleValidated = null),
                sample(5_000, currentUa = 720_000, currentScaleValidated = null),
            )
        )
        assertNull(aggregate.coulombUah)
    }

    @Test
    fun rawIntegralIsUnaffectedByCurrentScaleValidatedBecauseItIsDeliberatelyUnscaled() {
        // rawCurrentIntegral exists specifically to resolve a guess via
        // fromCounterAgreement, so it must keep accumulating raw register values from
        // guessed (or unvalidated) intervals -- gating it the same way coulombUah is
        // gated would make it impossible for a device to ever validate its scale in the
        // first place. 2_409 * 5_000 = 12_045_000, the same single-interval arithmetic
        // used throughout the rawCurrentIntegral section above.
        val aggregate = SessionAggregator.aggregate(
            listOf(
                sample(0, currentUa = 720_000, currentScaleValidated = false, currentRawUnits = 2_409),
                sample(5_000, currentUa = 720_000, currentScaleValidated = false, currentRawUnits = 2_409),
            )
        )
        assertNull(aggregate.coulombUah)
        assertEquals(12_045_000L, aggregate.rawCurrentIntegral)
    }
}

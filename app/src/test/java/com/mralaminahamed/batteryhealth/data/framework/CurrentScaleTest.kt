package com.mralaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentScaleTest {

    // --- fromMagnitude: immediate, per-reading guess from magnitude alone, refined by
    // charge state. Below the microamp ceiling, magnitude alone can never decide the
    // unit -- see the Critical fix in the function's own doc -- so every case in that
    // band is tested twice: once with isCharging = false, which must always abstain
    // (null), and once with isCharging = true, which is the one case allowed to answer.

    @Test
    fun realA35ReadingResolvesToMilliampsWhileCharging() {
        // The exact raw value this defect was found with: 2409 at a real 2.4 A charge.
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(2_409, isCharging = true))
    }

    @Test
    fun sameReadingAbstainsWithoutChargeState() {
        // Identical raw value to the case above, but with no charge-state evidence: 2409
        // uA (an ordinary idle draw) and 2409 mA (a real charging current) are both
        // plausible, so this must not assert either one.
        assertNull(CurrentScaleDetector.fromMagnitude(2_409, isCharging = false))
    }

    @Test
    fun genuineMicroampReadingResolvesToMicroampsRegardlessOfChargeState() {
        // The same real current (2.4 A), correctly reported in microamps. At or above the
        // ceiling, magnitude alone already decides -- charge state adds nothing here.
        assertEquals(
            CurrentScale.Microamps,
            CurrentScaleDetector.fromMagnitude(2_409_000, isCharging = false),
        )
        assertEquals(
            CurrentScale.Microamps,
            CurrentScaleDetector.fromMagnitude(2_409_000, isCharging = true),
        )
    }

    @Test
    fun negativeMagnitudeIsTreatedTheSameAsPositiveWhileCharging() {
        // A genuine small discharge current can read negative; sign must not change which
        // scale is inferred, only abs(rawCurrent) matters. Discharging is not charging, so
        // isCharging = true here models a device charging at this exact register value
        // that happens to read negative, not an actual discharge.
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(-2_409, isCharging = true))
    }

    @Test
    fun idleReadingBelowTheFloorAbstainsEvenWhileCharging() {
        // 50, at either scale, is too small to be a plausible charging current at all --
        // isCharging = true does not rescue a magnitude this far below the floor.
        assertNull(CurrentScaleDetector.fromMagnitude(50, isCharging = true))
    }

    @Test
    fun magnitudeJustBelowTheUnambiguousMilliampsFloorAbstainsEvenWhileCharging() {
        // 199 is one below the 200 mA floor -- still in the floor's own ambiguous band,
        // not yet a plausible charging current, regardless of charge state.
        assertNull(CurrentScaleDetector.fromMagnitude(199, isCharging = true))
    }

    @Test
    fun magnitudeAtTheUnambiguousMilliampsFloorAbstainsWithoutChargeState() {
        // Renamed from ...ResolvesToMilliamps: magnitude alone was never entitled to
        // assert here -- 200 uA (idle) and 200 mA (a slow charge) are both plausible, and
        // the previous version of this test pinned exactly the misclassification the
        // Critical fix removes.
        assertNull(CurrentScaleDetector.fromMagnitude(200, isCharging = false))
    }

    @Test
    fun magnitudeAtTheUnambiguousMilliampsFloorResolvesToMilliampsWhileCharging() {
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(200, isCharging = true))
    }

    @Test
    fun magnitudeJustBelowTheMicroampCeilingAbstainsWithoutChargeState() {
        // Renamed from ...ResolvesToMilliamps: this is the exact case the brief calls out
        // by direction -- 19,999 as milliamps is ~20 A, which no phone does, while 19,999
        // uA is an ordinary idle draw, so the milliamp assertion here was the *less*
        // plausible reading, not the safer default the old code treated it as.
        assertNull(CurrentScaleDetector.fromMagnitude(19_999, isCharging = false))
    }

    @Test
    fun magnitudeJustBelowTheMicroampCeilingResolvesToMilliampsWhileCharging() {
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(19_999, isCharging = true))
    }

    @Test
    fun magnitudeAtTheMicroampCeilingResolvesToMicroampsRegardlessOfChargeState() {
        assertEquals(CurrentScale.Microamps, CurrentScaleDetector.fromMagnitude(20_000, isCharging = false))
        assertEquals(CurrentScale.Microamps, CurrentScaleDetector.fromMagnitude(20_000, isCharging = true))
    }

    // --- fromCounterAgreement: authoritative check against the charge counter ---

    @Test
    fun picksMilliampsWhenIntegratedAsMicroampsIsAThousandTimesShortOfTheCounterDelta() {
        // Raw 2_000 held for 720_000 ms (12 minutes), as the untouched register value --
        // never a value this app has already scaled.
        // integratedRawMicroampMillis = 2_000 * 720_000 = 1_440_000_000.
        // asMicroamps = 1_440_000_000 / 3_600_000 = 400 -- 1000x short of the 400_000 uAh
        // the counter actually moved.
        // asMilliamps = 400 * 1_000 = 400_000 -- an exact match, error 0.
        val scale = CurrentScaleDetector.fromCounterAgreement(
            integratedRawMicroampMillis = 1_440_000_000L,
            counterDeltaUah = 400_000L,
        )
        assertEquals(CurrentScale.Milliamps, scale)
    }

    @Test
    fun picksMicroampsWhenTheIntegralAlreadyMatchesTheCounterDelta() {
        // integratedRawMicroampMillis chosen so asMicroamps lands exactly on the counter
        // delta: 400_000 * 3_600_000 = 1_440_000_000_000.
        // asMicroamps = 1_440_000_000_000 / 3_600_000 = 400_000 -- an exact match, error 0.
        // asMilliamps = 400_000 * 1_000 = 400_000_000 -- wildly over, error 999.
        val scale = CurrentScaleDetector.fromCounterAgreement(
            integratedRawMicroampMillis = 1_440_000_000_000L,
            counterDeltaUah = 400_000L,
        )
        assertEquals(CurrentScale.Microamps, scale)
    }

    @Test
    fun tinyCounterDeltaReturnsNullRegardlessOfTheIntegral() {
        // 40_000 uAh moved is below MIN_COUNTER_DELTA_UAH (50_000): quantisation would
        // swamp the comparison, so the evidence is too weak to decide either way.
        val scale = CurrentScaleDetector.fromCounterAgreement(
            integratedRawMicroampMillis = 1_440_000_000_000L,
            counterDeltaUah = 40_000L,
        )
        assertNull(scale)
    }

    @Test
    fun bothInterpretationsImplausibleReturnsNull() {
        // asMicroamps = 360_000_000_000 / 3_600_000 = 100_000 -- 80% short of 500_000.
        // asMilliamps = 100_000 * 1_000 = 100_000_000 -- absurdly over, error 199.
        // Neither is within the 40% band, so this is not decidable evidence (most likely a
        // synthesised counter), not a confident pick of the closer-but-still-wrong option.
        val scale = CurrentScaleDetector.fromCounterAgreement(
            integratedRawMicroampMillis = 360_000_000_000L,
            counterDeltaUah = 500_000L,
        )
        assertNull(scale)
    }

    @Test
    fun errorExactlyAtTheAcceptedThresholdIsStillAccepted() {
        // asMicroamps = 280_000, a 40.0% shortfall of 400_000 (280_000/400_000 = 0.70,
        // error = 0.30 -- pick a value that lands exactly at the 40% boundary instead):
        // counterDeltaUah = 400_000, asMicroamps = 240_000 -> error = 160_000/400_000 = 0.40.
        // integratedRawMicroampMillis = 240_000 * 3_600_000 = 864_000_000_000.
        val scale = CurrentScaleDetector.fromCounterAgreement(
            integratedRawMicroampMillis = 864_000_000_000L,
            counterDeltaUah = 400_000L,
        )
        assertEquals(CurrentScale.Microamps, scale)
    }
}

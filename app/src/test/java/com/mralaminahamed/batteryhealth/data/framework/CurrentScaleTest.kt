package com.mralaminahamed.batteryhealth.data.framework

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentScaleTest {

    // --- fromMagnitude: immediate, per-reading guess from magnitude alone ---

    @Test
    fun realA35ReadingResolvesToMilliampsByMagnitude() {
        // The exact raw value this defect was found with: 2409 at a real 2.4 A charge.
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(2_409))
    }

    @Test
    fun genuineMicroampReadingResolvesToMicroamps() {
        // The same real current (2.4 A), correctly reported in microamps.
        assertEquals(CurrentScale.Microamps, CurrentScaleDetector.fromMagnitude(2_409_000))
    }

    @Test
    fun negativeMagnitudeIsTreatedTheSameAsPositive() {
        // A genuine small discharge current can read negative; sign must not change which
        // scale is inferred, only abs(rawCurrent) matters.
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(-2_409))
    }

    @Test
    fun idleReadingBelowTheFloorReturnsNull() {
        // 50, at either scale, is too small to be unambiguous: 50 uA is a plausible idle
        // draw, and so is 50 mA.
        assertNull(CurrentScaleDetector.fromMagnitude(50))
    }

    @Test
    fun magnitudeJustBelowTheUnambiguousMilliampsFloorReturnsNull() {
        // 199 is one below the 200 mA floor that fromMagnitude was designed around -- still
        // in the ambiguous band, not yet unambiguous.
        assertNull(CurrentScaleDetector.fromMagnitude(199))
    }

    @Test
    fun magnitudeAtTheUnambiguousMilliampsFloorResolvesToMilliamps() {
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(200))
    }

    @Test
    fun magnitudeJustBelowTheMicroampCeilingResolvesToMilliamps() {
        assertEquals(CurrentScale.Milliamps, CurrentScaleDetector.fromMagnitude(19_999))
    }

    @Test
    fun magnitudeAtTheMicroampCeilingResolvesToMicroamps() {
        assertEquals(CurrentScale.Microamps, CurrentScaleDetector.fromMagnitude(20_000))
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

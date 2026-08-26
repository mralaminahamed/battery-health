package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowDischargeTest {

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

    @Test
    fun theCounterDropAcrossUnpluggedIntervalsIsPreferred() {
        val samples = listOf(
            sample(0, 90, 4_500_000),
            sample(900_000, 85, 4_250_000),
            sample(1_800_000, 80, 4_000_000),
        )
        // 500,000 uAh = 500 mAh from the counter. Design capacity deliberately set so the
        // level path would answer differently (10% of 3,000 mAh = 300 mAh): every other
        // fixture in this file happens to make the counter and level paths agree
        // numerically, which is why reversing the preference (level wins whenever usable)
        // would still leave those green. If the preference were ever reversed, this
        // assertion would see 300.0, not 500.0.
        assertEquals(500.0, WindowDischarge.mah(samples, designCapacityMah = 3_000)!!, 0.001)
    }

    @Test
    fun pluggedIntervalsAreExcludedSoChargingNeverCountsAsDrain() {
        // u -> p -> p -> u, with a real (not merely non-negative) counter drop across every
        // interval including the fully-plugged middle one. A 3-sample fixture of shape
        // (u, u, p, u) would contain no interval with BOTH endpoints plugged, so deleting
        // the whole exclusion clause would leave such a test green -- the rule it names
        // would be asserted by nothing. The middle interval here (t=900_000 to
        // t=1_800_000, both plugged) must be excluded outright regardless of its own
        // drop's sign; the two transition intervals either side of it are still decided by
        // the sign of their own drop, per WindowDischarge's own doc.
        val samples = listOf(
            sample(0, 80, 4_000_000),
            sample(900_000, 75, 3_900_000, plugged = 2), // u->p transition: 100 mAh, counted
            sample(1_800_000, 73, 3_800_000, plugged = 2), // p->p, real drop: must be EXCLUDED
            sample(2_700_000, 70, 3_700_000), // p->u transition: 100 mAh, counted
        )
        // 200,000 uAh = 200 mAh -- the two transition intervals only. Deleting the
        // exclusion clause would also count the middle interval's 100,000 uAh, giving
        // 300.0; a single-endpoint check (attributed to whichever sample is "the start")
        // would instead wrongly exclude the p->u transition (its start sample reads
        // plugged), giving 100.0. Only the both-endpoints rule gets this fixture right.
        assertEquals(200.0, WindowDischarge.mah(samples, designCapacityMah = 5_000)!!, 0.001)
    }

    @Test
    fun aRisingCounterOnAnUnpluggedIntervalIsIgnoredRatherThanSubtracted() {
        // A fuel-gauge reset, or a level correction. It is not negative drain.
        val samples = listOf(
            sample(0, 80, 4_000_000),
            sample(900_000, 82, 4_100_000),
            sample(1_800_000, 78, 3_900_000),
        )
        assertEquals(200.0, WindowDischarge.mah(samples, designCapacityMah = 5_000)!!, 0.001)
    }

    @Test
    fun withoutAUsableCounterItFallsBackToLevelTimesDesignCapacity() {
        val samples = listOf(
            sample(0, 90, null),
            sample(900_000, 80, null),
        )
        // 10% of 5000 mAh.
        assertEquals(500.0, WindowDischarge.mah(samples, designCapacityMah = 5_000)!!, 0.001)
    }

    @Test
    fun withNeitherACounterNorADesignCapacityTheAnswerIsUnknownNotZero() {
        val samples = listOf(sample(0, 90, null), sample(900_000, 80, null))
        assertNull(WindowDischarge.mah(samples, designCapacityMah = null))
    }

    @Test
    fun fewerThanTwoSamplesIsUnknownNotZero() {
        assertNull(WindowDischarge.mah(listOf(sample(0, 90, 4_500_000)), designCapacityMah = 5_000))
        assertNull(WindowDischarge.mah(emptyList(), designCapacityMah = 5_000))
    }

    @Test
    fun aWindowInWhichNothingDrainedIsZeroNotUnknown() {
        // The one case where 0 is the honest answer: two unplugged samples with an
        // identical counter AND an identical level genuinely measured no drain -- both
        // signals agree, so there is no contradicting evidence and the zero is trusted.
        // Distinguishing this from null is the whole reason the return type is nullable
        // rather than defaulting to 0.
        val samples = listOf(sample(0, 80, 4_000_000), sample(900_000, 80, 4_000_000))
        assertEquals(0.0, WindowDischarge.mah(samples, designCapacityMah = 5_000)!!, 0.001)
    }

    @Test
    fun aFlatCounterIsNotTrustedAsZeroDrainWhenTheLevelActuallyFell() {
        // A counter that reads the exact same value on every sample despite a real level
        // drop is a stuck or misreporting gauge, not evidence the battery held steady --
        // this project has already documented a manufacturer reporting a fixed value for a
        // field it does not track. counterUsable only means "two counter readings existed
        // and neither interval's drop was negative"; it is satisfied here even though the
        // counter never moved, and without the disagreement check this would return a
        // false "measured 0.00 mAh" while the very same samples recorded a 10% level drop.
        val samples = listOf(
            sample(0, 90, 4_000_000),
            sample(900_000, 80, 4_000_000),
        )
        // Counter total is 0 (flat) but level fell 10 points; falls back to level x design
        // capacity (10% of 5,000 mAh) instead of reporting the counter's false zero.
        assertEquals(500.0, WindowDischarge.mah(samples, designCapacityMah = 5_000)!!, 0.001)
    }
}

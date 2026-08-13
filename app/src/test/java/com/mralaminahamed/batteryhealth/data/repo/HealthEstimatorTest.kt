package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.domain.CapacityMethod
import com.mralaminahamed.batteryhealth.domain.CapacityObservation
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.domain.valueOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthEstimatorTest {

    private val estimator = HealthEstimator()

    /** Builds an observation whose counter delta implies exactly [fullUah]. */
    private fun counterObservation(id: Long, deltaLevelPct: Int, fullUah: Long) =
        CapacityObservation(
            sessionId = id,
            deltaLevelPct = deltaLevelPct,
            counterDeltaUah = fullUah * deltaLevelPct / 100,
            coulombUah = null,
        )

    private fun coulombObservation(id: Long, deltaLevelPct: Int, fullUah: Long) =
        CapacityObservation(
            sessionId = id,
            deltaLevelPct = deltaLevelPct,
            counterDeltaUah = null,
            coulombUah = fullUah * deltaLevelPct / 100,
        )

    @Test
    fun unknownDesignCapacityIsUnsupported() {
        val observations = List(3) { counterObservation(it.toLong(), 60, 4_300_000) }
        assertEquals(Reading.Unsupported, estimator.estimate(observations, designCapacityMah = null))
    }

    @Test
    fun fewerThanThreeQualifyingSessionsIsNotYetMeasured() {
        val observations = List(2) { counterObservation(it.toLong(), 60, 4_300_000) }
        assertEquals(Reading.NotYetMeasured, estimator.estimate(observations, 5000))
    }

    @Test
    fun narrowSessionsAreRejectedOutright() {
        // Four sessions, all below the 20-point floor: nothing qualifies.
        val observations = List(4) { counterObservation(it.toLong(), 15, 4_300_000) }
        assertEquals(Reading.NotYetMeasured, estimator.estimate(observations, 5000))
    }

    @Test
    fun reportsTheMedianRatherThanTheMostRecentSession() {
        val observations = listOf(
            counterObservation(1, 60, 4_300_000),
            counterObservation(2, 55, 4_300_000),
            counterObservation(3, 50, 3_000_000), // newest, an outlier
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        assertEquals(4_300_000, report.measuredFullUah)
        assertEquals(86, report.healthPct)
    }

    @Test
    fun threeWideSessionsExcludeTheNarrowOnesEntirely() {
        val observations = listOf(
            counterObservation(1, 50, 4_000_000),
            counterObservation(2, 55, 4_000_000),
            counterObservation(3, 60, 4_000_000),
            counterObservation(4, 20, 2_000_000),
            counterObservation(5, 22, 2_000_000),
            counterObservation(6, 24, 2_000_000),
            counterObservation(7, 25, 2_000_000),
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        // Median over all seven would be 2_000_000 (40%); wide-only gives 4_000_000.
        assertEquals(4_000_000, report.measuredFullUah)
        assertEquals(80, report.healthPct)
        assertEquals(3, report.sessionsUsed)
    }

    @Test
    fun fewerThanThreeWideSessionsFallsBackToAllQualifying() {
        val observations = listOf(
            counterObservation(1, 50, 4_600_000),
            counterObservation(2, 25, 4_200_000),
            counterObservation(3, 22, 4_000_000),
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        assertEquals(4_200_000, report.measuredFullUah)
        assertEquals(3, report.sessionsUsed)
    }

    @Test
    fun aCounterDerivedFromLevelIsRejectedInsteadOfReportingAPerfectBattery() {
        // Widely different windows all landing on exactly the design capacity means the
        // counter is synthesised from level, not measured.
        val observations = listOf(
            counterObservation(1, 25, 5_000_000),
            counterObservation(2, 45, 5_000_000),
            counterObservation(3, 70, 5_000_000),
        )
        assertEquals(Reading.Unsupported, estimator.estimate(observations, 5000))
    }

    @Test
    fun similarWindowsNearDesignAreNotTreatedAsDerived() {
        // Spread is only 4 points, so the derived-counter test must not fire.
        val observations = listOf(
            counterObservation(1, 50, 5_000_000),
            counterObservation(2, 52, 5_000_000),
            counterObservation(3, 54, 5_000_000),
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        assertEquals(100, report.healthPct)
    }

    @Test
    fun coulombObservationsAreUsedWhenNoCounterDeltaExists() {
        val observations = List(3) { coulombObservation(it.toLong(), 50, 4_100_000) }
        val reading = estimator.estimate(observations, 5000)
        val report = reading.valueOrNull()!!
        assertEquals(82, report.healthPct)
        assertEquals(CapacityMethod.Coulomb, report.method)
        assertEquals(Source.Measured, (reading as Reading.Available).source)
    }

    @Test
    fun healthIsClampedToOneHundred() {
        val observations = List(3) { counterObservation(it.toLong(), 60, 5_400_000) }
        assertEquals(100, estimator.estimate(observations, 5000).valueOrNull()!!.healthPct)
    }

    @Test
    fun observationsWithNoUsableCapacityAreIgnored() {
        val observations = listOf(
            counterObservation(1, 60, 4_300_000),
            counterObservation(2, 55, 4_300_000),
            CapacityObservation(3, deltaLevelPct = 60, counterDeltaUah = null, coulombUah = null),
        )
        assertEquals(Reading.NotYetMeasured, estimator.estimate(observations, 5000))
    }

    @Test
    fun evenNumberOfWideSessionsAveragesTheTwoMiddleMeasurementsRatherThanTheMean() {
        // Four sessions, all wide (delta == 100 so the counter round-trips exactly with no
        // truncation of its own), isolates the median's own even-count averaging step. The
        // fourth value is skewed well above the others so a mean-of-all implementation
        // would diverge from the true median instead of coincidentally landing on it.
        val observations = listOf(
            counterObservation(1, 100, 4_000_000),
            counterObservation(2, 100, 4_200_000),
            counterObservation(3, 100, 4_400_001),
            counterObservation(4, 100, 9_000_000),
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        // Sorted middle pair is 4_200_000 and 4_400_001; (4_200_000 + 4_400_001) / 2 =
        // 4_300_000 truncated, not 4_300_001 rounded. The mean of all four would instead be
        // (4_000_000 + 4_200_000 + 4_400_001 + 9_000_000) / 4 = 5_400_000 -- a different
        // number, so this test would catch a mean-based regression that the old, more
        // symmetric values could not.
        assertEquals(4_300_000, report.measuredFullUah)
        assertEquals(4, report.sessionsUsed)
        assertEquals(86, report.healthPct)
    }

    @Test
    fun wideNarrowingHidingASynthesisedCounterIsStillCaughtByTheFullSet() {
        // Six sessions, all implying exactly the design capacity (a counter synthesised as
        // level x design). The three widest (45, 48, 52) alone span only 7 points -- under
        // the 10-point detection threshold -- so if the derived-counter check ran only on
        // the post-narrowing "wide" set of three, it would never fire and this would report
        // a confident, wrong 100%. The full six-session set spans 32 points, well past the
        // threshold, and must still catch it.
        val observations = listOf(
            counterObservation(1, 20, 5_000_000),
            counterObservation(2, 25, 5_000_000),
            counterObservation(3, 30, 5_000_000),
            counterObservation(4, 45, 5_000_000),
            counterObservation(5, 48, 5_000_000),
            counterObservation(6, 52, 5_000_000),
        )
        assertEquals(Reading.Unsupported, estimator.estimate(observations, 5000))
    }

    @Test
    fun synthesisedCounterFallsBackToCoulombWhenCurrentIsReal() {
        // Three sessions whose counter is synthesised (all land exactly on design capacity,
        // spread 30 >= the detection threshold) but whose coulomb (integrated-current) data
        // is real and implies 76%. The estimator must retry from the coulomb column instead
        // of giving up, and must label the result Coulomb rather than Counter.
        val observations = listOf(
            CapacityObservation(1, deltaLevelPct = 20, counterDeltaUah = 1_000_000, coulombUah = 760_000),
            CapacityObservation(2, deltaLevelPct = 35, counterDeltaUah = 1_750_000, coulombUah = 1_330_000),
            CapacityObservation(3, deltaLevelPct = 50, counterDeltaUah = 2_500_000, coulombUah = 1_900_000),
        )
        val reading = estimator.estimate(observations, 5000)
        val report = reading.valueOrNull()!!
        // Coulomb-implied full capacity is 760_000 * 100 / 20 = 3_800_000 (and likewise for
        // the other two rows); 3_800_000 / 5_000_000 * 100 = 76.
        assertEquals(3_800_000, report.measuredFullUah)
        assertEquals(76, report.healthPct)
        assertEquals(CapacityMethod.Coulomb, report.method)
    }

    @Test
    fun mahScaleCounterIsUnsupportedRatherThanReportingADeadBattery() {
        // The counter is reported in mAh instead of uAh: every value lands ~1000x low
        // (5_000 implied full capacity against a 5_000_000 design). A dead battery and a
        // unit bug are indistinguishable at that extreme, so this must not clamp to a
        // confident 1% -- it must decline to answer. Uniform windows (spread 0) keep the
        // derived-counter check from firing first, so the plausibility band is what's
        // actually under test here.
        val observations = List(3) { counterObservation(it.toLong(), 60, 5_000) }
        assertEquals(Reading.Unsupported, estimator.estimate(observations, 5000))
    }

    @Test
    fun ratioJustInsideLowerPlausibilityBoundStillYieldsANumber() {
        // ratio = 2_050_000 / 5_000_000 = 0.41, just inside MIN_PLAUSIBLE_RATIO (0.40).
        val observations = List(3) { counterObservation(it.toLong(), 60, 2_050_000) }
        assertEquals(41, estimator.estimate(observations, 5000).valueOrNull()!!.healthPct)
    }

    @Test
    fun ratioJustInsideUpperPlausibilityBoundStillYieldsANumber() {
        // ratio = 6_450_000 / 5_000_000 = 1.29, just inside MAX_PLAUSIBLE_RATIO (1.30);
        // clamped to 100 by the separate 1..100 clamp, not rejected by the plausibility band.
        val observations = List(3) { counterObservation(it.toLong(), 60, 6_450_000) }
        assertEquals(100, estimator.estimate(observations, 5000).valueOrNull()!!.healthPct)
    }

    @Test
    fun duplicateSessionRowsDoNotCountTowardMinimumSessions() {
        // Session 1 appears twice (e.g. a re-delivered or double-recorded row). Only two
        // distinct sessions exist, so this must remain NotYetMeasured rather than letting
        // the duplicate stand in for a third, independent session.
        val observations = listOf(
            counterObservation(1, 60, 4_300_000),
            counterObservation(1, 60, 4_300_000),
            counterObservation(2, 55, 4_300_000),
        )
        assertEquals(Reading.NotYetMeasured, estimator.estimate(observations, 5000))
    }
}

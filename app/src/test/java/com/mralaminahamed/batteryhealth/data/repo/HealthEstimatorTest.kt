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
    fun evenNumberOfWideSessionsAveragesTheTwoMiddleMeasurements() {
        // Four sessions, all wide (delta == 100 so the counter round-trips exactly with no
        // truncation of its own), isolates the median's own even-count averaging step. The
        // two middle full-capacity values sum to an odd number, so integer division must
        // truncate the average down rather than round it.
        val observations = listOf(
            counterObservation(1, 100, 4_000_000),
            counterObservation(2, 100, 4_200_000),
            counterObservation(3, 100, 4_400_001),
            counterObservation(4, 100, 4_600_000),
        )
        val report = estimator.estimate(observations, 5000).valueOrNull()!!
        // sorted middle pair is 4_200_000 and 4_400_001; (4_200_000 + 4_400_001) / 2 = 4_300_000
        // truncated, not 4_300_001 rounded.
        assertEquals(4_300_000, report.measuredFullUah)
        assertEquals(4, report.sessionsUsed)
        assertEquals(86, report.healthPct)
    }
}

package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity
import com.alaminahamed.batteryhealth.data.local.SessionEntity
import com.alaminahamed.batteryhealth.domain.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import com.alaminahamed.batteryhealth.domain.ChargeSession
import org.junit.Test

class MappersTest {

    private fun entity(
        type: String = "CHARGE",
        endedAtMs: Long? = 9_000,
        endLevelPct: Int? = 80,
        startCounterUah: Long? = 1_000_000,
        endCounterUah: Long? = 3_580_000,
        coulombUah: Long? = null,
    ) = SessionEntity(
        id = 7,
        type = type,
        startedAtMs = 1_000,
        endedAtMs = endedAtMs,
        startLevelPct = 20,
        endLevelPct = endLevelPct,
        startCounterUah = startCounterUah,
        endCounterUah = endCounterUah,
        peakTempDeciC = 380,
        avgMilliwatts = 9_000,
        screenOnMs = 500,
        coulombUah = coulombUah,
    )

    @Test
    fun completedSessionMapsToDomain() {
        val session = entity().toDomain()!!
        assertEquals(7, session.id)
        assertEquals(SessionType.Charge, session.type)
        assertEquals(60, session.deltaLevelPct)
        assertEquals(8_000, session.durationMs)
    }

    @Test
    fun dischargeTypeMaps() {
        assertEquals(SessionType.Discharge, entity(type = "DISCHARGE").toDomain()!!.type)
    }

    @Test
    fun openSessionsDoNotMapBecauseTheyAreIncomplete() {
        assertNull(entity(endedAtMs = null).toDomain())
        assertNull(entity(endLevelPct = null).toDomain())
    }

    @Test
    fun unknownTypeStringDoesNotMap() {
        assertNull(entity(type = "SOMETHING_ELSE").toDomain())
    }

    @Test
    fun observationCarriesTheCounterDelta() {
        val observation = entity().toDomain()!!.toObservation()
        assertEquals(7, observation.sessionId)
        assertEquals(60, observation.deltaLevelPct)
        assertEquals(2_580_000L, observation.counterDeltaUah)
    }

    @Test
    fun observationHasNoCounterDeltaWhenEitherEndpointIsMissing() {
        assertNull(entity(startCounterUah = null).toDomain()!!.toObservation().counterDeltaUah)
        assertNull(entity(endCounterUah = null).toDomain()!!.toObservation().counterDeltaUah)
    }

    @Test
    fun decreasingCounterYieldsNoObservationDelta() {
        // A counter that went backwards means the fuel gauge reset mid-session.
        val observation = entity(startCounterUah = 3_000_000, endCounterUah = 1_000_000)
            .toDomain()!!.toObservation()
        assertNull(observation.counterDeltaUah)
    }

    @Test
    fun domainSessionCarriesTheRawCoulombValueUnfiltered() {
        // toDomain() is a straight field carry; filtering to positive-only happens in
        // toObservation(), not here.
        assertEquals(-500L, entity(coulombUah = -500).toDomain()!!.coulombUah)
    }

    @Test
    fun observationCarriesIntegratedCoulombChargeWhenPresent() {
        val observation = entity(coulombUah = 2_600_000).toDomain()!!.toObservation()
        assertEquals(2_600_000L, observation.coulombUah)
    }

    @Test
    fun observationHasNoCoulombChargeWhenAbsentOrNonPositive() {
        assertNull(entity(coulombUah = null).toDomain()!!.toObservation().coulombUah)
        assertNull(entity(coulombUah = 0).toDomain()!!.toObservation().coulombUah)
        assertNull(entity(coulombUah = -1).toDomain()!!.toObservation().coulombUah)
    }

    @Test
    fun observationCarriesBothCounterAndCoulombWhenBothArePresent() {
        // The estimator decides which column to trust; the mapper must not choose for it
        // by suppressing one just because the other is present.
        val observation = entity(coulombUah = 2_500_000).toDomain()!!.toObservation()
        assertEquals(2_580_000L, observation.counterDeltaUah)
        assertEquals(2_500_000L, observation.coulombUah)
    }

    @Test
    fun sampleMapsToLevelPoint() {
        val point = SampleEntity(
            timestampMs = 4_200,
            levelPct = 41,
            chargeCounterUah = null,
            currentUa = null,
            voltageMv = null,
            tempDeciC = null,
            statusCode = 2,
            pluggedCode = 2,
            screenOn = false,
            sessionId = null,
        ).toLevelPoint()
        assertEquals(4_200, point.timestampMs)
        assertEquals(41, point.levelPct)
    }

    // ---- discharge sessions ------------------------------------------------------------

    private fun session(
        type: SessionType,
        startLevel: Int,
        endLevel: Int,
        startCounter: Long?,
        endCounter: Long?,
        coulomb: Long? = null,
    ) = ChargeSession(
        id = 1,
        type = type,
        startedAtMs = 0,
        endedAtMs = 1_000,
        startLevelPct = startLevel,
        endLevelPct = endLevel,
        startCounterUah = startCounter,
        endCounterUah = endCounter,
        peakTempDeciC = null,
        avgMilliwatts = null,
        screenOnMs = 0,
        coulombUah = coulomb,
    )

    /**
     * A discharge measures the same quantity as a charge: charge leaving the cell across a
     * known change in level says as much about full capacity as charge entering it. Both
     * directions reach the estimator as magnitudes so nothing downstream has to know which
     * way a session ran.
     */
    @Test
    fun aDischargeYieldsThePositiveMagnitudeItMoved() {
        val observation = session(
            type = SessionType.Discharge,
            startLevel = 90,
            endLevel = 30,
            startCounter = 4_500_000,
            endCounter = 1_500_000,
        ).toObservation()

        assertEquals(60, observation.deltaLevelPct)
        assertEquals(3_000_000L, observation.counterDeltaUah)
    }

    @Test
    fun aChargeStillYieldsItsOwnPositiveDelta() {
        val observation = session(
            type = SessionType.Charge,
            startLevel = 30,
            endLevel = 90,
            startCounter = 1_500_000,
            endCounter = 4_500_000,
        ).toObservation()

        assertEquals(60, observation.deltaLevelPct)
        assertEquals(3_000_000L, observation.counterDeltaUah)
    }

    /**
     * The sign guard flips per direction rather than being replaced by an absolute value.
     * A charge whose counter fell is a gauge reset; the equivalent fault on a discharge is
     * a counter that rose -- the phone was charged partway through. Taking a magnitude
     * blindly would have accepted both as measurements.
     */
    @Test
    fun aCounterMovingTheWrongWayIsRejectedForEitherDirection() {
        val chargeThatFell = session(
            type = SessionType.Charge,
            startLevel = 30,
            endLevel = 90,
            startCounter = 4_500_000,
            endCounter = 1_500_000,
        ).toObservation()
        assertNull(chargeThatFell.counterDeltaUah)

        val dischargeThatRose = session(
            type = SessionType.Discharge,
            startLevel = 90,
            endLevel = 30,
            startCounter = 1_500_000,
            endCounter = 4_500_000,
        ).toObservation()
        assertNull(dischargeThatRose.counterDeltaUah)
    }

    /** Integrated current is signed by direction; the magnitude is what gets compared. */
    @Test
    fun aDischargesIntegratedCurrentIsTakenAsAMagnitude() {
        val observation = session(
            type = SessionType.Discharge,
            startLevel = 90,
            endLevel = 30,
            startCounter = null,
            endCounter = null,
            coulomb = -3_000_000,
        ).toObservation()

        assertEquals(3_000_000L, observation.coulombUah)
    }

    @Test
    fun aMissingCounterStaysMissingForEitherDirection() {
        val observation = session(
            type = SessionType.Discharge,
            startLevel = 90,
            endLevel = 30,
            startCounter = null,
            endCounter = 1_500_000,
        ).toObservation()
        assertNull(observation.counterDeltaUah)
    }

    /**
     * The counterpart to the charge rule: a discharge whose integrated current came out
     * *positive* is current having flowed the wrong way for its direction, and is refused
     * exactly as a negative one is on a charge.
     */
    @Test
    fun aDischargeWithPositiveIntegratedCurrentIsRefused() {
        val observation = session(
            type = SessionType.Discharge,
            startLevel = 90,
            endLevel = 30,
            startCounter = null,
            endCounter = null,
            coulomb = 3_000_000,
        ).toObservation()

        assertNull(observation.coulombUah)
    }
}

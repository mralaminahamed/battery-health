package com.mralaminahamed.batteryhealth.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DaoTest {

    private lateinit var db: BatteryDatabase

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun sample(timestampMs: Long, levelPct: Int = 50, sessionId: Long? = null) = SampleEntity(
        timestampMs = timestampMs,
        levelPct = levelPct,
        chargeCounterUah = 2_095_000,
        currentUa = 1_066_000,
        voltageMv = 3955,
        tempDeciC = 371,
        statusCode = 2,
        pluggedCode = 2,
        screenOn = true,
        sessionId = sessionId,
    )

    @Test
    fun samplesRoundTripAndQueryByTimeWindow() = runBlocking {
        db.samples().insert(sample(1_000))
        db.samples().insert(sample(5_000))
        db.samples().insert(sample(9_000))

        val recent = db.samples().samplesSince(4_000)
        assertEquals(2, recent.size)
        assertEquals(5_000, recent.first().timestampMs)
    }

    @Test
    fun latestReturnsTheNewestSample() = runBlocking {
        db.samples().insert(sample(1_000, levelPct = 40))
        db.samples().insert(sample(2_000, levelPct = 41))
        assertEquals(41, db.samples().latest()?.levelPct)
    }

    @Test
    fun latestOnAnEmptyTableIsNull() = runBlocking {
        assertNull(db.samples().latest())
    }

    @Test
    fun deleteOlderThanRemovesOnlyStaleSamples() = runBlocking {
        db.samples().insert(sample(1_000))
        db.samples().insert(sample(8_000))
        val removed = db.samples().deleteOlderThan(5_000)
        assertEquals(1, removed)
        assertEquals(1, db.samples().samplesSince(0).size)
    }

    @Test
    fun openSessionIsTheOneWithoutAnEndTimestamp() = runBlocking {
        val id = db.sessions().insert(
            SessionEntity(
                type = "CHARGE",
                startedAtMs = 1_000,
                endedAtMs = null,
                startLevelPct = 35,
                endLevelPct = null,
                startCounterUah = 1_750_000,
                endCounterUah = null,
                coulombUah = null,
                peakTempDeciC = null,
                avgMilliwatts = null,
                screenOnMs = 0,
            )
        )
        assertEquals(id, db.sessions().openSession()?.id)

        db.sessions().update(
            db.sessions().openSession()!!.copy(endedAtMs = 9_000, endLevelPct = 80)
        )
        assertNull(db.sessions().openSession())
        assertEquals(1, db.sessions().completedSessions(limit = 10).size)
    }

    @Test
    fun estimatesAreReturnedNewestFirst() = runBlocking {
        val sessionId = db.sessions().insert(
            SessionEntity(
                type = "CHARGE",
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                startLevelPct = 20,
                endLevelPct = 80,
                startCounterUah = 1_000_000,
                endCounterUah = 4_000_000,
                coulombUah = null,
                peakTempDeciC = 380,
                avgMilliwatts = 9_000,
                screenOnMs = 0,
            )
        )
        db.estimates().insert(
            CapacityEstimateEntity(
                sessionId = sessionId,
                measuredFullUah = 4_300_000,
                deltaLevelPct = 60,
                method = "COUNTER",
                trustworthy = true,
            )
        )
        val recent = db.estimates().recent(limit = 5)
        assertEquals(1, recent.size)
        assertEquals(4_300_000, recent.first().measuredFullUah)
    }
}

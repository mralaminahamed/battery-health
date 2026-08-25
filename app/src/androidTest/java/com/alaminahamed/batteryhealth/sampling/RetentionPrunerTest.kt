package com.alaminahamed.batteryhealth.sampling

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.local.BatteryDatabase
import com.alaminahamed.batteryhealth.data.local.SampleEntity
import com.alaminahamed.batteryhealth.data.local.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RetentionPrunerTest {

    private lateinit var db: BatteryDatabase
    private val nowMs = 1_000_000_000_000L

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    private fun sample(timestampMs: Long) = SampleEntity(
        timestampMs = timestampMs,
        levelPct = 50,
        chargeCounterUah = null,
        currentUa = null,
        voltageMv = null,
        tempDeciC = null,
        statusCode = 2,
        pluggedCode = 0,
        screenOn = false,
        sessionId = null,
    )

    private fun session(endedAtMs: Long) = SessionEntity(
        type = "CHARGE",
        startedAtMs = endedAtMs - 3_600_000,
        endedAtMs = endedAtMs,
        startLevelPct = 20,
        endLevelPct = 80,
        startCounterUah = 1_000_000,
        endCounterUah = 4_000_000,
        coulombUah = null,
        peakTempDeciC = 380,
        avgMilliwatts = 9_000,
        screenOnMs = 0,
    )

    private fun pruner() = RetentionPruner(db.samples(), db.sessions(), NowMs { nowMs })

    @Test
    fun samplesOlderThanFortyFiveDaysGo() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        db.samples().insert(sample(nowMs - 46 * day))
        db.samples().insert(sample(nowMs - 44 * day))

        val removed = pruner().prune()

        assertEquals(1, removed)
        assertEquals(1, db.samples().samplesSince(0).size)
    }

    @Test
    fun sampleRetentionOutlastsTheLongestChartRange() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        // A sample at the far edge of the 30-day History range must survive pruning.
        db.samples().insert(sample(nowMs - 30 * day))
        pruner().prune()
        assertEquals(1, db.samples().samplesSince(0).size)
    }

    @Test
    fun sessionsOlderThanAYearGo() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        db.sessions().insert(session(nowMs - 366 * day))
        db.sessions().insert(session(nowMs - 364 * day))

        pruner().prune()

        assertEquals(1, db.sessions().completedSessions(limit = 10).size)
    }
}

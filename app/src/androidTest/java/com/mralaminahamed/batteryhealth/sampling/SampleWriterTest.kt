package com.mralaminahamed.batteryhealth.sampling

import android.os.BatteryManager
import android.os.PowerManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.mralaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.mralaminahamed.batteryhealth.data.framework.IntPropertyReader
import com.mralaminahamed.batteryhealth.data.local.BatteryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SampleWriterTest {

    private lateinit var db: BatteryDatabase

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun writesOneSampleFromLiveDeviceState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val capabilities = CapabilityProbe(
            reader = IntPropertyReader { batteryManager.getIntProperty(it) },
        ).probe()

        val writer = SampleWriter(
            broadcasts = BatteryBroadcastSource(context),
            properties = BatteryManagerSource(batteryManager, capabilities),
            sampleDao = db.samples(),
            powerManager = context.getSystemService(PowerManager::class.java),
            nowMs = NowMs { 1_700_000_000_000L },
        )

        val id = writer.writeOne()

        assertNotNull(id)
        val stored = db.samples().latest()!!
        assertEquals(1_700_000_000_000L, stored.timestampMs)
        assertTrue(stored.levelPct in 0..100)
    }
}

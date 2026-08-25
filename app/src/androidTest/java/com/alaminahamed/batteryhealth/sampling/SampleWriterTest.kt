package com.alaminahamed.batteryhealth.sampling

import android.os.BatteryManager
import android.os.PowerManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.alaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.alaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.alaminahamed.batteryhealth.data.framework.IntPropertyReader
import com.alaminahamed.batteryhealth.data.local.BatteryDatabase
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SettingsStore reaches the app's real DataStore file (a fixed-name Context delegate with
 * no injectable alternative), so this suite clears it before and after to stay hermetic --
 * BatteryManagerSource now reads currentScale from it to decide the scale currentUa() uses.
 */
class SampleWriterTest {

    private lateinit var db: BatteryDatabase
    private lateinit var settings: SettingsStore

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        settings = SettingsStore(context)
        runBlocking { settings.clearForTesting() }
    }

    @After
    fun close() {
        runBlocking { settings.clearForTesting() }
        db.close()
    }

    @Test
    fun writesOneSampleFromLiveDeviceState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val capabilities = CapabilityProbe(
            reader = IntPropertyReader { batteryManager.getIntProperty(it) },
        ).probe()

        val writer = SampleWriter(
            broadcasts = BatteryBroadcastSource(context),
            properties = BatteryManagerSource(batteryManager, capabilities, settings),
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

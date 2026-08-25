package com.alaminahamed.batteryhealth.data.framework

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryBroadcastSourceTest {

    @Test
    fun emitsStickyStateImmediately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broadcast = withTimeout(5_000) { BatteryBroadcastSource(context).broadcasts().first() }

        assertNotNull("sticky broadcast must carry a level", broadcast.levelPct)
        assertTrue(broadcast.levelPct!! in 0..100)
        assertTrue(broadcast.present)
        assertNotNull(broadcast.technology)
    }

    /** The unconflated path must still behave like a normal Flow for a single read. */
    @Test
    fun rawBroadcastsAlsoEmitsStickyStateImmediately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val broadcast = withTimeout(5_000) { BatteryBroadcastSource(context).rawBroadcasts().first() }

        assertNotNull("sticky broadcast must carry a level", broadcast.levelPct)
        assertTrue(broadcast.levelPct!! in 0..100)
    }
}

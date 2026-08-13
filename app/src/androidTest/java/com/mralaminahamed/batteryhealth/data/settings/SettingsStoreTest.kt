package com.mralaminahamed.batteryhealth.data.settings

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.mralaminahamed.batteryhealth.sampling.SamplingScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * These tests write the app's real settings file, because `preferencesDataStore` is a
 * Context delegate with a fixed name and there is no injectable alternative here. That
 * makes hermeticity the test's own responsibility: without the clear-before and
 * clear-after below, running this suite would leave the opt-in recorder flag switched on
 * — turning on the very thing the opt-in default exists to prevent. `clearForTesting()`
 * also cancels the charge recorder's WorkManager job for the same reason.
 */
class SettingsStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore(context, SamplingScheduler(WorkManager.getInstance(context)))

    @Before
    fun clearBefore() = runBlocking { store.clearForTesting() }

    @After
    fun clearAfter() = runBlocking { store.clearForTesting() }

    @Test
    fun recorderIsDisabledUntilExplicitlyEnabled() = runBlocking {
        // Asserts the real default, with nothing written first. This is the test that pins
        // the opt-in requirement; flipping the default must break it.
        assertFalse(store.recorderEnabled.first())
    }

    @Test
    fun noDesignCapacityOverrideByDefault() = runBlocking {
        assertNull(store.designCapacityOverrideMah.first())
    }

    @Test
    fun overrideRoundTripsAndClears() = runBlocking {
        store.setDesignCapacityOverride(4820)
        assertEquals(4820, store.designCapacityOverrideMah.first())

        store.setDesignCapacityOverride(null)
        assertNull(store.designCapacityOverrideMah.first())
    }

    @Test
    fun recorderFlagRoundTrips() = runBlocking {
        store.setRecorderEnabled(true)
        assertTrue(store.recorderEnabled.first())

        store.setRecorderEnabled(false)
        assertFalse(store.recorderEnabled.first())
    }
}

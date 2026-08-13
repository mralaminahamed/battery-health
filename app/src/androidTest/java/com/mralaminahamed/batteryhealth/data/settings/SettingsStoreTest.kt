package com.mralaminahamed.batteryhealth.data.settings

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun overrideRoundTripsAndClears() = runBlocking {
        store.setDesignCapacityOverride(4820)
        assertEquals(4820, store.designCapacityOverrideMah.first())

        store.setDesignCapacityOverride(null)
        assertNull(store.designCapacityOverrideMah.first())
    }

    @Test
    fun recorderIsOptOutByDefault() = runBlocking {
        store.setRecorderEnabled(false)
        assertFalse(store.recorderEnabled.first())

        store.setRecorderEnabled(true)
        assertTrue(store.recorderEnabled.first())
    }
}

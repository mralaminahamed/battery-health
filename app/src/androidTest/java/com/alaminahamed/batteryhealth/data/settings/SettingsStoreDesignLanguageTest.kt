package com.alaminahamed.batteryhealth.data.settings

import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreDesignLanguageTest {

    private val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun tearDown() = runTest { store.clearForTesting() }

    @Test
    fun defaultsToAuto() = runTest {
        store.clearForTesting()
        assertEquals(DesignLanguageChoice.Auto, store.designLanguageChoice.first())
    }

    @Test
    fun roundTripsEveryChoice() = runTest {
        for (choice in DesignLanguageChoice.entries) {
            store.setDesignLanguageChoice(choice)
            assertEquals(choice, store.designLanguageChoice.first())
        }
    }

    @Test
    fun anUnrecognisedStoredValueFallsBackToAutoRatherThanCrashing() = runTest {
        // A value written by a future version, or a corrupted preference, must not throw
        // out of a Flow the whole UI collects. Same defensive shape as
        // SettingsStore.currentScale's runCatching.
        store.clearForTesting()
        store.writeRawDesignLanguageForTesting("NotALanguage")
        assertEquals(DesignLanguageChoice.Auto, store.designLanguageChoice.first())
    }
}

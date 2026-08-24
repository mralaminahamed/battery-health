package com.mralaminahamed.batteryhealth.data.settings

import androidx.test.platform.app.InstrumentationRegistry
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
 * — turning on the very thing the opt-in default exists to prevent.
 *
 * `clearForTesting()` does *not* stop the charge recorder service directly -- there is
 * no such call left anywhere (see `ChargeRecorderService`'s class doc). It resets the
 * flag to its false default, which a running service's own internal watcher reacts to
 * the same way an ordinary `setRecorderEnabled(false)` does, asynchronously and on its
 * own schedule. `recorderFlagRoundTrips` below is, incidentally, the exact test that
 * first surfaced the external-`stopService()` race this design avoids: enabling
 * immediately followed by disabling, with no delay, used to crash the app with
 * `ForegroundServiceDidNotStartInTimeException` before the self-stop fix.
 */
class SettingsStoreTest {

    private val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

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

    @Test
    fun adbPortDefaultsTo5555() = runBlocking {
        assertEquals(5555, store.adbPort.first())
    }

    @Test
    fun adbPortRoundTrips() = runBlocking {
        store.setAdbPort(5037)
        assertEquals(5037, store.adbPort.first())
    }

    @Test
    fun rootPreviouslyGrantedDefaultsToFalse() = runBlocking {
        // Load-bearing default: true here would make the app probe su on first launch and
        // raise Magisk's dialog before the user has asked for anything.
        assertEquals(false, store.rootPreviouslyGranted.first())
    }

    @Test
    fun rootPreviouslyGrantedRoundTrips() = runBlocking {
        store.setRootPreviouslyGranted(true)
        assertEquals(true, store.rootPreviouslyGranted.first())
    }
}

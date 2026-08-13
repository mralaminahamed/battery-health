package com.mralaminahamed.batteryhealth.sampling

import android.app.ActivityManager
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Correctness tests for the service lifecycle, as opposed to
 * `ChargeRecorderExerciseSupport`, which is deliberately not a correctness test.
 *
 * The specific race `watchRecorderEnabled()`'s loop exists to survive -- a disable's
 * `stopSelfResult` call landing just after AMS has already bumped its start-id counter
 * for a concurrent re-enable, but before `lastStartId` catches up -- depends on a
 * coroutine on `Dispatchers.Default` being descheduled for tens of milliseconds at
 * exactly the wrong instant. That is not reliably forceable from an instrumented test:
 * calling `setRecorderEnabled(false)` immediately followed by `setRecorderEnabled(true)`
 * from the same calling coroutine, with no delay between them, *can* land in the
 * failure window, but whether it does on any given run depends on scheduler timing this
 * test does not control. So rather than trying to prove the exact interleaving fired
 * (and risk presenting an ambiguous end state as proof, which a rapid toggle alone
 * cannot distinguish -- "the disable's stop succeeded, then the enable started a fresh
 * instance" ends up looking identical to "the stop was refused, and the pre-existing
 * instance kept running" from the outside), these tests assert the property that
 * actually differs between the old one-shot watcher and the fixed loop: whether the
 * service can still be stopped by an unambiguous, later disable. Under the one-shot
 * bug, a refusal during the rapid toggle retires the only observer the service has, and
 * a later disable is never noticed. Under the loop fix, the watcher re-arms after a
 * refusal and the later disable is caught regardless.
 */
class ChargeRecorderServiceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore(context)

    @Before
    fun disableAndAwaitBefore() = runBlocking {
        store.setRecorderEnabled(false)
        awaitServiceState(running = false)
    }

    @After
    fun disableAndAwaitAfter() = runBlocking {
        store.setRecorderEnabled(false)
        awaitServiceState(running = false)
    }

    @Test
    fun rapidDisableThenEnableEndsRunningAndStaysStoppableAfterwards() = runBlocking {
        store.setRecorderEnabled(true)
        awaitServiceState(running = true)

        // The rapid toggle: whether or not it lands in the exact refusal window is not
        // controlled here (see class doc). Either way, ending on enable must leave the
        // service running.
        store.setRecorderEnabled(false)
        store.setRecorderEnabled(true)
        awaitServiceState(running = true)
        assertTrue("service should be running after ending on enable", isServiceRunning())

        // The assertion that actually distinguishes the fix from the bug it replaced:
        // a subsequent, unambiguous disable must still be able to stop the service.
        // Under the one-shot-watcher bug, a refusal during the rapid toggle above would
        // have retired the only observer the service had, and this disable would never
        // be noticed -- the service would keep running (and sampling, and showing its
        // notification) indefinitely, regardless of what the flag says.
        store.setRecorderEnabled(false)
        awaitServiceState(running = false)
        assertFalse("a later disable must still be able to stop the service", isServiceRunning())
    }

    @Test
    fun endingOnDisableLeavesNoServiceRunning() = runBlocking {
        store.setRecorderEnabled(true)
        awaitServiceState(running = true)

        store.setRecorderEnabled(false)
        awaitServiceState(running = false)
        assertFalse(isServiceRunning())
    }

    private suspend fun awaitServiceState(running: Boolean, timeoutMs: Long = 5_000) {
        withTimeout(timeoutMs) {
            while (isServiceRunning() != running) {
                delay(50)
            }
        }
    }

    @Suppress("DEPRECATION") // getRunningServices() is still valid for the caller's own package.
    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val target = ComponentName(context, ChargeRecorderService::class.java)
        return activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == target }
    }
}

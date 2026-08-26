package com.alaminahamed.batteryhealth.sampling

import android.app.ActivityManager
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        awaitServiceState(running = false, step = "@Before cleanup")
    }

    @After
    fun disableAndAwaitAfter() = runBlocking {
        store.setRecorderEnabled(false)
        awaitServiceState(running = false, step = "@After cleanup")
    }

    @Test
    fun rapidDisableThenEnableEndsRunningAndStaysStoppableAfterwards() = runBlocking {
        store.setRecorderEnabled(true)
        awaitServiceState(running = true, step = "initial enable")

        // The rapid toggle: whether or not it lands in the exact refusal window is not
        // controlled here (see class doc). Either way, ending on enable must leave the
        // service running.
        store.setRecorderEnabled(false)
        store.setRecorderEnabled(true)
        awaitServiceState(running = true, step = "rapid disable-then-enable")
        assertTrue("service should be running after ending on enable", isServiceRunning())

        // The assertion that actually distinguishes the fix from the bug it replaced:
        // a subsequent, unambiguous disable must still be able to stop the service.
        // Under the one-shot-watcher bug, a refusal during the rapid toggle above would
        // have retired the only observer the service had, and this disable would never
        // be noticed -- the service would keep running (and sampling, and showing its
        // notification) indefinitely, regardless of what the flag says.
        store.setRecorderEnabled(false)
        awaitServiceState(running = false, step = "later unambiguous disable")
        assertFalse("a later disable must still be able to stop the service", isServiceRunning())
    }

    @Test
    fun endingOnDisableLeavesNoServiceRunning() = runBlocking {
        store.setRecorderEnabled(true)
        awaitServiceState(running = true, step = "enable")

        store.setRecorderEnabled(false)
        awaitServiceState(running = false, step = "disable")
        assertFalse(isServiceRunning())
    }

    /**
     * [timeoutMs] is a generous upper bound, not a performance assertion.
     *
     * Each transition here is a DataStore write, then a collector waking, then Android
     * actually starting or stopping a foreground service and posting its notification.
     * None of that is under this test's control and all of it competes with whatever else
     * the suite is doing. At the original 5s this test failed intermittently on a real
     * SM-S948B -- twice in five consecutive full-suite runs, while passing every time the
     * class ran alone -- which is the signature of a bound set to the observed time rather
     * than to the worst plausible one.
     *
     * Raising it does not weaken what the test proves. The defect it guards against is a
     * service that never stops at all, and that still fails here, just later. A test that
     * cries wolf under load is worse than one that takes longer to do so, because the
     * response to a flaky failure is to stop reading failures.
     *
     * Do not lower this back to the time a passing run happened to take.
     */
    private suspend fun awaitServiceState(
        running: Boolean,
        step: String,
        timeoutMs: Long = 20_000,
    ) {
        try {
            withTimeout(timeoutMs) {
                while (isServiceRunning() != running) {
                    delay(50)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Rethrown as a named failure rather than propagated.
            //
            // A bare TimeoutCancellationException names neither the step nor the
            // expectation, and its stack is the coroutine machinery's, not this file's
            // -- so a full-suite failure reported only "Timed out waiting for 20000 ms"
            // with no line number, and there are five distinct waits in this class. Two
            // separate rounds of diagnosis started by having to guess which one it was.
            throw AssertionError(
                "Timed out after ${timeoutMs}ms waiting for the service to be " +
                    "${if (running) "running" else "stopped"} at step \"$step\"; " +
                    "recorderEnabled=${store.recorderEnabled.first()}, " +
                    "isServiceRunning=${isServiceRunning()}",
                e,
            )
        }
    }

    @Suppress("DEPRECATION") // getRunningServices() is still valid for the caller's own package.
    private fun isServiceRunning(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val target = ComponentName(context, ChargeRecorderService::class.java)
        return activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == target }
    }
}

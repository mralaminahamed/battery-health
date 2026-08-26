package com.alaminahamed.batteryhealth

import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner

/**
 * Holds the display on for the whole instrumentation run.
 *
 * Compose's test framework can only find a hierarchy while there is a display to compose
 * onto. With the screen off, `onNodeWithText(...).assertIsDisplayed()` fails with "No
 * compose hierarchies found in the app" -- a message that names the Activity and says
 * nothing about the display, which is why this took three separate rounds to pin down.
 *
 * The symptom it produces is worth spelling out, because it does not look like an
 * environmental problem. The suite runs in one process in a hash-derived order, so a
 * display that switches off partway through fails whichever Compose class happens to be
 * running at that moment and leaves the classes before it green. Run that class by
 * itself and it passes, every time, because a single class finishes well inside the
 * screen timeout. "Passes alone, fails in the suite" is the exact signature of a test
 * isolation problem -- shared static state, a leaked singleton, an unclosed database --
 * and this project spent two rounds looking for one on that basis. There was none. The
 * suite takes about two minutes and the display timeout was shorter than that.
 *
 * Two mechanisms, because they cover different starting states:
 *
 * - The wake lock keeps the display on once it is on. `ACQUIRE_CAUSES_WAKEUP` also turns
 *   it on if it is already off when the run begins.
 * - `ON_AFTER_RELEASE` leaves the ordinary timeout running from the end of the suite
 *   rather than from whenever the display last happened to be touched, so the phone does
 *   not black out the instant the last test finishes.
 *
 * A wake lock rather than writing `screen_off_timeout`, which was the first approach and
 * is the one this file deliberately does not take: that setting is the user's, this runs
 * against real hardware someone owns, and a crashed or killed run would leave their phone
 * set to never sleep with nothing to notice it. A wake lock cannot outlive its process --
 * the kernel drops it when the process dies -- so the worst case here is that it stops
 * working, not that it silently changes a phone's behaviour afterwards.
 *
 * `WAKE_LOCK` is declared in `src/androidTest/AndroidManifest.xml`, which merges into the
 * test APK alone. The shipped app does not request it and this class is not in it.
 */
class ScreenAwakeRunner : AndroidJUnitRunner() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStart() {
        keepTheDisplayOn()
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        releaseQuietly()
        super.finish(resultCode, results)
    }

    private fun keepTheDisplayOn() {
        // Deliberately non-fatal. This is scaffolding for the run, not a thing under
        // test: a device or an OEM build that refuses the lock should produce whatever
        // failures it produces, with a line in the log saying the display was not held,
        // rather than failing every test in the suite for a reason unrelated to any of
        // them.
        try {
            val power = targetContext.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION") // No replacement reaches the display from outside an Activity.
            val lock = power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                WAKE_LOCK_TAG,
            )
            lock.setReferenceCounted(false)
            lock.acquire(MAX_RUN_MS)
            wakeLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "Could not hold the display on; Compose tests may fail if it sleeps", t)
        }

        // The lock keeps the display awake but does not get past a lock screen, and a
        // locked device shows the keyguard over the test Activity. Best-effort for the
        // same reason as above.
        try {
            uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not dismiss the keyguard", t)
        }
    }

    private fun releaseQuietly() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release the display wake lock", t)
        }
        wakeLock = null
    }

    private companion object {
        const val TAG = "ScreenAwakeRunner"
        const val WAKE_LOCK_TAG = "batteryhealth:instrumentation"

        /**
         * A timeout on the lock itself, so a run that is killed without `finish()` ever
         * being called still cannot hold the display on indefinitely. Comfortably longer
         * than a full suite (about two minutes at the time of writing) and far shorter
         * than "until someone notices".
         */
        const val MAX_RUN_MS = 30 * 60 * 1000L
    }
}

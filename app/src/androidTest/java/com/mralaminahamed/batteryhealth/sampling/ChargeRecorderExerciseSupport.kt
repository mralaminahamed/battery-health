package com.mralaminahamed.batteryhealth.sampling

import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Not a correctness test — there is no assertion here. The recorder is opt-in by design
 * (Task 6), and `SettingsStoreTest.recorderIsDisabledUntilExplicitlyEnabled` pins the
 * default to false specifically so that flipping it in source breaks a test. Exercising
 * `ChargeRecorderService` on the emulator without touching that default still needs the
 * flag flipped on somehow, so `setRecorderEnabled` flips it the honest way, through the
 * real `SettingsStore` API -- which also starts the real foreground service when
 * enabling. Disabling does not stop the service directly (there is no such call
 * anymore); it relies on the running service's own watcher, the same way production
 * code does.
 *
 * `@After` restores the flag to false (which the service reacts to by stopping itself)
 * on every run *unless* the
 * instrumentation run explicitly opts out with `-e keepRecorderEnabled true`, which only
 * a deliberate manual invocation ever passes. This is what actually closes the hazard a
 * hash-ordered JUnit4 suite run could otherwise create (`SettingsStoreTest` guards the
 * same way with `@Before`/`@After`): a full-suite run (`connectedPlayDebugAndroidTest`,
 * no filter) or a full-class run never passes that argument, so it always ends with the
 * flag off and the service stopped, regardless of which of these methods happened to run
 * last.
 *
 * `@Ignore` was tried first, in an earlier round of this file, and rejected: verified
 * empirically that `adb shell am instrument -e class <fqcn>#<method>` still skips an
 * `@Ignore`d method ("OK (0 tests)") -- it does not "run when named" on this test
 * runner, so it would have made these methods uninvokable rather than merely excluded
 * from full-suite discovery.
 *
 * `enableRecorderForManualExerciseAndHoldTheProcessAlive` does the enabling *and* the
 * holding in one method, deliberately not two chained via a comma-separated `-e class`
 * list: tried that first, and `am instrument` does not run comma-separated methods in
 * the order listed -- confirmed directly on-device, where `holdProcessAliveForManualExercise`
 * started before `enableRecorderForManualExercise` ("run started: 2 tests" followed
 * immediately by `started: holdProcessAliveForManualExercise`), the same hash-derived
 * ordering hazard Finding 5 already dealt with, just surfacing in a new place. One
 * method removes the ordering question entirely.
 *
 * The hold itself exists because `am instrument` tears down the target app's process the
 * instant the requested test(s) finish -- confirmed directly on-device: enabling alone
 * started `ChargeRecorderService` successfully (`ActivityManager` logged
 * `Background started FGS: Allowed ... BFGS denied: false`), but the very next log line
 * was `Bringing down service while still waiting for start foreground`, because the
 * whole process -- service included -- was killed the moment the test method returned.
 * That is an artifact of the test harness, not the app.
 *
 * To drive the manual plug/unplug exercise:
 * `adb shell am instrument -w \
 *   -e class <fqcn>#enableRecorderForManualExerciseAndHoldTheProcessAlive \
 *   -e keepRecorderEnabled true <testPackage>/androidx.test.runner.AndroidJUnitRunner`
 * then, once done, run `disableRecorderAfterManualExercise` (the flag argument is
 * unnecessary there, but harmless).
 */
class ChargeRecorderExerciseSupport {

    private val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun restoreDefaultUnlessKeepingItEnabledForManualExercise() = runBlocking {
        val keepEnabled = InstrumentationRegistry.getArguments()?.getString("keepRecorderEnabled") == "true"
        if (!keepEnabled) store.setRecorderEnabled(false)
    }

    @Test
    fun disableRecorderAfterManualExercise() = runBlocking {
        store.setRecorderEnabled(false)
    }

    /**
     * See the class doc for why enabling and holding are one method. Without
     * `-e keepRecorderEnabled true` this still enables (and immediately gets torn down
     * by `@After` plus the instrumentation teardown, same as before), but does not
     * block -- so a full-suite run is never held up by this method.
     */
    @Test
    fun enableRecorderForManualExerciseAndHoldTheProcessAlive() = runBlocking {
        store.setRecorderEnabled(true)
        val keepEnabled = InstrumentationRegistry.getArguments()?.getString("keepRecorderEnabled") == "true"
        if (keepEnabled) delay(300_000L)
    }
}

package com.mralaminahamed.batteryhealth.sampling

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * Not a correctness test — there is no assertion here. The recorder is opt-in by design
 * (Task 6), and `SettingsStoreTest.recorderIsDisabledUntilExplicitlyEnabled` pins the
 * default to false specifically so that flipping it in source breaks a test. Exercising
 * `ChargeRecorderWorker` on the emulator without touching that default still needs the
 * flag flipped on somehow, so `enableRecorderForManualExercise` flips it the honest way,
 * through the real `SettingsStore` API.
 *
 * `@After` restores the flag to false on every run *unless* the instrumentation run
 * explicitly opts out with `-e keepRecorderEnabled true`, which only a deliberate manual
 * invocation ever passes. This is what actually closes the hazard a hash-ordered JUnit4
 * suite run could otherwise create (`SettingsStoreTest` guards the same way with
 * `@Before`/`@After`): a full-suite run (`connectedPlayDebugAndroidTest`, no filter) or a
 * full-class run never passes that argument, so it always ends with the flag off,
 * regardless of which of these two methods happened to run last.
 *
 * `@Ignore` was tried first and rejected: verified empirically that
 * `adb shell am instrument -e class <fqcn>#<method>` still skips an `@Ignore`d method
 * ("OK (0 tests)") -- it does not "run when named" on this test runner, so it would have
 * made these methods uninvokable rather than merely excluded from full-suite discovery.
 *
 * To drive the manual plug/unplug exercise:
 * `adb shell am instrument -w -e class <fqcn>#enableRecorderForManualExercise \
 *   -e keepRecorderEnabled true <testPackage>/androidx.test.runner.AndroidJUnitRunner`
 * then, once done, run `disableRecorderAfterManualExercise` (the flag argument is
 * unnecessary there, but harmless).
 */
class ChargeRecorderExerciseSupport {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SettingsStore(context, SamplingScheduler(WorkManager.getInstance(context)))

    @After
    fun restoreDefaultUnlessKeepingItEnabledForManualExercise() = runBlocking {
        val keepEnabled = InstrumentationRegistry.getArguments()?.getString("keepRecorderEnabled") == "true"
        if (!keepEnabled) store.setRecorderEnabled(false)
    }

    @Test
    fun enableRecorderForManualExercise() = runBlocking {
        store.setRecorderEnabled(true)
    }

    @Test
    fun disableRecorderAfterManualExercise() = runBlocking {
        store.setRecorderEnabled(false)
    }
}

package com.mralaminahamed.batteryhealth.sampling

import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Not a correctness test — there is no assertion here. The recorder is opt-in by design
 * (Task 6), and `SettingsStoreTest.recorderIsDisabledUntilExplicitlyEnabled` pins the
 * default to false specifically so that flipping it in source breaks a test. Exercising
 * `PowerReceiver`/`ChargeRecorderService` on the emulator without touching that default
 * still needs the flag flipped on somehow, so these two methods flip it the honest way,
 * through the real `SettingsStore` API, run individually via
 * `adb shell am instrument -w -e class <fqcn>#<method> ...` (which, unlike Gradle's
 * `connectedAndroidTest` task, does not uninstall the app afterwards) around a manual
 * plug/unplug exercise driven by `adb shell dumpsys battery`.
 */
class ChargeRecorderExerciseSupport {

    private val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun enableRecorderForManualExercise() = runBlocking {
        store.setRecorderEnabled(true)
    }

    @Test
    fun disableRecorderAfterManualExercise() = runBlocking {
        store.setRecorderEnabled(false)
    }
}

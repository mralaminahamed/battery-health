package com.alaminahamed.batteryhealth.ui.settings

import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageChoice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `SettingsUiState`'s cold-start defaults.
 *
 * `SettingsViewModel.setDesignLanguage` writes through `SettingsStore`, which is constructed
 * from a real `Context` -- not available to a plain JVM test in this project. There is no
 * existing JVM test for any `ViewModel` in this codebase to follow a pattern from (checked:
 * no `*ViewModelTest.kt` exists in `app/src/test`), and this project's other tests facing the
 * same wall (`DesignCapacityProviderTest`'s own doc) responded by extracting pure logic
 * rather than by adding a mocking framework or Robolectric -- `testImplementation` in
 * `app/build.gradle.kts` carries only JUnit and `kotlinx-coroutines-test`. There is no pure
 * logic to extract from `setDesignLanguage`, though: it is one line,
 * `settings.setDesignLanguageChoice(choice)`, a direct delegation with no branching or
 * transformation to isolate. Introducing a fake/mock `SettingsStore` here to reach it would be
 * inventing a test framework this project has deliberately not adopted, for a single
 * delegating call -- so per the brief, this covers the pure state instead.
 */
class SettingsUiStateTest {

    @Test
    fun defaultsAreTheColdStartPlaceholders() {
        val state = SettingsUiState()
        assertEquals(DesignLanguageChoice.Auto, state.designLanguage)
        assertEquals(true, state.notificationsGranted)
        assertEquals(emptyList<PermissionRow>(), state.permissions)
    }
}

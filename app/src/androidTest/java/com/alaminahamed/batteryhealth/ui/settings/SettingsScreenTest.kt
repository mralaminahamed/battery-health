package com.alaminahamed.batteryhealth.ui.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `SettingsContent` exercised directly, the same way `HealthContent` is in
 * `DesignCapacityDialogTest` -- no Hilt test harness needed. The design-capacity round
 * trip and rejection tests still touch the app's real DataStore through `SettingsStore`
 * directly, so this suite clears it before and after to stay hermetic, per that same
 * test's established pattern.
 *
 * NEVER RUN: this module's androidTest suite could not be executed in the environment
 * this was written in (no attached device/emulator) -- only compiled, via
 * `:app:assemblePlayDebugAndroidTest`.
 */
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun clearBefore() = runBlocking { settings.clearForTesting() }

    @After
    fun clearAfter() = runBlocking { settings.clearForTesting() }

    private fun state(
        designCapacity: EffectiveDesignCapacity = EffectiveDesignCapacity.None,
        adbPort: Int = 5555,
    ) = SettingsUiState(designCapacity = designCapacity, adbPort = adbPort)

    @Test
    fun tappingTheDesignCapacityRowOpensItsDialog() {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(state(EffectiveDesignCapacity(5000, DesignCapacitySource.Table)), Modifier)
            }
        }

        compose.onNodeWithTag(SettingsDesignCapacityTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(SettingsDesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(SettingsDesignCapacityTags.DIALOG).assertIsDisplayed()
    }

    @Test
    fun theRowExplainsATableSourcedValue() {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(state(EffectiveDesignCapacity(5000, DesignCapacitySource.Table)), Modifier)
            }
        }

        compose.onNodeWithText("5000 mAh, model table").assertIsDisplayed()
    }

    @Test
    fun theRowExplainsAnOverrideSourcedValue() {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(state(EffectiveDesignCapacity(4820, DesignCapacitySource.Override)), Modifier)
            }
        }

        compose.onNodeWithText("4820 mAh, your override").assertIsDisplayed()
    }

    @Test
    fun anUnknownDesignCapacityInvitesTheUserToAddOne() {
        compose.setContent {
            BatteryHealthTheme { SettingsContent(state(EffectiveDesignCapacity.None), Modifier) }
        }

        compose.onNodeWithText("Not set — tap to add").assertIsDisplayed()
    }

    @Test
    fun outOfRangeDesignCapacityIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(SettingsDesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(SettingsDesignCapacityTags.INPUT).performTextInput("99999")
        compose.onNodeWithTag(SettingsDesignCapacityTags.SAVE).performClick()

        compose.onNodeWithText("Enter a value between 1000 and 10000 mAh").assertIsDisplayed()
        compose.onNodeWithTag(SettingsDesignCapacityTags.DIALOG).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(settings.designCapacityOverrideMah.first())
    }

    @Test
    fun nonNumericDesignCapacityIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(SettingsDesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(SettingsDesignCapacityTags.INPUT).performTextInput("banana")
        compose.onNodeWithTag(SettingsDesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(SettingsDesignCapacityTags.ERROR).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(settings.designCapacityOverrideMah.first())
    }

    @Test
    fun validDesignCapacityRoundTripsThroughRealSettings() = runBlocking {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { mah -> runBlocking { settings.setDesignCapacityOverride(mah) } },
                )
            }
        }

        compose.onNodeWithTag(SettingsDesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(SettingsDesignCapacityTags.INPUT).performTextInput("4200")
        compose.onNodeWithTag(SettingsDesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(SettingsDesignCapacityTags.DIALOG).assertDoesNotExist()
        assertEquals(4200, settings.designCapacityOverrideMah.first())
    }

    @Test
    fun clearingADesignCapacityOverrideFallsBackToTheTable() = runBlocking {
        settings.setDesignCapacityOverride(4820)
        var cleared = false
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(EffectiveDesignCapacity(4820, DesignCapacitySource.Override)),
                    modifier = Modifier,
                    onClearDesignCapacity = {
                        cleared = true
                        runBlocking { settings.setDesignCapacityOverride(null) }
                    },
                )
            }
        }

        compose.onNodeWithTag(SettingsDesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(SettingsDesignCapacityTags.CLEAR).performClick()

        assertEquals(true, cleared)
        assertNull(settings.designCapacityOverrideMah.first())
    }

    @Test
    fun tappingTheAdbPortRowOpensItsDialog() {
        compose.setContent { BatteryHealthTheme { SettingsContent(state(adbPort = 5555), Modifier) } }

        compose.onNodeWithTag(SettingsAdbPortTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(SettingsAdbPortTags.ROW).performClick()
        compose.onNodeWithTag(SettingsAdbPortTags.DIALOG).assertIsDisplayed()
    }

    @Test
    fun outOfRangeAdbPortIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(adbPort = 5555),
                    modifier = Modifier,
                    onSaveAdbPort = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(SettingsAdbPortTags.ROW).performClick()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextClearance()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextInput("70000")
        compose.onNodeWithTag(SettingsAdbPortTags.SAVE).performClick()

        compose.onNodeWithText("Enter a port between 1 and 65535").assertIsDisplayed()
        compose.onNodeWithTag(SettingsAdbPortTags.DIALOG).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertEquals(5555, settings.adbPort.first())
    }

    @Test
    fun nonNumericAdbPortIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(adbPort = 5555),
                    modifier = Modifier,
                    onSaveAdbPort = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(SettingsAdbPortTags.ROW).performClick()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextClearance()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextInput("banana")
        compose.onNodeWithTag(SettingsAdbPortTags.SAVE).performClick()

        compose.onNodeWithTag(SettingsAdbPortTags.ERROR).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertEquals(5555, settings.adbPort.first())
    }

    @Test
    fun validAdbPortRoundTripsThroughRealSettings() = runBlocking {
        compose.setContent {
            BatteryHealthTheme {
                SettingsContent(
                    state = state(adbPort = 5555),
                    modifier = Modifier,
                    onSaveAdbPort = { port -> runBlocking { settings.setAdbPort(port) } },
                )
            }
        }

        compose.onNodeWithTag(SettingsAdbPortTags.ROW).performClick()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextClearance()
        compose.onNodeWithTag(SettingsAdbPortTags.INPUT).performTextInput("5037")
        compose.onNodeWithTag(SettingsAdbPortTags.SAVE).performClick()

        compose.onNodeWithTag(SettingsAdbPortTags.DIALOG).assertDoesNotExist()
        assertEquals(5037, settings.adbPort.first())
    }
}

package com.mralaminahamed.batteryhealth.ui.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.mralaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * `HealthContent` is exercised directly (as `HealthScreenTest` already does) rather than
 * through `HealthScreen`/`HealthViewModel`, so these tests don't need a Hilt test harness.
 * The "round trips" and "rejected without writing" tests still touch the app's real
 * DataStore through `SettingsStore` directly -- via the save callback, exactly the way
 * `HealthViewModel.setDesignCapacityOverride` does in production -- so, per
 * `SettingsStoreTest`'s established pattern, this suite clears it before and after to
 * stay hermetic.
 */
class DesignCapacityDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val settings = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun clearBefore() = runBlocking { settings.clearForTesting() }

    @After
    fun clearAfter() = runBlocking { settings.clearForTesting() }

    private fun state(designCapacity: EffectiveDesignCapacity) =
        HealthUiState(snapshot = null, measured = Reading.NotYetMeasured, designCapacity = designCapacity)

    @Test
    fun tappingTheRowOpensTheDialog() {
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(state(EffectiveDesignCapacity(5000, DesignCapacitySource.Table)), Modifier)
            }
        }

        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(DesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertIsDisplayed()
    }

    @Test
    fun outOfRangeValueIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(DesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("99999")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        // Rejected in the dialog: the error is shown, save was never called, and the
        // dialog is still open rather than having closed on a value it refused.
        compose.onNodeWithText("Enter a value between 1000 and 10000 mAh").assertIsDisplayed()
        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(settings.designCapacityOverrideMah.first())
    }

    @Test
    fun nonNumericValueIsRejectedWithoutWriting() = runBlocking {
        var saveCalls = 0
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { saveCalls++ },
                )
            }
        }

        compose.onNodeWithTag(DesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("banana")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(DesignCapacityTags.ERROR).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(settings.designCapacityOverrideMah.first())
    }

    @Test
    fun validValueRoundTripsThroughRealSettings() = runBlocking {
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(
                    state = state(EffectiveDesignCapacity.None),
                    modifier = Modifier,
                    onSaveDesignCapacity = { mah ->
                        runBlocking { settings.setDesignCapacityOverride(mah) }
                    },
                )
            }
        }

        compose.onNodeWithTag(DesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("4200")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertDoesNotExist()
        assertEquals(4200, settings.designCapacityOverrideMah.first())
    }

    @Test
    fun clearRemovesAnExistingOverride() = runBlocking {
        settings.setDesignCapacityOverride(4820)
        var cleared = false
        compose.setContent {
            BatteryHealthTheme {
                HealthContent(
                    state = state(EffectiveDesignCapacity(4820, DesignCapacitySource.Override)),
                    modifier = Modifier,
                    onClearDesignCapacity = {
                        cleared = true
                        runBlocking { settings.setDesignCapacityOverride(null) }
                    },
                )
            }
        }

        compose.onNodeWithTag(DesignCapacityTags.ROW).performClick()
        compose.onNodeWithTag(DesignCapacityTags.CLEAR).performClick()

        assertEquals(true, cleared)
        assertNull(settings.designCapacityOverrideMah.first())
    }
}

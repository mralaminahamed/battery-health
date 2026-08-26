package com.alaminahamed.batteryhealth.ui.health

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.settings.DesignCapacitySource
import com.alaminahamed.batteryhealth.data.settings.EffectiveDesignCapacity
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.domain.Reading
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
 * `HealthContent` is exercised directly (as `HealthScreenTest` already does) rather than
 * through `HealthScreen`/`HealthViewModel`, so these tests don't need a Hilt test harness.
 * The "round trips" and "rejected without writing" tests still touch the app's real
 * DataStore through `SettingsStore` directly -- via the save callback, exactly the way
 * `HealthViewModel.setDesignCapacityOverride` does in production -- so, per
 * `SettingsStoreTest`'s established pattern, this suite clears it before and after to
 * stay hermetic.
 *
 * ## Two things this suite needs, learned by running it
 *
 * **The screen must be awake and unlocked.** With the display dozing, every Compose test
 * in the module fails with "No compose hierarchies found in the app" -- a message that
 * blames the Activity and says nothing about the screen. On this device's 30-second
 * timeout that turned a green suite into 47 failures spread across classes nobody had
 * touched, differing run to run, which reads exactly like flaky product code. See
 * `docs/running-instrumented-tests.md`.
 *
 * **Scroll to a row before clicking it.** `HealthContent` is a long `verticalScroll`
 * column and the design-capacity row sits in its last card, below the fold on a phone.
 * `performClick` does not scroll: it dispatches a touch at the node's centre, and for a
 * node outside the viewport that touch lands nowhere. Nothing throws -- the node is in the
 * semantics tree, so the call looks like it worked -- and it surfaces later and
 * misleadingly as the dialog's input "not found". `SettingsScreenTest` has the same rows
 * and dialog and got away without scrolling purely because that screen is shorter.
 *
 * Test bodies also avoid wrapping `compose.setContent` in `runBlocking`, and suspending
 * calls take their own narrow `runBlocking { }`. That is on general principle -- a
 * blocking event loop on the test thread has no business surrounding a Compose rule -- and
 * not because it was shown to fix anything here: it was tried against these failures first
 * and changed nothing. The screen and the scrolling were the real causes.
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
        compose.onNodeWithTag(DesignCapacityTags.ROW).performScrollTo().performClick()
        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertIsDisplayed()
    }

    @Test
    fun outOfRangeValueIsRejectedWithoutWriting() {
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

        compose.onNodeWithTag(DesignCapacityTags.ROW).performScrollTo().performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("99999")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        // Rejected in the dialog: the error is shown, save was never called, and the
        // dialog is still open rather than having closed on a value it refused.
        compose.onNodeWithText("Enter a value between 1000 and 10000 mAh").assertIsDisplayed()
        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(runBlocking { settings.designCapacityOverrideMah.first() })
    }

    @Test
    fun nonNumericValueIsRejectedWithoutWriting() {
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

        compose.onNodeWithTag(DesignCapacityTags.ROW).performScrollTo().performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("banana")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(DesignCapacityTags.ERROR).assertIsDisplayed()
        assertEquals(0, saveCalls)
        assertNull(runBlocking { settings.designCapacityOverrideMah.first() })
    }

    @Test
    fun validValueRoundTripsThroughRealSettings() {
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

        compose.onNodeWithTag(DesignCapacityTags.ROW).performScrollTo().performClick()
        compose.onNodeWithTag(DesignCapacityTags.INPUT).performTextInput("4200")
        compose.onNodeWithTag(DesignCapacityTags.SAVE).performClick()

        compose.onNodeWithTag(DesignCapacityTags.DIALOG).assertDoesNotExist()
        assertEquals(4200, runBlocking { settings.designCapacityOverrideMah.first() })
    }

    @Test
    fun clearRemovesAnExistingOverride() {
        runBlocking { settings.setDesignCapacityOverride(4820) }
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

        compose.onNodeWithTag(DesignCapacityTags.ROW).performScrollTo().performClick()
        compose.onNodeWithTag(DesignCapacityTags.CLEAR).performClick()

        assertEquals(true, cleared)
        assertNull(runBlocking { settings.designCapacityOverrideMah.first() })
    }
}

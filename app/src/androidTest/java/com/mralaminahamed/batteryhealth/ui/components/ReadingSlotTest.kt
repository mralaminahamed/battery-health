package com.mralaminahamed.batteryhealth.ui.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Rule
import org.junit.Test

class ReadingSlotTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun availableReadingRendersItsContent() {
        compose.setContent {
            BatteryHealthTheme {
                ReadingSlot(Reading.Available(86, Source.Privileged)) { value, _ ->
                    Text("$value%")
                }
            }
        }
        compose.onNodeWithText("86%").assertIsDisplayed()
        compose.onNodeWithTag(ReadingSlotTags.AVAILABLE).assertIsDisplayed()
        compose.onAllNodesWithTag(ReadingSlotTags.UNAVAILABLE).assertCountEquals(0)
    }

    @Test
    fun needsShizukuRendersReasonAndNotTheContent() {
        compose.setContent {
            BatteryHealthTheme {
                ReadingSlot(Reading.NeedsShizuku) { value: Int, _ -> Text("$value%") }
            }
        }
        compose.onNodeWithTag(ReadingSlotTags.UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText("Needs Shizuku").assertIsDisplayed()
        compose.onAllNodesWithTag(ReadingSlotTags.AVAILABLE).assertCountEquals(0)
    }

    @Test
    fun unsupportedRendersItsOwnReason() {
        compose.setContent {
            BatteryHealthTheme {
                ReadingSlot(Reading.Unsupported) { value: Int, _ -> Text("$value%") }
            }
        }
        compose.onNodeWithText("Not available on this device").assertIsDisplayed()
    }

    @Test
    fun notYetMeasuredRendersItsOwnReason() {
        compose.setContent {
            BatteryHealthTheme {
                ReadingSlot(Reading.NotYetMeasured) { value: Int, _ -> Text("$value%") }
            }
        }
        compose.onNodeWithText("Measuring").assertIsDisplayed()
    }
}

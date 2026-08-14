package com.mralaminahamed.batteryhealth.ui.history

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mralaminahamed.batteryhealth.domain.ChargeSession
import com.mralaminahamed.batteryhealth.domain.HistoryRange
import com.mralaminahamed.batteryhealth.domain.LevelPoint
import com.mralaminahamed.batteryhealth.domain.SessionType
import com.mralaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyHistorySaysSoRatherThanDrawingAnEmptyChart() {
        val state = HistoryUiState(HistoryRange.Day, emptyList(), emptyList())
        compose.setContent { BatteryHealthTheme { HistoryContent(state, {}, Modifier) } }

        compose.onNodeWithTag(HistoryScreenTags.ROOT).assertIsDisplayed()
        compose.onNodeWithText("No samples recorded yet").assertIsDisplayed()
        compose.onNodeWithText("No completed sessions yet").assertIsDisplayed()
    }

    @Test
    fun rendersChartAndSessionsWhenDataExists() {
        val state = HistoryUiState(
            range = HistoryRange.Day,
            points = listOf(LevelPoint(0, 40), LevelPoint(900_000, 42)),
            sessions = listOf(
                ChargeSession(
                    id = 1,
                    type = SessionType.Charge,
                    startedAtMs = 0,
                    endedAtMs = 5_100_000,
                    startLevelPct = 35,
                    endLevelPct = 80,
                    startCounterUah = 1_750_000,
                    endCounterUah = 4_000_000,
                    peakTempDeciC = 382,
                    avgMilliwatts = 9_400,
                    screenOnMs = 0,
                )
            ),
        )
        compose.setContent { BatteryHealthTheme { HistoryContent(state, {}, Modifier) } }

        compose.onNodeWithTag(HistoryScreenTags.CHART).assertIsDisplayed()
        compose.onNodeWithText("Charged 35% to 80%").assertIsDisplayed()
        compose.onNodeWithText("1 h 25 m").assertIsDisplayed()
        compose.onNodeWithText("9.40 W").assertIsDisplayed()
    }

    @Test
    fun tappingARangeReportsTheSelection() {
        var selected: HistoryRange? = null
        val state = HistoryUiState(HistoryRange.Day, emptyList(), emptyList())
        compose.setContent {
            BatteryHealthTheme { HistoryContent(state, { selected = it }, Modifier) }
        }

        compose.onNodeWithText("7 days").performClick()
        assertEquals(HistoryRange.Week, selected)
    }
}

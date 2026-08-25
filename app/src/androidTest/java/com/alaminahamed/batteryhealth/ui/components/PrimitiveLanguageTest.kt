package com.alaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrimitiveLanguageTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Restructured from the brief: `ComposeContentTestRule.setContent` throws
     * `IllegalStateException` if called more than once per test, so the per-language
     * `rowHeightFor` helper cannot be called twice as the brief's version does. Instead both
     * languages are composed as siblings in one `setContent` -- the same pattern
     * `ThemeWiringTest` uses for its (language, mode) cases -- each tagged distinctly, and
     * their measured heights compared afterwards.
     *
     * Also isolates the thing under test from a confound the brief's version has: `Value`
     * and the row label both render with `MaterialTheme.typography`, and Expressive's
     * `bodyLarge`/`titleMedium` are already a point size larger than One UI's (see Type.kt,
     * landed in an earlier task) -- purely from bolder, larger type Expressive's row already
     * measures taller than One UI's *before* `KeyValueRow` reads `rowVertical` from the
     * bundle at all, which would make a same-content height comparison pass whether or not
     * this task's change lands. So instead of comparing the two rows' raw heights, each
     * row's own label/value text height is measured and subtracted out, leaving only the
     * chrome around the text -- `2 * rowVertical` plus the (language-independent) divider
     * thickness -- which is what `rowVertical` actually controls. The assertion's intent
     * is unchanged: Expressive's chrome must measure taller, because its `rowVertical` is
     * 12dp against One UI's 9dp.
     */
    @Test
    fun expressiveRowsAreTallerThanOneUiRows() {
        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    Box(modifier = Modifier.testTag("row-oneui")) {
                        KeyValueRow(label = "Cycles") { Value("619") }
                    }
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    Box(modifier = Modifier.testTag("row-expressive")) {
                        KeyValueRow(label = "Cycles") { Value("619") }
                    }
                }
            }
        }
        compose.waitForIdle()

        fun rowChrome(rowTag: String): Float {
            val rowHeight = compose.onNodeWithTag(rowTag).getBoundsInRoot().height.value
            val isInRow = hasAnyAncestor(hasTestTag(rowTag))
            val labelHeight =
                compose.onNode(hasText("Cycles") and isInRow).getBoundsInRoot().height.value
            val valueHeight =
                compose.onNode(hasText("619") and isInRow).getBoundsInRoot().height.value
            return rowHeight - maxOf(labelHeight, valueHeight)
        }

        val oneUi = rowChrome("row-oneui")
        val expressive = rowChrome("row-expressive")

        // Expressive spacing sets rowVertical to 12dp against One UI's 9dp, a 6dp gap in
        // chrome. If the primitives were still hard-coding 9dp for both, the two chrome
        // values would be equal (modulo sub-dp measurement noise), so the margin here is a
        // strict `>` widened to a 1dp threshold -- comfortably below the real 6dp signal but
        // above the noise a same-value comparison showed during development (a ~0.00001dp
        // float rounding gap that a bare `>` would pass on by accident).
        assertTrue(
            "expected Expressive row chrome ($expressive) at least 1dp taller than One UI's ($oneUi)",
            expressive - oneUi > 1f,
        )
    }
}

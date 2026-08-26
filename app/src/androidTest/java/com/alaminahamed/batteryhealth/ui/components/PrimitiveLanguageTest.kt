package com.alaminahamed.batteryhealth.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.alaminahamed.batteryhealth.ui.theme.BatteryHealthTheme
import com.alaminahamed.batteryhealth.ui.theme.DesignLanguageId
import com.alaminahamed.batteryhealth.ui.theme.LocalDesignLanguage
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrimitiveLanguageTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Absolute-value assertion used for fields whose inter-language gap is wide enough (2-3dp:
     * `rowVertical`, `progressHeight`, `cardOuterHorizontal`) that a fixed tolerance can clear
     * measurement noise while staying comfortably under the real gap. Per the review's finding,
     * a cross-language *difference* assertion needs that same margin, so asserting each
     * language's *absolute* value against its own known constant sidesteps computing a
     * difference: the tolerance only has to absorb dp<->px rounding noise. Fields whose gap is
     * only 1dp (`cardOuterVertical`, `sectionHeaderBottom`, `unitOffsetStart`,
     * `unitOffsetBottom`) use [assertNearestExpected] instead -- a fixed tolerance can't
     * discriminate a 1dp gap without either missing real noise or failing to detect the
     * failure it exists to catch (task-4-re-review.md).
     */
    private fun assertApproximately(message: String, expectedDp: Float, actualDp: Float, toleranceDp: Float) {
        assertTrue(
            "$message: expected ${expectedDp}dp, measured ${actualDp}dp (tolerance ${toleranceDp}dp)",
            abs(expectedDp - actualDp) <= toleranceDp,
        )
    }

    /** Same idea as [assertApproximately], for the sampled colour in [sectionHeaderColorMatchesLanguageBranch]. */
    private fun assertColorApproximately(message: String, expected: Color, actual: Color, tolerancePerChannel: Float) {
        val close = abs(expected.red - actual.red) <= tolerancePerChannel &&
            abs(expected.green - actual.green) <= tolerancePerChannel &&
            abs(expected.blue - actual.blue) <= tolerancePerChannel
        assertTrue(
            "$message: expected ~$expected, sampled $actual (tolerance $tolerancePerChannel per channel)",
            close,
        )
    }

    /**
     * For tokens whose two languages differ by only 1dp (`cardOuterVertical` 5dp/4dp,
     * `sectionHeaderBottom` 6dp/5dp, `unitOffsetStart` 4dp/5dp, `unitOffsetBottom` 6dp/7dp),
     * [assertApproximately]'s fixed tolerance cannot discriminate: a tolerance under 0.5dp is
     * needed to tell the two languages' values apart at all, but the re-review measured
     * ~0.1-0.2dp of real device noise, leaving those four assertions little to no margin --
     * and `cardOuterVertical`'s 1.0dp tolerance was empirically *equal* to its 1.0dp gap, so it
     * passed even when the two languages' values were made identical (task-4-re-review.md).
     *
     * This replaces the fixed tolerance with a nearest-expected assertion: the measured value
     * must land closer to *this* language's expected token than to the other language's. That
     * discriminates for any noise under half the gap (0.5dp here, comfortably above the ~0.2dp
     * observed), needs no tolerance constant, and states the property the test actually cares
     * about -- that the primitive read the right language's token -- rather than merely "a
     * plausible number close to what I expected."
     *
     * The pass/fail decision itself is [isNearestExpected], a small `internal` function in
     * `main` rather than inlined here, so a JVM test (`NearestExpectedTest`) can pin its
     * behaviour without a device and without duplicating the comparison -- see that function's
     * doc. The distances below are recomputed only for the assertion message; they do not
     * affect the pass/fail outcome, which comes solely from [isNearestExpected].
     */
    private fun assertNearestExpected(message: String, measuredDp: Float, thisExpectedDp: Float, otherExpectedDp: Float) {
        val distanceToThis = abs(measuredDp - thisExpectedDp)
        val distanceToOther = abs(measuredDp - otherExpectedDp)
        assertTrue(
            "$message: measured ${measuredDp}dp should be closer to this language's " +
                "${thisExpectedDp}dp (distance ${distanceToThis}dp) than to the other " +
                "language's ${otherExpectedDp}dp (distance ${distanceToOther}dp)",
            isNearestExpected(measuredDp, thisExpectedDp, otherExpectedDp),
        )
    }

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

    /**
     * `ProgressTrack` is the cleanest case in this file: its outer `Box` carries `.height(...)`
     * directly (no wrapping `Text`, so no typography to isolate from), and nothing after
     * `.height(...)` in its modifier chain changes size. Its own measured bounds height is
     * therefore exactly `spacing.progressHeight`, read straight off the tagged node with no
     * chrome-subtraction needed.
     */
    @Test
    fun progressTrackHeightMatchesLanguageSpacing() {
        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    ProgressTrack(
                        fraction = 0.5f,
                        color = Color.Black,
                        modifier = Modifier.testTag("progress-oneui"),
                    )
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    ProgressTrack(
                        fraction = 0.5f,
                        color = Color.Black,
                        modifier = Modifier.testTag("progress-expressive"),
                    )
                }
            }
        }
        compose.waitForIdle()

        val oneUiHeight = compose.onNodeWithTag("progress-oneui").getBoundsInRoot().height.value
        val expressiveHeight = compose.onNodeWithTag("progress-expressive").getBoundsInRoot().height.value

        assertApproximately("One UI progress track height", 9f, oneUiHeight, 0.5f)
        assertApproximately("Expressive progress track height", 6f, expressiveHeight, 0.5f)
    }

    /**
     * `OneUiCard`'s own modifier chain is `fillMaxWidth().padding(outer).clip(...).background(...)
     * .padding(cardInner)`: because `fillMaxWidth()` is outermost, the card's own reported bounds
     * always span the full parent width regardless of `cardOuterHorizontal` -- the outer padding
     * only shifts the *content* inward, it does not shrink the card's own box. So this measures a
     * tagged marker placed as the card's sole child against the card's own tagged bounds (the
     * "child's bounds against the root" the brief calls out): the marker's inset from the card's
     * top-left is `cardOuterHorizontal + cardInner` horizontally and `cardOuterVertical + cardInner`
     * vertically. `cardInner` is 16dp in both languages, so it cancels out of the *cross-language*
     * comparison, but this asserts the absolute inset per language (30dp/28dp horizontal,
     * 21dp/20dp vertical) rather than relying on that cancellation: horizontal uses
     * [assertApproximately] (2dp gap, wide enough for a fixed tolerance), vertical uses
     * [assertNearestExpected] (1dp gap -- see that helper's doc).
     * The marker is a fixed-size `Box`, not text, so there is no typography to isolate here.
     */
    @Test
    fun oneUiCardOuterPaddingMatchesLanguageSpacing() {
        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    OneUiCard(modifier = Modifier.testTag("card-oneui")) {
                        Box(modifier = Modifier.testTag("card-oneui-marker").size(2.dp))
                    }
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    OneUiCard(modifier = Modifier.testTag("card-expressive")) {
                        Box(modifier = Modifier.testTag("card-expressive-marker").size(2.dp))
                    }
                }
            }
        }
        compose.waitForIdle()

        fun outerInsets(cardTag: String, markerTag: String): Pair<Float, Float> {
            val card = compose.onNodeWithTag(cardTag).getBoundsInRoot()
            val marker = compose.onNodeWithTag(markerTag).getBoundsInRoot()
            val horizontal = marker.left.value - card.left.value
            val vertical = marker.top.value - card.top.value
            return horizontal to vertical
        }

        val (oneUiHorizontal, oneUiVertical) = outerInsets("card-oneui", "card-oneui-marker")
        val (expressiveHorizontal, expressiveVertical) = outerInsets("card-expressive", "card-expressive-marker")

        // horizontal = cardOuterHorizontal + cardInner, vertical = cardOuterVertical + cardInner.
        // cardOuterHorizontal has a 2dp inter-language gap (14 vs 12), wide enough for a fixed
        // tolerance to discriminate with real margin -- left as assertApproximately.
        // cardOuterVertical has only a 1dp gap (5 vs 4), so per assertNearestExpected's doc,
        // it's asserted as nearest-expected instead. cardInner (16dp) is identical in both
        // languages, so it cancels out of the nearest-expected comparison -- shifting both
        // insets by the same constant does not change which expected value the measured
        // value is closer to.
        assertApproximately("One UI card horizontal inset", 14f + 16f, oneUiHorizontal, 1f)
        assertApproximately("Expressive card horizontal inset", 12f + 16f, expressiveHorizontal, 1f)
        assertNearestExpected("One UI card vertical inset", oneUiVertical, 5f + 16f, 4f + 16f)
        assertNearestExpected("Expressive card vertical inset", expressiveVertical, 4f + 16f, 5f + 16f)
    }

    /**
     * `BigMetric`'s unit `Text` carries `Modifier.padding(start = unitOffsetStart, bottom =
     * unitOffsetBottom)` directly on itself (not passed in from a caller-visible `modifier`
     * parameter), so there is nowhere to attach a `testTag` to that padded node itself, the way
     * `OneUiCard`'s marker or `ProgressTrack`'s own tag could. Locating it by content instead
     * (`hasText("%")`) turned up a real gotcha, confirmed empirically on-device: the node that
     * search resolves to reports a *width/height that excludes its own padding* (its measured
     * size equals a same-style, unpadded reference text's size, not size-plus-padding), while its
     * *position* (`left`/`bottom`) correctly reflects where the padding shifted its content. A
     * width/height-subtraction "chrome isolation" -- the technique that works for `SectionHeader`
     * below, where the tag sits on the same modifier chain as the padding -- therefore silently
     * measured zero here instead of the real offset.
     *
     * The fix uses position instead of size, anchored on `value` -- `BigMetric`'s *other* child,
     * which carries no padding of its own, so its own position is exactly where `Row` placed it
     * with no discrepancy to worry about. `Row` lays out `value` and `unit` back-to-back with no
     * arrangement gap, and `verticalAlignment = Alignment.Bottom` aligns each child's own box to
     * the row's bottom edge, so:
     *  - `unit.left - value.right` is exactly `unitOffsetStart` (the gap between value's true
     *    right edge and unit's content start, which padding pushed inward).
     *  - `value.bottom - unit.bottom` is exactly `unitOffsetBottom` (value's bottom already sits
     *    on the row's bottom edge, having no padding of its own to move it; unit's content sits
     *    `unitOffsetBottom` above that edge).
     * Both are pure `Row`-placement geometry, unaffected by `value`'s and `unit`'s own glyph sizes
     * differing between languages (headlineMedium/titleMedium, see Type.kt) -- so, unlike
     * `SectionHeader` below, no separate reference text is needed to isolate typography here.
     */
    @Test
    fun bigMetricUnitOffsetMatchesLanguageSpacing() {
        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    BigMetric(
                        value = "92",
                        unit = "%",
                        color = Color.Black,
                        modifier = Modifier.testTag("metric-oneui"),
                    )
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    BigMetric(
                        value = "92",
                        unit = "%",
                        color = Color.Black,
                        modifier = Modifier.testTag("metric-expressive"),
                    )
                }
            }
        }
        compose.waitForIdle()

        fun unitOffsets(metricTag: String): Pair<Float, Float> {
            val isInMetric = hasAnyAncestor(hasTestTag(metricTag))
            val value = compose.onNode(hasText("92") and isInMetric).getBoundsInRoot()
            val unit = compose.onNode(hasText("%") and isInMetric).getBoundsInRoot()
            val start = unit.left.value - value.right.value
            val bottom = value.bottom.value - unit.bottom.value
            return start to bottom
        }

        val (oneUiStart, oneUiBottom) = unitOffsets("metric-oneui")
        val (expressiveStart, expressiveBottom) = unitOffsets("metric-expressive")

        // unitOffsetStart (4dp/5dp) and unitOffsetBottom (6dp/7dp) both have only a 1dp
        // inter-language gap -- per assertNearestExpected's doc, both are nearest-expected.
        assertNearestExpected("One UI unit start offset", oneUiStart, 4f, 5f)
        assertNearestExpected("One UI unit bottom offset", oneUiBottom, 6f, 7f)
        assertNearestExpected("Expressive unit start offset", expressiveStart, 5f, 4f)
        assertNearestExpected("Expressive unit bottom offset", expressiveBottom, 7f, 6f)
    }

    /**
     * `SectionHeader`'s `Text` carries `Modifier.padding(bottom = sectionHeaderBottom)` on
     * itself, the same shape of problem as `BigMetric`'s unit text, and `labelSmall` is not
     * quite identical between languages either (11sp Bold in both, but letter-spacing differs --
     * see Type.kt), so this reuses the same reference-text subtraction technique: an unpadded
     * `Text` rendering the identical (already-uppercase) string in `labelSmall` is composed
     * alongside `SectionHeader`, and its height is subtracted from the header's own to isolate
     * `sectionHeaderBottom` from whatever the glyphs themselves measure.
     *
     * This is also the assertion that would have caught the reviewer's first escaping mutation
     * (swapping `sectionHeaderBottom` with `unitOffsetBottom` in `OneUiLanguage.kt`): both are
     * 6dp in One UI, so it is invisible there (and this test's One UI case does not move), but
     * 5dp vs 7dp in Expressive, so the Expressive case fails.
     */
    @Test
    fun sectionHeaderBottomSpacingMatchesLanguage() {
        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    Column {
                        SectionHeader(text = "STATUS", modifier = Modifier.testTag("header-oneui"))
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("header-oneui-ref"),
                        )
                    }
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    Column {
                        SectionHeader(text = "STATUS", modifier = Modifier.testTag("header-expressive"))
                        Text(
                            text = "STATUS",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("header-expressive-ref"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        fun bottomSpacing(headerTag: String, refTag: String): Float {
            val header = compose.onNodeWithTag(headerTag).getBoundsInRoot().height.value
            val reference = compose.onNodeWithTag(refTag).getBoundsInRoot().height.value
            return header - reference
        }

        // sectionHeaderBottom has only a 1dp inter-language gap (6dp/5dp) -- per
        // assertNearestExpected's doc, this is nearest-expected rather than a fixed tolerance.
        assertNearestExpected(
            "One UI section header bottom spacing",
            bottomSpacing("header-oneui", "header-oneui-ref"),
            6f,
            5f,
        )
        assertNearestExpected(
            "Expressive section header bottom spacing",
            bottomSpacing("header-expressive", "header-expressive-ref"),
            5f,
            6f,
        )
    }

    /**
     * `SectionHeader`'s colour branch is not exposed through semantics (Compose does not publish
     * a `Text`'s resolved colour as a semantics property), so per the brief this samples it from
     * a captured image instead: each header is drawn over an opaque white background inside a
     * tagged `Box`, the box is captured with `captureToImage()`, and the pixel furthest from
     * white (i.e. the most-covered part of a glyph stroke) is taken as the rendered ink colour.
     * The expected colour is not hard-coded -- it is captured from `LocalDesignLanguage.current`
     * inside the same composition, using the field the spec assigns to each language (`accent`
     * for One UI, `textSecondary` for Expressive), so this asserts "the header used the field the
     * spec says it should," not just "the header used *some* colour."
     *
     * This is the assertion that would have caught the reviewer's second escaping mutation
     * (deleting `SectionHeader`'s `when (language.id)` branch, leaving it always `colors.accent`):
     * One UI's expected colour is already `colors.accent`, so that case does not move, but
     * Expressive's sampled ink would then be Expressive's own accent blue instead of its
     * textSecondary grey, which is well outside the per-channel tolerance below.
     */
    @Test
    fun sectionHeaderColorMatchesLanguageBranch() {
        var oneUiExpected: Color? = null
        var expressiveExpected: Color? = null

        compose.setContent {
            Column {
                BatteryHealthTheme(languageId = DesignLanguageId.OneUi, darkTheme = false) {
                    oneUiExpected = LocalDesignLanguage.current.colors.accent
                    Box(modifier = Modifier.testTag("header-oneui-swatch").background(Color.White)) {
                        SectionHeader(text = "STATUS")
                    }
                }
                BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                    expressiveExpected = LocalDesignLanguage.current.colors.textSecondary
                    Box(modifier = Modifier.testTag("header-expressive-swatch").background(Color.White)) {
                        SectionHeader(text = "STATUS")
                    }
                }
            }
        }
        compose.waitForIdle()

        fun inkColor(swatchTag: String): Color {
            val pixels = compose.onNodeWithTag(swatchTag).captureToImage().toPixelMap()
            var darkest = Color.White
            var darkestDistance = 0f
            for (x in 0 until pixels.width) {
                for (y in 0 until pixels.height) {
                    val sample = pixels[x, y]
                    val distance = (1f - sample.red) + (1f - sample.green) + (1f - sample.blue)
                    if (distance > darkestDistance) {
                        darkestDistance = distance
                        darkest = sample
                    }
                }
            }
            return darkest
        }

        assertColorApproximately(
            "One UI section header colour (accent)",
            requireNotNull(oneUiExpected),
            inkColor("header-oneui-swatch"),
            0.15f,
        )
        assertColorApproximately(
            "Expressive section header colour (textSecondary)",
            requireNotNull(expressiveExpected),
            inkColor("header-expressive-swatch"),
            0.15f,
        )
    }
}

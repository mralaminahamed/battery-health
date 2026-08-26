package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.ui.unit.dp

private val OneUiShapes = LanguageShapes(
    card = 24.dp,
    pill = 999.dp,
    small = 8.dp,
)

private val OneUiSpacing = LanguageSpacing(
    cardOuterHorizontal = 14.dp,
    cardOuterVertical = 5.dp,
    cardInner = 16.dp,
    rowVertical = 9.dp,
    sectionHeaderBottom = 6.dp,
    progressHeight = 9.dp,
    unitOffsetStart = 4.dp,
    unitOffsetBottom = 6.dp,
)

/**
 * The values this app has shipped since the One UI design pass. P1 moves them behind the
 * bundle without altering one of them — every literal here is byte-identical to trunk's
 * (`git show f1d5495:.../Tokens.kt`), and `DesignLanguageTest.oneUiKeepsTheValuesTheAppShipsToday`
 * / `oneUiDarkKeepsTheValuesTheAppShipsToday` pin all nine colour fields plus every shape and
 * spacing value trunk shipped against those trunk literals, not merely against each other.
 * `shapes.small` has no trunk literal to pin against -- it is a new field, covered instead by
 * `MaterialShapesTest` once `BatteryHealthTheme` gave it a consumer.
 *
 * That is not the same claim as "a Samsung device looks identical before and after", and this
 * comment used to say the second thing. It does not follow from the first: trunk's `Theme.kt`
 * set five `colorScheme` roles and no `Shapes`, so every other Material role and all five
 * corner radii came from Material's own defaults. This bundle populates fourteen roles and a
 * full `Shapes` (see `BatteryHealthTheme`), which is the point of P1 — "Material components
 * stop rendering off-palette" is a change by construction. On a Samsung device the *rendered*
 * result therefore differs from trunk in several places, most of them improvements: the
 * `AlertDialog` corner radius (28dp → 24dp) and container tint, the `Switch` track, the
 * text-field border, `error`, and (dark mode) the `UnlockCard` button's label contrast
 * (~4.6:1 → ~8.4:1). See final-review.md §2 and final-fix-report.md for the full enumeration
 * and the measured figures.
 */
fun oneUiLanguage(dark: Boolean) = DesignLanguage(
    id = DesignLanguageId.OneUi,
    colors = if (dark) DarkOneUiColors else LightOneUiColors,
    shapes = OneUiShapes,
    spacing = OneUiSpacing,
    typography = OneUiTypography,
)

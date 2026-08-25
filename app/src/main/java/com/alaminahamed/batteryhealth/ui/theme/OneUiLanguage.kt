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
 * bundle without altering one of them, so a Samsung device looks identical before and
 * after — `DesignLanguageTest.oneUiKeepsTheValuesTheAppShipsToday` is the guard on that.
 */
fun oneUiLanguage(dark: Boolean) = DesignLanguage(
    id = DesignLanguageId.OneUi,
    colors = if (dark) DarkOneUiColors else LightOneUiColors,
    shapes = OneUiShapes,
    spacing = OneUiSpacing,
    typography = OneUiTypography,
)

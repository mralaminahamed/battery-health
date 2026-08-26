package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tonal surfaces rather than white cards on grey, and a filled container for headline
 * figures. Dynamic colour stays off, so these are fixed values keyed to the app's own
 * accent rather than derived from the wallpaper.
 */
private val LightExpressiveColors = OneUiColors(
    canvas = Color(0xFFF7F9FC),
    card = Color(0xFFEDF0F5),
    accent = Color(0xFF0B4CC4),
    textPrimary = Color(0xFF191C20),
    textSecondary = Color(0xFF5A6470),
    divider = Color(0xFFE1E5EB),
    heroContainer = Color(0xFFDCE5FF),
    onHeroContainer = Color(0xFF0B4CC4),
    good = Color(0xFF146C43),
    fair = Color(0xFF8A5300),
    poor = Color(0xFFB3261E),
)

private val DarkExpressiveColors = OneUiColors(
    canvas = Color(0xFF101418),
    card = Color(0xFF1A1F26),
    accent = Color(0xFFA9C7FF),
    textPrimary = Color(0xFFE3E6EA),
    textSecondary = Color(0xFFA3ADBA),
    divider = Color(0xFF2A303A),
    heroContainer = Color(0xFF1E2E4D),
    onHeroContainer = Color(0xFFA9C7FF),
    good = Color(0xFF6EDCA0),
    fair = Color(0xFFFFCF7A),
    poor = Color(0xFFFFB4AB),
)

private val ExpressiveShapes = LanguageShapes(
    card = 20.dp,
    pill = 999.dp,
    small = 12.dp,
)

private val ExpressiveSpacing = LanguageSpacing(
    cardOuterHorizontal = 12.dp,
    cardOuterVertical = 4.dp,
    cardInner = 16.dp,
    // Roomier than One UI's 9dp: Expressive rows are taller, which also gives a
    // comfortable touch target when a row becomes tappable in P5.
    rowVertical = 12.dp,
    sectionHeaderBottom = 5.dp,
    progressHeight = 6.dp,
    unitOffsetStart = 5.dp,
    unitOffsetBottom = 7.dp,
)

fun expressiveLanguage(dark: Boolean) = DesignLanguage(
    id = DesignLanguageId.Expressive,
    colors = if (dark) DarkExpressiveColors else LightExpressiveColors,
    shapes = ExpressiveShapes,
    spacing = ExpressiveSpacing,
    typography = ExpressiveTypography,
)

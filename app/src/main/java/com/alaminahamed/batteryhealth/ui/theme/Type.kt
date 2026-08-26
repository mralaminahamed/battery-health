package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tabular figures, applied to every style that can carry a number, so digits keep
 * a fixed advance width and readings stop jittering as they update. TextStyle takes
 * the raw OpenType feature tag string.
 */
private const val TABULAR_FIGURES = "tnum"

private fun numeric(
    size: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

val OneUiTypography = Typography(
    headlineLarge = numeric(27, FontWeight.Bold, -0.5),
    headlineMedium = numeric(42, FontWeight.Bold, -2.0),
    titleMedium = numeric(15, FontWeight.SemiBold),
    bodyLarge = numeric(14, FontWeight.Normal),
    bodyMedium = numeric(12, FontWeight.Normal),
    labelSmall = numeric(11, FontWeight.Bold, 0.6),
)

/**
 * Material 3 Expressive runs heavier and slightly larger than One UI at every level —
 * that weight is the most recognisable part of the language. Tabular figures still apply,
 * because the readings jitter without them regardless of which language is active.
 */
val ExpressiveTypography = Typography(
    headlineLarge = numeric(29, FontWeight.ExtraBold, -0.7),
    headlineMedium = numeric(46, FontWeight.ExtraBold, -2.2),
    titleMedium = numeric(16, FontWeight.Bold),
    bodyLarge = numeric(15, FontWeight.Normal),
    bodyMedium = numeric(13, FontWeight.Normal),
    labelSmall = numeric(11, FontWeight.Bold, 0.7),
)

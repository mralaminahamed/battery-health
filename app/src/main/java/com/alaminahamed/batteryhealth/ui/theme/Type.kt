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

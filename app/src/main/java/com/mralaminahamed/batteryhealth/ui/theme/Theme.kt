package com.mralaminahamed.batteryhealth.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Dynamic colour is deliberately absent. The fixed Samsung blue is the product's
 * identity, and a wallpaper-tinted battery app reads as generic.
 */
@Composable
fun BatteryHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val oneUi = if (darkTheme) DarkOneUiColors else LightOneUiColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = oneUi.accent,
            background = oneUi.canvas,
            surface = oneUi.card,
            onBackground = oneUi.textPrimary,
            onSurface = oneUi.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = oneUi.accent,
            background = oneUi.canvas,
            surface = oneUi.card,
            onBackground = oneUi.textPrimary,
            onSurface = oneUi.textPrimary,
        )
    }

    CompositionLocalProvider(LocalOneUiColors provides oneUi) {
        MaterialTheme(colorScheme = scheme, typography = OneUiTypography, content = content)
    }
}

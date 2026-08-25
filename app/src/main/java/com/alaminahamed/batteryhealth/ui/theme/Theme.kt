package com.alaminahamed.batteryhealth.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Dynamic colour is deliberately absent. The fixed accent is the product's identity, a
 * wallpaper-tinted battery app reads as generic, and now that the app ships to every
 * Android device rather than only Samsung phones, looking the same everywhere matters more
 * than matching one device's wallpaper.
 *
 * [languageId] is passed in rather than resolved here so that the composable stays
 * previewable and testable without a device or a DataStore read. `BatteryHealthApp`'s
 * caller supplies it.
 *
 * [languageId] defaults to resolving [DesignLanguageChoice.Auto] from the device rather than
 * to a fixed language: a forgotten parameter must degrade to the language that device should
 * have, not to a constant that happens to be right on whichever phone tested it. Every
 * pre-existing preview and screen test omits this parameter, and on a Samsung device that
 * still resolves to [DesignLanguageId.OneUi] — the language they were written against — so
 * they keep rendering exactly as before. On any other manufacturer it now correctly resolves
 * to [DesignLanguageId.Expressive] instead of silently rendering Samsung's language. `.orEmpty()`
 * guards a JVM unit test reaching this composable off-device, where `Build.MANUFACTURER` would
 * otherwise NPE; `resolveDesignLanguageId` already treats an empty string as non-Samsung. Task 5
 * removes the need for the default once every caller supplies the stored choice.
 */
@Composable
fun BatteryHealthTheme(
    languageId: DesignLanguageId = resolveDesignLanguageId(
        DesignLanguageChoice.Auto,
        Build.MANUFACTURER.orEmpty(),
    ),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val language = designLanguageFor(languageId, darkTheme)
    val colors = language.colors

    // Derived from the bundle rather than declared separately. These were two independent
    // sources of truth before, so any Material component that was not explicitly themed
    // rendered off-palette.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.canvas,
            primaryContainer = colors.heroContainer,
            onPrimaryContainer = colors.onHeroContainer,
            background = colors.canvas,
            onBackground = colors.textPrimary,
            surface = colors.card,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.divider,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
            error = colors.poor,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.card,
            primaryContainer = colors.heroContainer,
            onPrimaryContainer = colors.onHeroContainer,
            background = colors.canvas,
            onBackground = colors.textPrimary,
            surface = colors.card,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.divider,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.divider,
            error = colors.poor,
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(language.shapes.small / 2),
        small = RoundedCornerShape(language.shapes.small),
        medium = RoundedCornerShape(language.shapes.card),
        large = RoundedCornerShape(language.shapes.card),
        extraLarge = RoundedCornerShape(language.shapes.card),
    )

    CompositionLocalProvider(
        LocalDesignLanguage provides language,
        // Shim for the 33 call sites P3 will migrate. See LocalOneUiColors' own doc.
        LocalOneUiColors provides colors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = language.typography,
            shapes = shapes,
            content = content,
        )
    }
}

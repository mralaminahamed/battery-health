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
            // Material has two distinct roles here and this bundle has two distinct
            // tokens for them: `outline` is for a boundary that must be *seen* (an
            // OutlinedTextField's resting border, a Switch's unchecked thumb) so it
            // reads from `textSecondary`, not `divider`. `outlineVariant` is for a
            // subtle separator, which is what `divider` actually is. Collapsing both
            // onto `divider` (the previous mapping) made every OutlinedTextField's
            // unfocused border exactly the colour of the AlertDialog surface behind
            // it -- 1.00:1 contrast -- since surfaceContainerHigh below is also
            // `divider`. See DesignLanguageTest / ThemeWiringTest for the pinned
            // values and final-fix-report.md for the measured contrast ratios.
            outline = colors.textSecondary,
            outlineVariant = colors.divider,
            error = colors.poor,
            // Unset, these fall back to Material's baseline surface-container tokens
            // rather than anything from the bundle: Switch's uncheckedTrackColor
            // (SwitchTokens.UnselectedTrackColor) resolves to surfaceContainerHighest,
            // and AlertDialog's container (DialogTokens.ContainerColor) resolves to
            // surfaceContainerHigh. `divider` is this app's own "a tint distinct from
            // card" token, so both roles read as an app-palette neutral instead of
            // Material's default lavender-tinted grey.
            surfaceContainerHigh = colors.divider,
            surfaceContainerHighest = colors.divider,
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
            // See the matching comment in the dark branch above.
            outline = colors.textSecondary,
            outlineVariant = colors.divider,
            error = colors.poor,
            // See the matching comment in the dark branch above.
            surfaceContainerHigh = colors.divider,
            surfaceContainerHighest = colors.divider,
        )
    }

    val shapes = materialShapesFor(language.shapes)

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

/**
 * The `Shapes` object [BatteryHealthTheme] hands to `MaterialTheme`, pulled out to a plain
 * function so a JVM test (`MaterialShapesTest`) can pin the derivation without a composition.
 * Before this existed, nothing asserted `MaterialTheme.shapes` at all: `DesignLanguageTest`
 * pinned the *bundle's* `shapes.card`/`shapes.pill`, but deleting `shapes = shapes` above, or
 * changing `OneUiShapes.small` from 8dp to 13dp, passed the full JVM suite -- silently
 * reinstating the "two parallel sources of truth" defect this theme exists to close, on the
 * one axis nothing guarded. `ThemeWiringTest.materialShapesAreDerivedFromTheSelectedLanguage`
 * is the composition-level counterpart: it proves this function's result is what actually
 * reaches `MaterialTheme`, which a JVM test cannot.
 *
 * `medium`/`large`/`extraLarge` all collapse onto `shapes.card`: this app has exactly one
 * card corner radius per language, and Material has no live consumer that needs them to
 * differ (`AlertDialog` reads `extraLarge`, everything else that could read `medium`/`large`
 * is either absent from this app or explicitly shaped by its own call site).
 * `extraSmall`/`small` come from `shapes.small`, halved for `extraSmall` -- `small` is what
 * `OutlinedTextFieldTokens.ContainerShape` reads, so it is the one value here with a live
 * Material consumer beyond `AlertDialog`.
 */
internal fun materialShapesFor(shapes: LanguageShapes): Shapes = Shapes(
    extraSmall = RoundedCornerShape(shapes.small / 2),
    small = RoundedCornerShape(shapes.small),
    medium = RoundedCornerShape(shapes.card),
    large = RoundedCornerShape(shapes.card),
    extraLarge = RoundedCornerShape(shapes.card),
)

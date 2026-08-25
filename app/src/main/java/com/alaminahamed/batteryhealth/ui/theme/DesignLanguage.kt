package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner radii. `pill` is a fully rounded track or chip. */
@Immutable
data class LanguageShapes(
    val card: Dp,
    val pill: Dp,
    val small: Dp,
)

/**
 * Every dimension the shared primitives in `OneUiComponents` need. Named for the thing
 * each one positions rather than for a size, so a language can change the rhythm without
 * every call site knowing which number it picked.
 */
@Immutable
data class LanguageSpacing(
    val cardOuterHorizontal: Dp,
    val cardOuterVertical: Dp,
    val cardInner: Dp,
    val rowVertical: Dp,
    val sectionHeaderBottom: Dp,
    val progressHeight: Dp,
    val unitOffsetStart: Dp,
    val unitOffsetBottom: Dp,
)

/**
 * One design language's complete set of values. Screens and primitives read this rather
 * than hard-coding anything, which is what lets the app carry two languages without
 * duplicating a single screen.
 */
@Immutable
data class DesignLanguage(
    val id: DesignLanguageId,
    val colors: OneUiColors,
    val shapes: LanguageShapes,
    val spacing: LanguageSpacing,
    val typography: Typography,
)

/**
 * Exhaustive over [DesignLanguageId] deliberately: adding a language must fail the build
 * here rather than silently falling back to whichever one an `else` branch named. This
 * project has already shipped one provenance bug of exactly that shape.
 */
fun designLanguageFor(id: DesignLanguageId, dark: Boolean): DesignLanguage = when (id) {
    DesignLanguageId.OneUi -> oneUiLanguage(dark)
    DesignLanguageId.Expressive -> expressiveLanguage(dark)
}

/**
 * No default value. A composable reading this outside [BatteryHealthTheme] is a bug, and a
 * default would hide it behind a plausible-looking screen — the same reasoning that keeps
 * absence explicit everywhere else in this app.
 */
val LocalDesignLanguage = staticCompositionLocalOf<DesignLanguage> {
    error("No DesignLanguage provided. Wrap the content in BatteryHealthTheme.")
}

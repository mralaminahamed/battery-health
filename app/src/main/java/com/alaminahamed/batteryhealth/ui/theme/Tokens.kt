package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.alaminahamed.batteryhealth.domain.HealthBand

@Immutable
data class OneUiColors(
    val canvas: Color,
    val card: Color,
    val accent: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    /**
     * The tonal block behind a headline figure. Expressive uses a filled container here;
     * One UI has no such surface and maps it onto its ordinary card, so a Samsung device
     * does not acquire a Material-looking hero.
     */
    val heroContainer: Color,
    val onHeroContainer: Color,
    val good: Color,
    val fair: Color,
    val poor: Color,
) {
    fun forBand(band: HealthBand): Color = when (band) {
        HealthBand.Good -> good
        HealthBand.Fair -> fair
        HealthBand.Poor -> poor
    }
}

val LightOneUiColors = OneUiColors(
    canvas = Color(0xFFF3F4F6),
    card = Color(0xFFFFFFFF),
    accent = Color(0xFF0F62FE),
    textPrimary = Color(0xFF1B1D20),
    textSecondary = Color(0xFF6A7078),
    divider = Color(0xFFEEF0F3),
    heroContainer = Color(0xFFFFFFFF),
    onHeroContainer = Color(0xFF0F62FE),
    good = Color(0xFF0F9D58),
    fair = Color(0xFFF5A623),
    poor = Color(0xFFE5484D),
)

val DarkOneUiColors = OneUiColors(
    canvas = Color(0xFF000000),
    card = Color(0xFF1B1D1F),
    accent = Color(0xFF5A9BFF),
    textPrimary = Color(0xFFF5F6F7),
    textSecondary = Color(0xFF9AA0A8),
    divider = Color(0xFF2A2D31),
    heroContainer = Color(0xFF1B1D1F),
    onHeroContainer = Color(0xFF5A9BFF),
    good = Color(0xFF3DD68C),
    fair = Color(0xFFFFB84D),
    poor = Color(0xFFFF6369),
)

val LocalOneUiColors = staticCompositionLocalOf { LightOneUiColors }

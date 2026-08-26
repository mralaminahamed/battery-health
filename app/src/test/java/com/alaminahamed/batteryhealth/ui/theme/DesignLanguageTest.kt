package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DesignLanguageTest {

    @Test
    fun oneUiKeepsTheValuesTheAppShipsToday() {
        // These are the current hard-coded values in OneUiComponents and Tokens. This test
        // is the guard that P1 is a refactor for Samsung users, not a redesign: if any of
        // these change, a Samsung device's appearance changed and that is out of scope.
        val light = designLanguageFor(DesignLanguageId.OneUi, dark = false)
        assertEquals(LightOneUiColors, light.colors)
        // Literal hex checks, independent of LightOneUiColors: comparing light.colors
        // against the same constant it was built from would pass even if the constant's
        // own values drifted, since both sides move together. All nine fields trunk shipped
        // (git show f1d5495:.../Tokens.kt) are pinned here, not just accent/canvas -- a prior
        // round pinned only two of nine, so mutating e.g. `divider` to magenta passed
        // 308/308 (final-review.md Important 2). `heroContainer`/`onHeroContainer` are new
        // fields with no trunk counterpart, so they are not pinned against a trunk literal
        // here (Minor 3 tracks them as unconsumed).
        assertEquals(Color(0xFF0F62FE), light.colors.accent)
        assertEquals(Color(0xFFF3F4F6), light.colors.canvas)
        assertEquals(Color(0xFFFFFFFF), light.colors.card)
        assertEquals(Color(0xFF1B1D20), light.colors.textPrimary)
        assertEquals(Color(0xFF6A7078), light.colors.textSecondary)
        assertEquals(Color(0xFFEEF0F3), light.colors.divider)
        assertEquals(Color(0xFF0F9D58), light.colors.good)
        assertEquals(Color(0xFFF5A623), light.colors.fair)
        assertEquals(Color(0xFFE5484D), light.colors.poor)
        assertEquals(24, light.shapes.card.value.toInt())
        assertEquals(999, light.shapes.pill.value.toInt())
        assertEquals(14, light.spacing.cardOuterHorizontal.value.toInt())
        assertEquals(5, light.spacing.cardOuterVertical.value.toInt())
        assertEquals(16, light.spacing.cardInner.value.toInt())
        assertEquals(9, light.spacing.rowVertical.value.toInt())
        assertEquals(6, light.spacing.sectionHeaderBottom.value.toInt())
        assertEquals(9, light.spacing.progressHeight.value.toInt())
        assertEquals(4, light.spacing.unitOffsetStart.value.toInt())
        assertEquals(6, light.spacing.unitOffsetBottom.value.toInt())
        assertEquals(OneUiTypography, light.typography)
    }

    /**
     * The dark-mode counterpart to [oneUiKeepsTheValuesTheAppShipsToday]: before this test,
     * `DarkOneUiColors` had no literal pin at all, light or dark, on any of its nine fields.
     * Same trunk source (`git show f1d5495:.../Tokens.kt`), dark half.
     */
    @Test
    fun oneUiDarkKeepsTheValuesTheAppShipsToday() {
        val dark = designLanguageFor(DesignLanguageId.OneUi, dark = true)
        assertEquals(DarkOneUiColors, dark.colors)
        assertEquals(Color(0xFF5A9BFF), dark.colors.accent)
        assertEquals(Color(0xFF000000), dark.colors.canvas)
        assertEquals(Color(0xFF1B1D1F), dark.colors.card)
        assertEquals(Color(0xFFF5F6F7), dark.colors.textPrimary)
        assertEquals(Color(0xFF9AA0A8), dark.colors.textSecondary)
        assertEquals(Color(0xFF2A2D31), dark.colors.divider)
        assertEquals(Color(0xFF3DD68C), dark.colors.good)
        assertEquals(Color(0xFFFFB84D), dark.colors.fair)
        assertEquals(Color(0xFFFF6369), dark.colors.poor)
    }

    @Test
    fun darkAndLightDifferPerLanguage() {
        assertNotEquals(
            designLanguageFor(DesignLanguageId.OneUi, dark = false).colors,
            designLanguageFor(DesignLanguageId.OneUi, dark = true).colors,
        )
        assertNotEquals(
            designLanguageFor(DesignLanguageId.Expressive, dark = false).colors,
            designLanguageFor(DesignLanguageId.Expressive, dark = true).colors,
        )
    }

    @Test
    fun theTwoLanguagesAreActuallyDifferentOnEveryAxis() {
        // A bundle that returned identical values for both ids would compile, satisfy the
        // selection tests, and ship one design language while claiming two.
        val oneUi = designLanguageFor(DesignLanguageId.OneUi, dark = false)
        val expressive = designLanguageFor(DesignLanguageId.Expressive, dark = false)
        assertNotEquals("colors", oneUi.colors, expressive.colors)
        assertNotEquals("shapes", oneUi.shapes, expressive.shapes)
        assertNotEquals("spacing", oneUi.spacing, expressive.spacing)
        assertNotEquals("typography", oneUi.typography, expressive.typography)
    }

    @Test
    fun everyLanguageAndModeCombinationResolves() {
        for (id in DesignLanguageId.entries) {
            for (dark in listOf(false, true)) {
                // Driven off entries so a third language cannot be added without either
                // supplying values here or failing.
                val language = designLanguageFor(id, dark)
                assertNotEquals("$id dark=$dark accent", 0UL, language.colors.accent.value)
            }
        }
    }
}

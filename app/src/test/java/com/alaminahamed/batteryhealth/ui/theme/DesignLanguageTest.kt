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
        // own values drifted, since both sides move together.
        assertEquals(Color(0xFF0F62FE), light.colors.accent)
        assertEquals(Color(0xFFF3F4F6), light.colors.canvas)
        assertEquals(24, light.shapes.card.value.toInt())
        assertEquals(14, light.spacing.cardOuterHorizontal.value.toInt())
        assertEquals(5, light.spacing.cardOuterVertical.value.toInt())
        assertEquals(16, light.spacing.cardInner.value.toInt())
        assertEquals(9, light.spacing.rowVertical.value.toInt())
        assertEquals(9, light.spacing.progressHeight.value.toInt())
        assertEquals(OneUiTypography, light.typography)
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

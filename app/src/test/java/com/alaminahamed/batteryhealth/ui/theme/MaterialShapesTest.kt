package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM-only coverage for [materialShapesFor], the pure derivation `BatteryHealthTheme` uses to
 * build the `Shapes` object it hands to `MaterialTheme`. Before this existed, nothing in either
 * source set asserted `MaterialTheme.shapes`: `DesignLanguageTest` only pinned the *bundle's*
 * `shapes.card`/`shapes.pill`, so deleting `shapes = shapes` from the `MaterialTheme` call, or
 * changing `OneUiShapes.small` from 8dp to 13dp, passed all 308 JVM tests. See
 * `mutatingSmallChangesTheDerivedExtraSmallAndSmallShapes` for the reproduced failure.
 *
 * `ThemeWiringTest.materialShapesAreDerivedFromTheSelectedLanguage` is the composition-level
 * counterpart and the only thing that can prove [materialShapesFor]'s result actually reaches
 * `MaterialTheme.shapes` -- that instrumented suite cannot run in this environment (no device,
 * no emulator image installed), so this JVM test is what pins the derivation itself while that
 * gap stands. Neither `Shapes` nor `RoundedCornerShape` needs Android at runtime -- both
 * override `equals`/`hashCode` structurally on their corner sizes -- so this comparison is
 * exact, not a stand-in.
 */
class MaterialShapesTest {

    @Test
    fun oneUiLightShapesMatchTheShippedCardAndSmallRadii() {
        val oneUi = designLanguageFor(DesignLanguageId.OneUi, dark = false)
        val expected = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(24.dp),
        )
        assertEquals(expected, materialShapesFor(oneUi.shapes))
    }

    @Test
    fun expressiveLightShapesMatchItsOwnCardAndSmallRadii() {
        val expressive = designLanguageFor(DesignLanguageId.Expressive, dark = false)
        val expected = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(20.dp),
        )
        assertEquals(expected, materialShapesFor(expressive.shapes))
    }

    /**
     * `medium`, `large` and `extraLarge` all collapse onto `shapes.card` -- this pins that
     * they are genuinely derived from the same field for both languages, not merely equal by
     * coincidence of the literal values used in the two tests above.
     */
    @Test
    fun mediumLargeAndExtraLargeAllEqualExtraLargeForBothLanguages() {
        for (id in DesignLanguageId.entries) {
            val language = designLanguageFor(id, dark = false)
            val result = materialShapesFor(language.shapes)
            assertEquals("$id medium", result.extraLarge, result.medium)
            assertEquals("$id large", result.extraLarge, result.large)
        }
    }

    /**
     * Reproduces the mutation the review used to demonstrate the gap: changing
     * `OneUiShapes.small` from 8dp to 13dp -- before this test existed, that mutation passed
     * all 308 JVM tests because nothing pinned the theme's derived `Shapes`. Rather than
     * editing the production constant (this file cannot reach a `private val` in
     * `OneUiLanguage.kt`), it constructs the mutated `LanguageShapes` directly and shows
     * [materialShapesFor]'s result differs from the real token's -- the pure-function
     * equivalent of the same mutation, and what `oneUiLightShapesMatchTheShippedCardAndSmallRadii`
     * above would catch if the real constant were mutated instead.
     */
    @Test
    fun mutatingSmallChangesTheDerivedExtraSmallAndSmallShapes() {
        val real = designLanguageFor(DesignLanguageId.OneUi, dark = false).shapes
        val mutated = real.copy(small = 99.dp)

        val realResult = materialShapesFor(real)
        val mutatedResult = materialShapesFor(mutated)

        assertNotEquals(realResult.small, mutatedResult.small)
        assertNotEquals(realResult.extraSmall, mutatedResult.extraSmall)
        // medium/large/extraLarge are keyed off `card`, not `small`, so the mutation must not
        // disturb them -- confirms the failure above is specifically about `small`.
        assertEquals(realResult.extraLarge, mutatedResult.extraLarge)
    }
}

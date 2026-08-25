package com.alaminahamed.batteryhealth.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThemeWiringTest {

    @get:Rule
    val compose = createComposeRule()

    /** One (language, mode) combination composed inside [materialSchemeIsDerivedFromTheSelectedLanguage]. */
    private data class Case(val id: DesignLanguageId, val dark: Boolean)

    /**
     * Restructured from the brief: `ComposeContentTestRule.setContent` throws
     * `IllegalStateException` if called more than once per test, so the per-language loop
     * cannot each call `compose.setContent`. Instead every (language, mode) case is composed
     * as a sibling in one `Column` within a single `setContent`, each wrapped in its own
     * `BatteryHealthTheme` scope, with the captured values keyed by [Case] and asserted
     * afterwards.
     *
     * Covers all four language/mode combinations, not just light: earlier this test only
     * asserted `primary`/`background` under `darkTheme = false`, so nothing here noticed when
     * `onPrimary`'s `if (darkTheme)` branch was flipped in `Theme.kt` — a real escape found by
     * review (task-3-review.md finding 2), confirmed by mutation testing 3/3 still green. Also
     * asserts `surfaceContainerHighest`, the role `Switch`'s unchecked track falls back to and
     * that review finding 1 found unset — a role set in `Theme.kt` but left unasserted here is
     * the same class of gap one layer along.
     */
    @Test
    fun materialSchemeIsDerivedFromTheSelectedLanguage() {
        // The defect this pins: colorScheme and the token bundle were independent, so a
        // Material component that was not explicitly themed rendered off-palette.
        val cases = DesignLanguageId.entries.flatMap { id -> listOf(false, true).map { Case(id, it) } }

        val schemePrimary = mutableMapOf<Case, Color>()
        val schemeBackground = mutableMapOf<Case, Color>()
        val schemeOnPrimary = mutableMapOf<Case, Color>()
        val schemeSurfaceContainerHighest = mutableMapOf<Case, Color>()
        val bundleAccent = mutableMapOf<Case, Color>()
        val bundleCanvas = mutableMapOf<Case, Color>()
        val bundleCard = mutableMapOf<Case, Color>()
        val bundleDivider = mutableMapOf<Case, Color>()

        compose.setContent {
            Column {
                for (case in cases) {
                    BatteryHealthTheme(languageId = case.id, darkTheme = case.dark) {
                        schemePrimary[case] = MaterialTheme.colorScheme.primary
                        schemeBackground[case] = MaterialTheme.colorScheme.background
                        schemeOnPrimary[case] = MaterialTheme.colorScheme.onPrimary
                        schemeSurfaceContainerHighest[case] = MaterialTheme.colorScheme.surfaceContainerHighest
                        bundleAccent[case] = LocalDesignLanguage.current.colors.accent
                        bundleCanvas[case] = LocalDesignLanguage.current.colors.canvas
                        bundleCard[case] = LocalDesignLanguage.current.colors.card
                        bundleDivider[case] = LocalDesignLanguage.current.colors.divider
                    }
                }
            }
        }
        compose.waitForIdle()

        for (case in cases) {
            assertEquals("$case primary", bundleAccent[case], schemePrimary[case])
            assertEquals("$case background", bundleCanvas[case], schemeBackground[case])
            assertEquals(
                "$case onPrimary",
                if (case.dark) bundleCanvas[case] else bundleCard[case],
                schemeOnPrimary[case],
            )
            assertEquals(
                "$case surfaceContainerHighest",
                bundleDivider[case],
                schemeSurfaceContainerHighest[case],
            )
        }
    }

    @Test
    fun materialTypographyIsTheLanguagesTypography() {
        var applied: androidx.compose.material3.Typography? = null
        compose.setContent {
            BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = false) {
                applied = MaterialTheme.typography
            }
        }
        compose.waitForIdle()
        assertEquals(ExpressiveTypography, applied)
    }

    @Test
    fun theLegacyColorsLocalStillMatchesTheBundle() {
        // 33 call sites across 11 files still read LocalOneUiColors. Until P3 rewrites the
        // screens, the shim must agree with the bundle or the two would drift apart
        // mid-migration.
        var shim: OneUiColors? = null
        var bundle: OneUiColors? = null
        compose.setContent {
            BatteryHealthTheme(languageId = DesignLanguageId.Expressive, darkTheme = true) {
                shim = LocalOneUiColors.current
                bundle = LocalDesignLanguage.current.colors
            }
        }
        compose.waitForIdle()
        assertEquals(bundle, shim)
    }
}

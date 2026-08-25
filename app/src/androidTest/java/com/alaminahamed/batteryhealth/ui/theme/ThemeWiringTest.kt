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

    /**
     * Restructured from the brief: `ComposeContentTestRule.setContent` throws
     * `IllegalStateException` if called more than once per test, so the per-language loop
     * cannot each call `compose.setContent`. Instead both languages are composed as siblings
     * in one `Column` within a single `setContent`, each wrapped in its own
     * `BatteryHealthTheme` scope, with the captured values keyed by [DesignLanguageId] and
     * asserted afterwards. The assertions and intent are unchanged from the brief.
     */
    @Test
    fun materialSchemeIsDerivedFromTheSelectedLanguage() {
        // The defect this pins: colorScheme and the token bundle were independent, so a
        // Material component that was not explicitly themed rendered off-palette.
        val schemePrimary = mutableMapOf<DesignLanguageId, Color>()
        val schemeBackground = mutableMapOf<DesignLanguageId, Color>()
        val bundleAccent = mutableMapOf<DesignLanguageId, Color>()
        val bundleCanvas = mutableMapOf<DesignLanguageId, Color>()

        compose.setContent {
            Column {
                for (id in DesignLanguageId.entries) {
                    BatteryHealthTheme(languageId = id, darkTheme = false) {
                        schemePrimary[id] = MaterialTheme.colorScheme.primary
                        schemeBackground[id] = MaterialTheme.colorScheme.background
                        bundleAccent[id] = LocalDesignLanguage.current.colors.accent
                        bundleCanvas[id] = LocalDesignLanguage.current.colors.canvas
                    }
                }
            }
        }
        compose.waitForIdle()

        for (id in DesignLanguageId.entries) {
            assertEquals("$id primary", bundleAccent[id], schemePrimary[id])
            assertEquals("$id background", bundleCanvas[id], schemeBackground[id])
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

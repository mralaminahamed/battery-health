package com.alaminahamed.batteryhealth.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class DesignLanguageChoiceTest {

    @Test
    fun autoPicksOneUiOnSamsung() {
        assertEquals(
            DesignLanguageId.OneUi,
            resolveDesignLanguageId(DesignLanguageChoice.Auto, "samsung"),
        )
    }

    @Test
    fun autoIsCaseInsensitiveAboutTheManufacturerString() {
        // Build.MANUFACTURER casing is not contractual: real devices report "samsung",
        // "Samsung" and "SAMSUNG" across vendors and Android versions.
        for (spelling in listOf("samsung", "Samsung", "SAMSUNG", "sAmSuNg")) {
            assertEquals(
                "spelling=$spelling",
                DesignLanguageId.OneUi,
                resolveDesignLanguageId(DesignLanguageChoice.Auto, spelling),
            )
        }
    }

    @Test
    fun autoPicksExpressiveEverywhereElse() {
        for (other in listOf("Google", "Xiaomi", "OnePlus", "motorola", "", "  ")) {
            assertEquals(
                "manufacturer=$other",
                DesignLanguageId.Expressive,
                resolveDesignLanguageId(DesignLanguageChoice.Auto, other),
            )
        }
    }

    @Test
    fun autoDoesNotMatchAManufacturerThatMerelyContainsSamsung() {
        // Guards against a `contains` implementation. No shipping device reports this,
        // but an exact-match rule is the claim being made and this is what pins it.
        assertEquals(
            DesignLanguageId.Expressive,
            resolveDesignLanguageId(DesignLanguageChoice.Auto, "notsamsungatall"),
        )
    }

    @Test
    fun anExplicitChoiceIgnoresTheManufacturer() {
        assertEquals(
            DesignLanguageId.OneUi,
            resolveDesignLanguageId(DesignLanguageChoice.Samsung, "Google"),
        )
        assertEquals(
            DesignLanguageId.Expressive,
            resolveDesignLanguageId(DesignLanguageChoice.Material, "samsung"),
        )
    }
}

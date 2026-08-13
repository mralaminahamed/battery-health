package com.mralaminahamed.batteryhealth

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsCorrect() {
        assertEquals("com.mralaminahamed.batteryhealth", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun flavorIsOneOfTheTwoDeclaredFlavors() {
        assertEquals(true, BuildConfig.FLAVOR in setOf("full", "play"))
    }
}

package com.alaminahamed.batteryhealth

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigTest {
    @Test
    fun applicationIdIsCorrect() {
        assertEquals("com.alaminahamed.batteryhealth", BuildConfig.APPLICATION_ID)
    }
}

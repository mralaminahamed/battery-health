package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignCapacityTableTest {

    @Test
    fun developmentDeviceIsKnown() {
        assertEquals(5000, DesignCapacityTable.lookup("SM-A356E"))
    }

    @Test
    fun regionalVariantsOfTheSameModelMatch() {
        assertEquals(5000, DesignCapacityTable.lookup("SM-A356B"))
        assertEquals(5000, DesignCapacityTable.lookup("SM-A3560"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(5000, DesignCapacityTable.lookup("sm-a356e"))
    }

    @Test
    fun unknownModelReturnsNullRatherThanAGuess() {
        assertNull(DesignCapacityTable.lookup("SM-ZZ999X"))
        assertNull(DesignCapacityTable.lookup(""))
    }

    @Test
    fun aLongerPrefixWinsOverAShorterOne() {
        // SM-S928 (S24 Ultra, 5000) must not be shadowed by SM-S92 style entries.
        assertEquals(5000, DesignCapacityTable.lookup("SM-S928B"))
        assertEquals(4000, DesignCapacityTable.lookup("SM-S921B"))
    }
}

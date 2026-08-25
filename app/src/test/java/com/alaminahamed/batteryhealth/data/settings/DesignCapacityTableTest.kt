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

    @Test
    fun galaxyS25SeriesIsKnown() {
        assertEquals(4000, DesignCapacityTable.lookup("SM-S931B"))
        assertEquals(4900, DesignCapacityTable.lookup("SM-S936B"))
        assertEquals(5000, DesignCapacityTable.lookup("SM-S938B"))
    }

    @Test
    fun galaxyS25RegionalVariantsMatch() {
        assertEquals(5000, DesignCapacityTable.lookup("SM-S938U"))
        assertEquals(5000, DesignCapacityTable.lookup("SM-S938W"))
        assertEquals(5000, DesignCapacityTable.lookup("sm-s938b"))
    }

    @Test
    fun currentASeriesSuccessorsAreKnown() {
        // A36/A56 5G, the direct successors to the A35/A55 already in the table.
        assertEquals(5000, DesignCapacityTable.lookup("SM-A366B"))
        assertEquals(5000, DesignCapacityTable.lookup("SM-A566B"))
    }

    @Test
    fun newEntriesDoNotShadowOrGetShadowedByExistingOnes() {
        // Longest-prefix-first must still hold once more S9xx/A3xx/A5xx keys share the
        // table: none of the added prefixes is a literal prefix of an existing one (or
        // vice versa), so each must resolve to its own value, not a neighbour's.
        assertEquals(4000, DesignCapacityTable.lookup("SM-S921B")) // S24, unaffected by S25
        assertEquals(4000, DesignCapacityTable.lookup("SM-S931B")) // S25, unaffected by S24
        assertEquals(5000, DesignCapacityTable.lookup("SM-A356E")) // A35, unaffected by A36
        assertEquals(5000, DesignCapacityTable.lookup("SM-A366B")) // A36, unaffected by A35
    }

    @Test
    fun theTestDeviceRemainsUnsupportedByTheTable() {
        // SM-S948B (Galaxy S26 Ultra) is deliberately not added: no confidently-sourced
        // design capacity for it was available at the time this table was written. It
        // stays null here on purpose -- the override in Settings is how this device (or
        // any other unlisted one) gets a working measured health path.
        assertNull(DesignCapacityTable.lookup("SM-S948B"))
    }
}

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
    fun anUnlistedModelStaysUnsupported() {
        // The table must keep returning null for what it does not know, rather than
        // reaching for a plausible default. That absence is what routes the user to the
        // Settings override instead of quietly measuring against a wrong design capacity.
        assertNull(DesignCapacityTable.lookup("SM-X999Z"))
        assertNull(DesignCapacityTable.lookup("Pixel 9 Pro"))
    }

    /**
     * The project's own development device. Verified two ways rather than one: published
     * specs for `SM-S948B` give 5000 mAh, and the device's own framework readings agree --
     * a charge counter of 4205 mAh at level 84% implies a full charge of about 5006 mAh,
     * with the vendor reporting state of health at 100% so full should sit at design.
     */
    @Test
    fun galaxyS26UltraIsKnown() {
        assertEquals(5000, DesignCapacityTable.lookup("SM-S948B"))
        assertEquals(5000, DesignCapacityTable.lookup("SM-S948U"))
    }
}

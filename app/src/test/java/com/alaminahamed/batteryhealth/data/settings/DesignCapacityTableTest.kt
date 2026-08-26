package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.Vendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignCapacityTableTest {

    private fun samsung(model: String, device: String = "") =
        DeviceIdentity(manufacturer = "samsung", brand = "samsung", model = model, device = device)

    private fun google(model: String, device: String) =
        DeviceIdentity(manufacturer = "Google", brand = "google", model = model, device = device)

    private fun mah(identity: DeviceIdentity) = DesignCapacityTable.lookupMah(identity)

    @Test
    fun developmentDeviceIsKnown() {
        assertEquals(5000, mah(samsung("SM-A356E")))
    }

    @Test
    fun regionalVariantsOfTheSameModelMatch() {
        assertEquals(5000, mah(samsung("SM-A356B")))
        assertEquals(5000, mah(samsung("SM-A3560")))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(5000, mah(samsung("sm-a356e")))
    }

    @Test
    fun unknownModelReturnsNullRatherThanAGuess() {
        assertNull(mah(samsung("SM-ZZ999X")))
        assertNull(mah(samsung("")))
    }

    @Test
    fun aLongerPrefixWinsOverAShorterOne() {
        assertEquals(5000, mah(samsung("SM-S928B")))
        assertEquals(4000, mah(samsung("SM-S921B")))
    }

    @Test
    fun galaxyS25SeriesIsKnown() {
        assertEquals(4000, mah(samsung("SM-S931B")))
        assertEquals(4900, mah(samsung("SM-S936B")))
        assertEquals(5000, mah(samsung("SM-S938B")))
    }

    @Test
    fun galaxyS25RegionalVariantsMatch() {
        assertEquals(5000, mah(samsung("SM-S938U")))
        assertEquals(5000, mah(samsung("SM-S938W")))
        assertEquals(5000, mah(samsung("sm-s938b")))
    }

    @Test
    fun currentASeriesSuccessorsAreKnown() {
        assertEquals(5000, mah(samsung("SM-A366B")))
        assertEquals(5000, mah(samsung("SM-A566B")))
    }

    @Test
    fun newEntriesDoNotShadowOrGetShadowedByExistingOnes() {
        assertEquals(4000, mah(samsung("SM-S921B"))) // S24, unaffected by S25
        assertEquals(4000, mah(samsung("SM-S931B"))) // S25, unaffected by S24
        assertEquals(5000, mah(samsung("SM-A356E"))) // A35, unaffected by A36
        assertEquals(5000, mah(samsung("SM-A366B"))) // A36, unaffected by A35
    }

    @Test
    fun anUnlistedModelStaysUnsupported() {
        // Absence is what routes the user to the Settings override instead of quietly
        // measuring against a wrong design capacity.
        assertNull(mah(samsung("SM-X999Z")))
    }

    /**
     * Verified two ways: published specs for `SM-S948B` give 5000 mAh, and the device's
     * own framework readings agree -- a charge counter of 4205 mAh at level 84% implies a
     * full charge of about 5006 mAh, with the vendor reporting state of health at 100% so
     * full should sit at design.
     */
    @Test
    fun galaxyS26UltraIsKnown() {
        assertEquals(5000, mah(samsung("SM-S948B")))
        assertEquals(5000, mah(samsung("SM-S948U")))
    }

    // ---- vendor scoping -------------------------------------------------------------

    /**
     * The core multi-vendor invariant. Model namespaces are unrelated across makers and
     * nothing stops them colliding, so a Samsung prefix presented under another vendor's
     * manufacturer string must not match. Without this scoping the app would measure one
     * phone's battery against another phone's design figure.
     */
    @Test
    fun aSamsungModelPrefixDoesNotMatchUnderAnotherVendor() {
        assertNull(mah(DeviceIdentity("Xiaomi", "Redmi", "SM-S938B", "")))
        assertNull(mah(DeviceIdentity("OnePlus", "OnePlus", "SM-A356E", "")))
    }

    @Test
    fun anUnknownVendorNeverMatchesAnything() {
        assertNull(mah(DeviceIdentity("Acme Phones Ltd", "Acme", "SM-S938B", "caiman")))
        assertNull(mah(DeviceIdentity.Unknown))
    }

    // ---- device-code matching -------------------------------------------------------

    /**
     * Pixel is why [DeviceMatch.DeviceCode] exists. `Build.MODEL` is the marketing name,
     * and "Pixel 9 Pro" is a literal prefix of both "Pixel 9 Pro XL" and "Pixel 9 Pro
     * Fold" -- three phones with 4700, 5060 and 4650 mAh cells respectively. Matching the
     * board name is exact and cannot spread to a neighbour.
     */
    @Test
    fun pixelMatchesOnBoardNameNotMarketingName() {
        assertEquals(4700, mah(google("Pixel 9 Pro", "caiman")))
    }

    @Test
    fun theLargerPixelsAreNotClaimedByThePixel9ProRow() {
        // komodo is the Pixel 9 Pro XL and comet the 9 Pro Fold, per Google's published
        // device list. Neither is in the table, and the 9 Pro row must not absorb them
        // just because their marketing names share its stem.
        assertNull(mah(google("Pixel 9 Pro XL", "komodo")))
        assertNull(mah(google("Pixel 9 Pro Fold", "comet")))
    }

    @Test
    fun deviceCodeMatchingIsCaseInsensitive() {
        assertEquals(4700, mah(google("Pixel 9 Pro", "CAIMAN")))
    }

    @Test
    fun aDeviceCodeFromTheWrongVendorDoesNotMatch() {
        assertNull(mah(samsung("SM-ZZ999X", device = "caiman")))
    }

    // ---- table-wide invariants ------------------------------------------------------

    /**
     * Every row must carry two independent, distinct citations. [CapacitySources] enforces
     * this at construction, so this asserts the constructor is actually the only way rows
     * are built -- a row added through some future shortcut that skipped it would fail here.
     */
    @Test
    fun everyEntryCarriesTwoDistinctSources() {
        DesignCapacityTable.all.forEach { entry ->
            assertTrue("${entry.marketingName} primary", entry.sources.primary.isNotBlank())
            assertTrue("${entry.marketingName} corroborating", entry.sources.corroborating.isNotBlank())
            assertTrue(
                "${entry.marketingName} sources must be independent",
                entry.sources.primary != entry.sources.corroborating,
            )
        }
    }

    /**
     * A transcription guard. It cannot catch a plausible-but-wrong figure -- nothing
     * mechanical can -- but it does catch a dropped or extra digit, which is the mistake
     * that actually happens when copying figures by hand.
     */
    @Test
    fun everyCapacityIsPhysicallyPlausible() {
        DesignCapacityTable.all.forEach { entry ->
            assertTrue(
                "${entry.marketingName} = ${entry.designMah}mAh",
                entry.designMah in CapacityEntry.PLAUSIBLE_MAH,
            )
        }
    }

    /** A row for [Vendor.Unknown] could never be reached, so its presence is a mistake. */
    @Test
    fun noEntryIsFiledUnderTheUnknownVendor() {
        assertEquals(emptyList<String>(), DesignCapacityTable.all.filter { it.vendor == Vendor.Unknown }.map { it.marketingName })
    }

    /**
     * Two rows in the same vendor must not share a match key. Duplicates would make the
     * result depend on declaration order, and the two rows would almost certainly disagree
     * about the capacity -- otherwise there would be no reason to have written both.
     */
    @Test
    fun noTwoEntriesInAVendorShareAMatchKey() {
        val duplicates = DesignCapacityTable.all
            .groupBy { it.vendor to it.match }
            .filterValues { it.size > 1 }
            .keys
        assertEquals(emptySet<Pair<Vendor, DeviceMatch>>(), duplicates)
    }

    /**
     * Where one vendor's model prefix is a prefix of another row's in the same vendor,
     * longest-wins must resolve it to the longer row. This drives off the table itself
     * rather than a fixed list, so it keeps holding as vendors are added.
     */
    @Test
    fun everyModelPrefixResolvesToItsOwnEntry() {
        DesignCapacityTable.all.forEach { entry ->
            val match = entry.match
            if (match !is DeviceMatch.ModelPrefix) return@forEach
            val identity = DeviceIdentity(
                manufacturer = entry.vendor.manufacturerTokens.first(),
                brand = entry.vendor.manufacturerTokens.first(),
                model = match.prefix,
                device = "",
            )
            val resolved = DesignCapacityTable.lookup(identity)
            assertNotNull("${entry.marketingName} resolves to nothing", resolved)
            assertEquals(
                "${entry.marketingName} (${match.prefix}) resolved to ${resolved?.marketingName}",
                entry.designMah,
                resolved?.designMah,
            )
        }
    }

    /**
     * Likewise every device-code row must resolve to itself. Together with the prefix
     * counterpart above, this proves no row in the table is unreachable -- an entry
     * nobody can ever match is indistinguishable from a missing one, but looks like
     * coverage when reading the file.
     */
    @Test
    fun everyDeviceCodeResolvesToItsOwnEntry() {
        DesignCapacityTable.all.forEach { entry ->
            val match = entry.match
            if (match !is DeviceMatch.DeviceCode) return@forEach
            val identity = DeviceIdentity(
                manufacturer = entry.vendor.manufacturerTokens.first(),
                brand = entry.vendor.manufacturerTokens.first(),
                model = "",
                device = match.code,
            )
            assertEquals(entry.designMah, DesignCapacityTable.lookup(identity)?.designMah)
        }
    }
}

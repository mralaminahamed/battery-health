package com.alaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Against the real 525KB `dumpsys batterystats --checkin` capture this parser was built
 * for -- the same device (`SM-A356E`) [DumpsysBatteryParserTest] uses its own fixture
 * from. All hand-verified counts and values below were independently confirmed against
 * the raw fixture with `grep`/`awk` before this parser existed, not fitted to whatever
 * the implementation happened to produce.
 */
class BatteryStatsCheckinParserTest {

    private fun loadFixture(): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("batterystats-checkin-sm-a356e.csv")
            ?: error("fixture not found on the test classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun findsEveryPerUidPowerRowInTheRealFixture() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // 90 "pwi,uid" rows in the raw fixture (grep -c), none dropped, none invented.
        assertEquals(90, parsed.uidPowerMah.size)
    }

    @Test
    fun findsEveryDistinctUidInTheUidDictionary() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // 283 distinct uids across 489 "...,uid,<uid>,<pkg>" rows (many uids, especially
        // 1000, own several packages each).
        assertEquals(283, parsed.uidPackages.size)
    }

    @Test
    fun theShellUidIsTheTopConsumerAndIsNamedComAndroidShell() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // uid 2000 -- adb/USB-debugging shell -- tops this fixture at 422 mAh purely
        // because of the testing done on this device; see AppPowerAggregator/UidKind for
        // why that is not presented as if it were an app.
        assertEquals(422.0, parsed.uidPowerMah.getValue(2000), 0.0001)
        assertEquals(listOf("com.android.shell"), parsed.uidPackages.getValue(2000))
    }

    @Test
    fun aRealUserFacingAppResolvesToItsOwnPackage() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // uid 10106 -- Samsung's own camera app -- second-highest at 15.6 mAh.
        assertEquals(15.6, parsed.uidPowerMah.getValue(10106), 0.0001)
        assertEquals(listOf("com.sec.android.app.camera"), parsed.uidPackages.getValue(10106))
    }

    @Test
    fun aSharedUidOwnsEveryPackageItWasEverGivenNotJustTheLast() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // uid 1000 (android.uid.system) -- 82 packages share it on this fixture, and it
        // drew 6.23 mAh. Accumulating into a list, not overwriting on each new row, is
        // what this test guards.
        assertEquals(6.23, parsed.uidPowerMah.getValue(1000), 0.0001)
        assertEquals(82, parsed.uidPackages.getValue(1000).size)
        assertTrue(parsed.uidPackages.getValue(1000).contains("com.android.settings"))
    }

    @Test
    fun aUidCanHavePowerWithNoKnownPackageAtAll() {
        val parsed = BatteryStatsCheckinParser.parse(loadFixture())

        // uid 0 (root) drew 1.66 mAh but the fixture's own uid dictionary never names a
        // package for it -- confirmed by direct inspection before this parser existed.
        // uidPackages must not invent an entry to paper over the gap.
        assertEquals(1.66, parsed.uidPowerMah.getValue(0), 0.0001)
        assertTrue(!parsed.uidPackages.containsKey(0))
    }

    @Test
    fun theSystemWideComponentBreakdownIsNotMistakenForPerUidPower() {
        // Same shape as the real fixture's own component rows: field 5 names a hardware
        // component ("cpu"), not "uid", and field 2 is "0" the way every real component
        // row's is. If this were mistaken for per-uid power, uid 0 would read the
        // component's own mAh value here instead of staying entirely absent.
        val dump = "9,0,l,pwi,cpu,17.5,0,0,0\n"

        val parsed = BatteryStatsCheckinParser.parse(dump)

        assertTrue(parsed.uidPowerMah.isEmpty())
    }

    @Test
    fun aMalformedMahFieldDropsOnlyThatRowNotTheWholeParse() {
        val dump = """
            9,10106,l,pwi,uid,not-a-number,1,0,0
            9,1000,l,pwi,uid,6.23,1,0,0
        """.trimIndent()

        val parsed = BatteryStatsCheckinParser.parse(dump)

        assertNull(parsed.uidPowerMah[10106])
        assertEquals(6.23, parsed.uidPowerMah.getValue(1000), 0.0001)
    }

    @Test
    fun aMalformedUidFieldDropsOnlyThatRow() {
        val dump = """
            9,not-a-uid,l,pwi,uid,15.6,1,0,0
            9,1000,l,pwi,uid,6.23,1,0,0
        """.trimIndent()

        val parsed = BatteryStatsCheckinParser.parse(dump)

        assertEquals(1, parsed.uidPowerMah.size)
        assertEquals(6.23, parsed.uidPowerMah.getValue(1000), 0.0001)
    }

    @Test
    fun aBlankPackageNameFieldIsSkippedRatherThanRecordedAsAnEmptyIdentifier() {
        val dump = "9,0,i,uid,1000,\n"

        val parsed = BatteryStatsCheckinParser.parse(dump)

        assertTrue(!parsed.uidPackages.containsKey(1000))
    }

    @Test
    fun aTooShortRowIsSkippedRatherThanThrowing() {
        val dump = "9,0,i,uid,1000\n"

        val parsed = BatteryStatsCheckinParser.parse(dump)

        assertTrue(parsed.uidPackages.isEmpty())
    }

    @Test
    fun emptyDumpProducesEmptyMapsRatherThanThrowing() {
        val parsed = BatteryStatsCheckinParser.parse("")

        assertTrue(parsed.uidPowerMah.isEmpty())
        assertTrue(parsed.uidPackages.isEmpty())
    }

    @Test
    fun unrelatedRowTypesAreIgnoredRatherThanMisparsed() {
        // The dump's own first line -- a "vers" row -- has fields[3] == "vers", matching
        // neither "pwi" nor "uid".
        val parsed = BatteryStatsCheckinParser.parse(
            "9,0,i,vers,36,1179864,BP4A.251205.006,BP4A.251205.006\n"
        )

        assertTrue(parsed.uidPowerMah.isEmpty())
        assertTrue(parsed.uidPackages.isEmpty())
    }

    /**
     * Not a rigorous complexity proof, but a real regression guard: an accidental
     * O(n²) rewrite (e.g. re-scanning the whole dump per row, or materialising and
     * re-searching a List for every lookup) would make this take vastly longer than a
     * single linear pass over 525KB does. Generous bound for CI/host scheduling jitter,
     * nowhere near what a quadratic pass over ~5800 lines would cost.
     */
    @Test
    fun parsesTheRealFixtureWellWithinALinearTimeBudget() {
        val dump = loadFixture()
        val startedAtMs = System.currentTimeMillis()

        BatteryStatsCheckinParser.parse(dump)

        val elapsedMs = System.currentTimeMillis() - startedAtMs
        assertTrue("expected a fast single pass, took ${elapsedMs}ms", elapsedMs < 2_000)
    }
}

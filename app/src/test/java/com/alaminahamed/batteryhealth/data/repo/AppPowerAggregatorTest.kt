package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.privileged.ParsedBatteryStats
import com.alaminahamed.batteryhealth.domain.UidKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPowerAggregatorTest {

    @Test
    fun sortsDescendingByPowerRegardlessOfMapInsertionOrder() {
        val stats = ParsedBatteryStats(
            uidPowerMah = linkedMapOf(10106 to 15.6, 2000 to 422.0, 0 to 1.66),
            uidPackages = emptyMap(),
        )

        val entries = AppPowerAggregator.aggregate(stats)

        assertEquals(listOf(2000, 10106, 0), entries.map { it.uid })
    }

    @Test
    fun sharePctIsComputedAgainstTheSumOfEveryUidNotJustAppUids() {
        // Hand-computed total: 422 + 15.6 + 6.23 + 1.66 = 445.49. Matches the task's own
        // real-fixture framing of the shell uid at "roughly 94%" of everything.
        val stats = ParsedBatteryStats(
            uidPowerMah = linkedMapOf(2000 to 422.0, 10106 to 15.6, 1000 to 6.23, 0 to 1.66),
            uidPackages = emptyMap(),
        )

        val entries = AppPowerAggregator.aggregate(stats)
        val shell = entries.first { it.uid == 2000 }

        assertEquals(94.7271543693461, shell.sharePct, 0.0001)
    }

    @Test
    fun classifiesTheShellUidSeparatelyFromSystemAndApps() {
        val stats = ParsedBatteryStats(
            uidPowerMah = linkedMapOf(2000 to 422.0, 1000 to 6.23, 10106 to 15.6),
            uidPackages = emptyMap(),
        )

        val entries = AppPowerAggregator.aggregate(stats)

        assertEquals(UidKind.Shell, entries.first { it.uid == 2000 }.kind)
        assertEquals(UidKind.System, entries.first { it.uid == 1000 }.kind)
        assertEquals(UidKind.App, entries.first { it.uid == 10106 }.kind)
    }

    @Test
    fun uidKindBoundariesAreExactAtTheirEdges() {
        assertEquals(UidKind.System, UidKind.of(9_999))
        assertEquals(UidKind.App, UidKind.of(10_000))
        assertEquals(UidKind.Shell, UidKind.of(2_000))
        // 2000 is caught by the shell check before the system range would otherwise claim
        // it -- pin the ordering, not just the two boundaries in isolation.
        assertEquals(UidKind.System, UidKind.of(1_999))
        assertEquals(UidKind.System, UidKind.of(2_001))
    }

    @Test
    fun carriesEveryPackageAUidOwnsIntoItsEntry() {
        val stats = ParsedBatteryStats(
            uidPowerMah = linkedMapOf(1000 to 6.23),
            uidPackages = mapOf(1000 to listOf("a", "b", "c")),
        )

        val entries = AppPowerAggregator.aggregate(stats)

        assertEquals(listOf("a", "b", "c"), entries.single().packages)
    }

    @Test
    fun aUidWithPowerButNoPackageMappingGetsAnEmptyListNotACrash() {
        val stats = ParsedBatteryStats(
            uidPowerMah = linkedMapOf(0 to 1.66),
            uidPackages = emptyMap(),
        )

        val entries = AppPowerAggregator.aggregate(stats)

        assertTrue(entries.single().packages.isEmpty())
    }

    @Test
    fun emptyPowerMapProducesAnEmptyListRatherThanDividingByZero() {
        val stats = ParsedBatteryStats(uidPowerMah = emptyMap(), uidPackages = emptyMap())

        val entries = AppPowerAggregator.aggregate(stats)

        assertTrue(entries.isEmpty())
    }
}

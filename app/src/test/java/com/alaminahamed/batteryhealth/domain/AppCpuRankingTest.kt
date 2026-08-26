package com.alaminahamed.batteryhealth.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCpuRankingTest {

    private fun entry(
        uid: Int,
        user: Long,
        system: Long = 0L,
        bucket: AppBucket = AppBucket.of(UidKind.of(uid), hasLauncherEntry = true),
    ) = AppCpuTime(uid, user, system, UidKind.of(uid), emptyList(), bucket)

    @Test
    fun totalIsUserPlusSystem() {
        assertEquals(300L, entry(10001, user = 200, system = 100).totalCpuMs)
    }

    @Test
    fun rowsAreOrderedByCpuTimeHighestFirst() {
        val ranked = AppCpuRanking.ranked(
            listOf(entry(10001, 100), entry(10002, 900), entry(10003, 500)),
        )
        assertEquals(listOf(10002, 10003, 10001), ranked.map { it.entry.uid })
    }

    /**
     * Android reports a row for every uid it has ever seen, most having done nothing
     * measurable. Listing them would bury the few that matter and imply the app knows
     * something about each.
     */
    @Test
    fun uidsWithNoCpuTimeAreDropped() {
        val ranked = AppCpuRanking.ranked(listOf(entry(10001, 0), entry(10002, 0, 0), entry(10003, 5)))
        assertEquals(listOf(10003), ranked.map { it.entry.uid })
    }

    @Test
    fun sharesAreOfTheVisibleTotalAndSumToOneHundred() {
        val ranked = AppCpuRanking.ranked(listOf(entry(10001, 250), entry(10002, 750)))
        assertEquals(75.0, ranked[0].sharePct, 0.001)
        assertEquals(25.0, ranked[1].sharePct, 0.001)
        assertEquals(100.0, ranked.sumOf { it.sharePct }, 0.001)
    }

    /**
     * A small but real contributor must not be rounded away into nothing. Reporting 0%
     * for a row that genuinely used CPU would make the list contradict itself.
     */
    @Test
    fun aSmallShareIsNotRoundedToZero() {
        val ranked = AppCpuRanking.ranked(listOf(entry(10001, 4), entry(10002, 996)))
        assertTrue("expected a non-zero share, got ${ranked[1].sharePct}", ranked[1].sharePct > 0.0)
        assertEquals(0.4, ranked[1].sharePct, 0.001)
    }

    @Test
    fun nothingMeasurableYieldsNoRowsRatherThanZeroes() {
        assertEquals(emptyList<RankedCpu>(), AppCpuRanking.ranked(emptyList()))
        assertEquals(emptyList<RankedCpu>(), AppCpuRanking.ranked(listOf(entry(10001, 0))))
    }

    /** Kind comes from the uid alone, the same rule the power rows already use. */
    @Test
    fun kindIsDerivedFromTheUidNumber() {
        assertEquals(UidKind.System, entry(1000, 5).kind)
        assertEquals(UidKind.Shell, entry(2000, 5).kind)
        assertEquals(UidKind.App, entry(10049, 5).kind)
    }

    /**
     * System time and user time are kept apart because they answer different questions --
     * a row that is almost all kernel work means something different from one that is
     * almost all the app's own code.
     */
    @Test
    fun bothHalvesSurviveRanking() {
        val ranked = AppCpuRanking.ranked(listOf(entry(10049, user = 447_180, system = 205_099)))
        assertEquals(447_180L, ranked.single().entry.userCpuMs)
        assertEquals(205_099L, ranked.single().entry.systemCpuMs)
    }

    /**
     * The bucket rides along through ranking untouched. Ranking orders and shares; it has
     * no business reclassifying what a row is.
     */
    @Test
    fun rankingPreservesEachRowsBucket() {
        val rows = listOf(
            entry(10001, 100, bucket = AppBucket.Hidden),
            entry(1000, 900, bucket = AppBucket.System),
            entry(10002, 500, bucket = AppBucket.Visible),
        )
        val ranked = AppCpuRanking.ranked(rows).associate { it.entry.uid to it.entry.bucket }
        assertEquals(AppBucket.Hidden, ranked[10001])
        assertEquals(AppBucket.System, ranked[1000])
        assertEquals(AppBucket.Visible, ranked[10002])
    }
}

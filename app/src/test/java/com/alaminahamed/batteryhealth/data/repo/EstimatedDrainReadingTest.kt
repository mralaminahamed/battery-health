package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.apps.AppLabel
import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [EstimatedDrainReading.from]'s absence classification -- the four-way decision the
 * Apps screen's estimate section renders directly, with no further branching of its own.
 * Every test here is a case a wrong implementation would actually fail, not a restatement
 * of the code: see each test's own comment for the mutation it catches.
 */
class EstimatedDrainReadingTest {

    /** Resolves every package to the same fixed [AppLabel], recording what it was asked
     * to resolve so a test can confirm the real package name reached it. */
    private class FakeLabelResolver(private val label: AppLabel = AppLabel.Unknown) : AppLabelResolver {
        val requests = mutableListOf<List<String>>()
        override fun resolve(packageNames: List<String>): AppLabel {
            requests += packageNames
            return label
        }
    }

    private fun entry(
        packageName: String = "com.example.app",
        foregroundMs: Long = 600_000L,
        estimatedMah: Double = 50.0,
        sharePct: Double = 25.0,
    ) = EstimatedAppDrainEntry(packageName, foregroundMs, estimatedMah, sharePct)

    @Test
    fun accessNotHeldWithEmptyEntriesIsStillNeedsUsageAccessNotNotYetMeasured() {
        // The ordinary case for a user who has never granted the permission: nothing
        // recorded, and access not held either. This is the one input that actually tells
        // the two possible check orderings apart -- with a non-empty entries list, both
        // "check access first" and "check emptiness first" reach the access check anyway
        // (the emptiness branch never fires), so a fixture with real entries would pass
        // regardless of ordering and prove nothing. Only an EMPTY entries list makes the
        // emptiness-first ordering return NotYetMeasured instead of NeedsUsageAccess --
        // telling a user who has never granted the permission that there is something to
        // "wait for", which is wrong advice: there is nothing pending, they simply have
        // not granted it. Reordering the two checks in EstimatedDrainReading.from is
        // exactly the mutation this test exists to catch.
        val result = EstimateWindow.Result(
            entries = emptyList(),
            totalDischargeMah = null,
            windowStartMs = 0L,
            windowEndMs = 900_000L,
        )
        val reading = EstimatedDrainReading.from(
            usageAccessGranted = false,
            result = result,
            labelResolver = FakeLabelResolver(),
        )
        assertEquals(Reading.NeedsUsageAccess, reading)
    }

    @Test
    fun accessNotHeldWinsEvenOverAContrivedNonEmptyResult() {
        // EstimateWindow.compute never actually produces a non-empty entries list when
        // usage access is not held (it never queries foreground time in that case), but
        // this function does not lean on that upstream guarantee -- it checks
        // usageAccessGranted itself, unconditionally, rather than trusting the caller to
        // have never passed this combination.
        val result = EstimateWindow.Result(
            entries = listOf(entry()),
            totalDischargeMah = 200.0,
            windowStartMs = 0L,
            windowEndMs = 900_000L,
        )
        val reading = EstimatedDrainReading.from(
            usageAccessGranted = false,
            result = result,
            labelResolver = FakeLabelResolver(),
        )
        assertEquals(Reading.NeedsUsageAccess, reading)
    }

    @Test
    fun accessHeldWithEmptyEntriesIsNotYetMeasured() {
        val result = EstimateWindow.Result(
            entries = emptyList(),
            totalDischargeMah = null,
            windowStartMs = 0L,
            windowEndMs = 900_000L,
        )
        val reading = EstimatedDrainReading.from(
            usageAccessGranted = true,
            result = result,
            labelResolver = FakeLabelResolver(),
        )
        assertEquals(Reading.NotYetMeasured, reading)
    }

    @Test
    fun accessHeldWithNonEmptyEntriesProducesAnAvailableInferredReadingCarryingTheWindow() {
        val result = EstimateWindow.Result(
            entries = listOf(
                entry(packageName = "com.instagram.android", estimatedMah = 80.0, sharePct = 60.0),
                entry(packageName = "com.whatsapp", estimatedMah = 20.0, sharePct = 40.0),
            ),
            totalDischargeMah = 100.0,
            windowStartMs = 1_000L,
            windowEndMs = 4_600_000L,
        )
        val resolver = FakeLabelResolver(AppLabel.Resolved("Instagram", icon = null))

        val reading = EstimatedDrainReading.from(
            usageAccessGranted = true,
            result = result,
            labelResolver = resolver,
        )

        check(reading is Reading.Available)
        assertEquals(Source.Inferred, reading.source)
        assertEquals(100.0, reading.value.totalMah, 0.001)
        assertEquals(1_000L, reading.value.windowStartMs)
        assertEquals(4_600_000L, reading.value.windowEndMs)
        assertEquals(2, reading.value.rows.size)
        assertEquals("com.instagram.android", reading.value.rows[0].packageName)
        assertEquals(80.0, reading.value.rows[0].estimatedMah, 0.001)
        // Every entry's own package name reached the resolver -- proves rows are not
        // built from some other list (e.g. always resolving the first entry's package for
        // every row, which would still compile and still produce two rows).
        assertEquals(listOf(listOf("com.instagram.android"), listOf("com.whatsapp")), resolver.requests)
    }

    @Test
    fun entriesWithNoTotalDischargeIsAnInvariantViolationThatThrowsRatherThanSilentlyZeroing() {
        // EstimateWindow.compute never actually produces this combination -- it only adds
        // to entries inside the branch where totalDischargeMah is known non-null -- but if
        // that invariant were ever broken upstream, silently defaulting to 0.0 here would
        // render a false "~0.00 mAh" that looks like a real, if tiny, measurement rather
        // than surfacing the bug. This pins that the guard is a hard failure, not a
        // fallback default.
        val result = EstimateWindow.Result(
            entries = listOf(entry()),
            totalDischargeMah = null,
            windowStartMs = 0L,
            windowEndMs = 900_000L,
        )
        assertThrows(IllegalStateException::class.java) {
            EstimatedDrainReading.from(
                usageAccessGranted = true,
                result = result,
                labelResolver = FakeLabelResolver(),
            )
        }
    }

    @Test
    fun labelResolutionGoesThroughTheSharedAppLabelResolverNotAnInventedName() {
        val result = EstimateWindow.Result(
            entries = listOf(entry(packageName = "com.example.unresolved")),
            totalDischargeMah = 10.0,
            windowStartMs = 0L,
            windowEndMs = 60_000L,
        )
        val resolver = FakeLabelResolver(AppLabel.PackageNameOnly("com.example.unresolved"))

        val reading = EstimatedDrainReading.from(true, result, resolver)

        check(reading is Reading.Available)
        assertTrue(reading.value.rows.single().label is AppLabel.PackageNameOnly)
    }
}

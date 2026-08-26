package com.alaminahamed.batteryhealth.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only coverage for [isNearestExpected], the pure comparison
 * `PrimitiveLanguageTest.assertNearestExpected` is built on. That instrumented test needs a
 * device this session doesn't have (task-4-report.md's device is absent from the USB tree, and
 * no emulator image is installed); this test needs neither, since the predicate is plain
 * arithmetic -- and per this round's notes it carries most of the risk in the assertion-shape
 * change, since a fixed tolerance could not reliably discriminate the four tokens whose two
 * languages differ by only 1dp (`cardOuterVertical`, `sectionHeaderBottom`, `unitOffsetStart`,
 * `unitOffsetBottom`).
 *
 * Values below use `cardOuterVertical`'s real tokens (One UI 5dp, Expressive 4dp) so the cases
 * read against an actual 1dp gap rather than an arbitrary one.
 *
 * What this class proves: the *decision rule* -- "closer to this language's token than to the
 * other's" -- is correct for exact matches, for the ambiguous midpoint, and for ~0.2dp of
 * measurement noise (the magnitude task-4-report.md's on-device run observed) landing on either
 * side of the true value, including the harder direction where noise pushes toward the wrong
 * token. What it does NOT prove: that a real device's *measured* value is anywhere near either
 * token in the first place. [isNearestExpected] only ever compares the two numbers it is given;
 * it would return `true` just as confidently for a coincidentally-closer wrong number as for a
 * correct one, if the actual measurement pipeline were broken upstream. Only the instrumented
 * test, run on a device, establishes that the numbers fed into this predicate are the ones a
 * real Compose layout pass actually produces.
 */
class NearestExpectedTest {

    // cardOuterVertical: One UI 5dp, Expressive 4dp -- one of the four 1dp-gap tokens this
    // predicate exists for (OneUiLanguage.kt / ExpressiveLanguage.kt).
    private val oneUi = 5f
    private val expressive = 4f

    @Test
    fun measuredExactlyAtThisLanguagePasses() {
        assertTrue(isNearestExpected(measuredDp = oneUi, thisExpectedDp = oneUi, otherExpectedDp = expressive))
    }

    @Test
    fun measuredExactlyAtTheOtherLanguageFails() {
        assertFalse(isNearestExpected(measuredDp = expressive, thisExpectedDp = oneUi, otherExpectedDp = expressive))
    }

    /**
     * At the exact midpoint the measured value is equidistant from both tokens -- not evidence
     * either way of which language's value was actually read. [isNearestExpected] uses a
     * strict `<`, so a tie resolves to `false` (not "nearest"). That is the safe direction: an
     * assertion whose whole purpose is discriminating which language's token was read should
     * never treat "I can't tell" as a pass, which a `<=` would do here.
     */
    @Test
    fun measuredAtTheMidpointFails() {
        val midpoint = (oneUi + expressive) / 2f // 4.5dp -- 0.5dp from both
        assertFalse(isNearestExpected(measuredDp = midpoint, thisExpectedDp = oneUi, otherExpectedDp = expressive))
    }

    /**
     * ~0.2dp is the magnitude of real device noise task-4-report.md measured. Offsetting away
     * from the other language's value is the easy direction (it only widens the existing
     * margin) -- included for completeness before the harder direction below.
     */
    @Test
    fun realisticNoiseAwayFromTheOtherLanguageStillPasses() {
        val measured = oneUi + 0.2f // 5.2dp: 0.2dp from 5dp, 1.2dp from 4dp
        assertTrue(isNearestExpected(measuredDp = measured, thisExpectedDp = oneUi, otherExpectedDp = expressive))
    }

    /**
     * The case that actually exercises the margin this assertion shape depends on: noise
     * pushes the measurement *toward* the other language's token. 0.2dp of a 1dp gap still
     * leaves 0.3dp to spare (measured 4.8dp is 0.2dp from 5dp and 0.8dp from 4dp), so this must
     * still pass -- if it didn't, the ~0.1-0.2dp of noise observed on a real device would make
     * this assertion flake, which is exactly the failure mode this round's change was meant to
     * fix.
     */
    @Test
    fun realisticNoiseTowardTheOtherLanguageStillPasses() {
        val measured = oneUi - 0.2f // 4.8dp
        assertTrue(isNearestExpected(measuredDp = measured, thisExpectedDp = oneUi, otherExpectedDp = expressive))
    }

    /**
     * Same two properties as above, called the way `PrimitiveLanguageTest` calls this helper
     * for Expressive assertions -- `thisExpectedDp`/`otherExpectedDp` swapped relative to the
     * One UI cases -- to confirm the predicate has no directional bias baked in.
     */
    @Test
    fun realisticNoiseTowardTheOtherLanguageStillPassesWithRolesSwapped() {
        val measured = expressive + 0.2f // 4.2dp: noise toward One UI's 5dp
        assertTrue(isNearestExpected(measuredDp = measured, thisExpectedDp = expressive, otherExpectedDp = oneUi))
    }

    @Test
    fun exactOtherLanguageValueFailsWithRolesSwapped() {
        assertFalse(isNearestExpected(measuredDp = oneUi, thisExpectedDp = expressive, otherExpectedDp = oneUi))
    }
}

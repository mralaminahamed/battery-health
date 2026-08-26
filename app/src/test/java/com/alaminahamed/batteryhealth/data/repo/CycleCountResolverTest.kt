package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class CycleCountResolverTest {

    @Test
    fun privilegedValueWinsOverAFrameworkValueWhenBothArePresent() {
        // The precedence the task cares about most: Samsung's own accumulated figure is
        // the more direct measurement, so it wins outright even though the broadcast also
        // has a real, non-zero number of its own.
        val reading = CycleCountResolver.resolve(
            privilegedCycles = 619,
            dumpAvailable = true,
            broadcastCycles = 142,
        )

        assertEquals(Reading.Available(619, Source.Privileged), reading)
    }

    @Test
    fun aConnectedDumpMissingTheFieldFallsBackToARealFrameworkReading() {
        // Connected, a real dump was obtained, but this specific field's regex found
        // nothing in it (or the plausibility guard rejected it) -- dumpAvailable is true,
        // yet the broadcast still has a genuine reading. The general "prefer privileged
        // when available" rule must not translate into "discard a real framework number
        // just because the privileged tier came back empty for this one field."
        val reading = CycleCountResolver.resolve(
            privilegedCycles = null,
            dumpAvailable = true,
            broadcastCycles = 142,
        )

        assertEquals(Reading.Available(142, Source.Framework), reading)
    }

    @Test
    fun notConnectedAtAllStillFallsBackToARealFrameworkReading() {
        // Never connected (dumpAvailable false), but the broadcast reading is still real.
        // Not being able to try the privileged tier is not a reason to hide a number the
        // framework already reported.
        val reading = CycleCountResolver.resolve(
            privilegedCycles = null,
            dumpAvailable = false,
            broadcastCycles = 142,
        )

        assertEquals(Reading.Available(142, Source.Framework), reading)
    }

    /**
     * The distinction the task explicitly calls out: connected-but-absent and
     * never-connected must not collapse into the same answer once neither tier has a real
     * number. A dump was obtained here, and full shell privilege still did not turn up the
     * field, so reconnecting the privileged tier again cannot help -- Unsupported, not
     * NeedsPrivilegedAccess.
     */
    @Test
    fun connectedWithNoDataAnywhereIsUnsupportedNotNeedsPrivilegedAccess() {
        val reading = CycleCountResolver.resolve(
            privilegedCycles = null,
            dumpAvailable = true,
            broadcastCycles = null,
        )

        assertEquals(Reading.Unsupported, reading)
    }

    /**
     * The other half of the same distinction, and the one this whole feature turns the
     * field's previous behaviour on: before Samsung's own figure was discovered, this
     * exact input (the privileged tier never connected, broadcast reporting the untracked
     * sentinel) was correctly Unsupported -- the app had no way to ever produce a number.
     * It is not true anymore: connecting the privileged tier might now supply it, so this
     * must be NeedsPrivilegedAccess. Reporting Unsupported here would repeat the defect
     * fixed two tasks before this one, where the app told users a value could never be
     * supplied when it now can.
     */
    @Test
    fun neverConnectedWithNoFrameworkValueIsNeedsPrivilegedAccessNotUnsupported() {
        val reading = CycleCountResolver.resolve(
            privilegedCycles = null,
            dumpAvailable = false,
            broadcastCycles = null,
        )

        assertEquals(Reading.NeedsPrivilegedAccess, reading)
    }

    // ---- this app's own count --------------------------------------------------------

    /**
     * The reading that took the last privileged-only value off the critical path.
     * Samsung's cycle count has no BATTERY_PROPERTY id and `EXTRA_CYCLE_COUNT` reads 0 on
     * the hardware this was verified against, so before this an ordinary user saw "needs
     * privileged access" on that row forever.
     */
    @Test
    fun thisAppsOwnCountIsUsedWhenNoVendorFigureExists() {
        val measured = Reading.Available(3, Source.Measured)
        assertEquals(
            measured,
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = measured,
            ),
        )
    }

    /**
     * Never blended with a vendor figure. Samsung counts from the day the battery was
     * made; this counts from the day the app was installed. On a year-old phone those
     * differ enormously, so the vendor's wins outright wherever it exists.
     */
    @Test
    fun aVendorFigureAlwaysOutranksOurOwn() {
        val measured = Reading.Available(3, Source.Measured)
        assertEquals(
            Reading.Available(412, Source.Privileged),
            CycleCountResolver.resolve(412, dumpAvailable = true, broadcastCycles = null, measured = measured),
        )
        assertEquals(
            Reading.Available(97, Source.Framework),
            CycleCountResolver.resolve(null, dumpAvailable = false, broadcastCycles = 97, measured = measured),
        )
    }

    /**
     * "A count is coming once you charge a few times" is true and actionable. Falling
     * through to NeedsPrivilegedAccess would instead tell that user to go and set up adb,
     * which is advice they do not need.
     */
    @Test
    fun stillAccumulatingBeatsTellingTheUserToSetUpAdb() {
        assertEquals(
            Reading.NotYetMeasured,
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = Reading.NotYetMeasured,
            ),
        )
    }

    /**
     * With no design capacity our count is Unsupported, and the old behaviour must still
     * apply: the privileged tier might genuinely help, so say so.
     */
    @Test
    fun withNothingMeasurableTheOriginalAbsenceRuleStillHolds() {
        assertEquals(
            Reading.NeedsPrivilegedAccess,
            CycleCountResolver.resolve(null, dumpAvailable = false, broadcastCycles = null, measured = Reading.Unsupported),
        )
        assertEquals(
            Reading.Unsupported,
            CycleCountResolver.resolve(null, dumpAvailable = true, broadcastCycles = null, measured = Reading.Unsupported),
        )
    }

    // ---- the user-supplied baseline ----------------------------------------------------

    /**
     * The feature that makes this app's own count usable on a phone that is not new.
     *
     * It can only count charge it watched go in, so on an old battery it starts at zero.
     * Samsung shows the real figure to the user; adding what they read to what this app
     * has since counted gives an accurate total without inventing anything.
     */
    @Test
    fun theUsersBaselineIsAddedToWhatThisAppHasCounted() {
        assertEquals(
            Reading.Available(10, Source.Measured),
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = Reading.Available(3, Source.Measured),
                baselineCycles = 7,
            ),
        )
    }

    /**
     * Their figure is real and already useful before this app has counted a full cycle.
     * Showing "Measuring" over the top of it would discard something true they entered.
     */
    @Test
    fun aBaselineAloneIsShownBeforeAnythingIsMeasured() {
        assertEquals(
            Reading.Available(7, Source.Measured),
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = Reading.NotYetMeasured,
                baselineCycles = 7,
            ),
        )
    }

    /**
     * Vendor figures are already lifetime totals. Adding a baseline to one would
     * double-count the very history the baseline exists to stand in for.
     */
    @Test
    fun aBaselineIsNeverAddedToAVendorFigure() {
        assertEquals(
            Reading.Available(412, Source.Privileged),
            CycleCountResolver.resolve(412, dumpAvailable = true, broadcastCycles = null, baselineCycles = 7),
        )
        assertEquals(
            Reading.Available(97, Source.Framework),
            CycleCountResolver.resolve(null, dumpAvailable = false, broadcastCycles = 97, baselineCycles = 7),
        )
    }

    /**
     * Zero is a real baseline, not an absent one -- a user reporting it is saying this
     * app's count and their phone's now agree, which must not be discarded as "unset".
     */
    @Test
    fun aZeroBaselineIsHonouredRatherThanTreatedAsUnset() {
        assertEquals(
            Reading.Available(0, Source.Measured),
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = Reading.NotYetMeasured,
                baselineCycles = 0,
            ),
        )
    }

    @Test
    fun withNoBaselineNothingChanges() {
        assertEquals(
            Reading.Available(3, Source.Measured),
            CycleCountResolver.resolve(
                privilegedCycles = null,
                dumpAvailable = false,
                broadcastCycles = null,
                measured = Reading.Available(3, Source.Measured),
                baselineCycles = null,
            ),
        )
    }
}

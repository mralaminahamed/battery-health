package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
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
}

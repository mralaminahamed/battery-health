package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class CycleCountResolverTest {

    @Test
    fun aFrameworkValueIsUsedWhenTheBroadcastReportsOne() {
        val reading = CycleCountResolver.resolve(broadcastCycles = 142)

        assertEquals(Reading.Available(142, Source.Framework), reading)
    }

    @Test
    fun aFrameworkFigureOutranksThisAppsOwnCount() {
        // The broadcast's own figure wins outright even though this app has counted a
        // real, non-zero number of its own -- Samsung's accumulated count is the more
        // direct measurement of this device's actual history.
        val measured = Reading.Available(3, Source.Measured)
        assertEquals(
            Reading.Available(97, Source.Framework),
            CycleCountResolver.resolve(broadcastCycles = 97, measured = measured),
        )
    }

    /**
     * The reading that took the shell tier off the critical path for this field. Before
     * this app could count its own charge sessions, a device whose broadcast never
     * reported a cycle count (as this one's does not) had no route to a number at all.
     */
    @Test
    fun thisAppsOwnCountIsUsedWhenTheBroadcastReportsNone() {
        val measured = Reading.Available(3, Source.Measured)
        assertEquals(
            measured,
            CycleCountResolver.resolve(broadcastCycles = null, measured = measured),
        )
    }

    /**
     * "A count is coming once you charge a few times" is true and actionable, and this app
     * has nothing further to offer beyond waiting for it -- there is no permission left to
     * ask for that would produce it any sooner.
     */
    @Test
    fun stillAccumulatingIsReportedAsNotYetMeasured() {
        assertEquals(
            Reading.NotYetMeasured,
            CycleCountResolver.resolve(broadcastCycles = null, measured = Reading.NotYetMeasured),
        )
    }

    /**
     * With no design capacity known, this app's own count is itself `Unsupported`
     * ([MeasuredCycles.fromSessions]'s own first check), and with the broadcast reporting
     * nothing either there is no further source left to try -- `Unsupported`, not a promise
     * this app cannot keep.
     */
    @Test
    fun withNothingMeasurableAndNoBroadcastValueResultIsUnsupported() {
        assertEquals(
            Reading.Unsupported,
            CycleCountResolver.resolve(broadcastCycles = null, measured = Reading.Unsupported),
        )
    }

    @Test
    fun withNoMeasuredArgumentTheDefaultIsUnsupported() {
        assertEquals(
            Reading.Unsupported,
            CycleCountResolver.resolve(broadcastCycles = null),
        )
    }
}

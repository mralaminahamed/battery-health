package com.alaminahamed.batteryhealth.data.vendor.discovery

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameworkStateOfHealthTest {

    @Test
    fun itAsksForPropertyTen() {
        var asked = -1
        FrameworkStateOfHealth.read { id -> asked = id; 90 }
        assertEquals(10, asked)
    }

    /**
     * The case this whole file exists for: on a build where AOSP's `stateOfHealthPublic()`
     * flag is on, the vendor's own figure arrives with no permission at all. Source is
     * [Source.Framework], not [Source.Privileged] -- it did not come through a privileged
     * shell (this app has none any more) and must not claim to have.
     */
    @Test
    fun aPermittedReadingIsFrameworkSourced() {
        assertEquals(
            Reading.Available(94, Source.Framework),
            FrameworkStateOfHealth.read { 94 },
        )
    }

    /**
     * A denial is not the same as an absence: the platform has the figure and is
     * withholding it, which is a different fact from the device simply not having one.
     * `NeedsPrivilegedAccess` is still the honest label for that distinction even though
     * this app itself has no remaining route to ever satisfy it -- see the task report for
     * why `FrameworkStateOfHealth`'s own behaviour was deliberately left unchanged here.
     */
    @Test
    fun aDenialSaysTheTierMightStillHelp() {
        assertEquals(
            Reading.NeedsPrivilegedAccess,
            FrameworkStateOfHealth.read { throw SecurityException("BATTERY_STATS") },
        )
    }

    /**
     * A platform that predates the property throws `NoSuchMethodError`. That is a
     * permanent no for the device, so it must not invite the user to unlock something
     * unlockable.
     */
    @Test
    fun aMissingImplementationIsUnsupportedNotUnlockable() {
        assertEquals(
            Reading.Unsupported,
            FrameworkStateOfHealth.read { throw NoSuchMethodError("getIntProperty") },
        )
    }

    @Test
    fun theUnsupportedSentinelIsUnsupported() {
        assertEquals(Reading.Unsupported, FrameworkStateOfHealth.read { Int.MIN_VALUE })
        assertEquals(Reading.Unsupported, FrameworkStateOfHealth.read { -1 })
    }

    /**
     * Refused, never clamped. Clamping 137 to 100 would turn a misread register into a
     * confident "your battery is perfect", which is precisely the invented number this app
     * is built not to produce.
     */
    @Test
    fun animplausiblePercentageIsRefusedRatherThanClamped() {
        assertEquals(Reading.Unsupported, FrameworkStateOfHealth.read { 137 })
        assertEquals(Reading.Unsupported, FrameworkStateOfHealth.read { 0 })
        assertEquals(Reading.Unsupported, FrameworkStateOfHealth.read { -20 })
    }

    @Test
    fun theBoundsThemselvesAreAccepted() {
        assertEquals(Reading.Available(1, Source.Framework), FrameworkStateOfHealth.read { 1 })
        assertEquals(Reading.Available(100, Source.Framework), FrameworkStateOfHealth.read { 100 })
    }
}

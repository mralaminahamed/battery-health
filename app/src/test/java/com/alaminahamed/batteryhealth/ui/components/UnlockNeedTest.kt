package com.alaminahamed.batteryhealth.ui.components

import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.data.privileged.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

class UnlockNeedTest {

    private val ready = PrivilegedAvailability.Ready(Transport.Adb)

    private fun need(
        granted: Boolean,
        availability: PrivilegedAvailability = PrivilegedAvailability.Unavailable,
        dumpFailed: Boolean = false,
    ) = UnlockNeed.of(granted, availability, dumpFailed)

    /**
     * The defect this rule exists for. With the permission granted, state of health and
     * both dates are already on screen -- and the card was still telling the user to go
     * and run `adb tcpip` for them, because it could only see the shell tier.
     */
    @Test
    fun aGrantedPermissionStopsTheCardAskingForWhatItAlreadySupplied() {
        assertEquals(UnlockNeed.Shell, need(granted = true))
    }

    @Test
    fun bothRoutesInPlaceMeansNothingToOffer() {
        assertEquals(UnlockNeed.Nothing, need(granted = true, availability = ready))
    }

    @Test
    fun neitherRouteInPlaceOffersBoth() {
        assertEquals(UnlockNeed.Both, need(granted = false))
    }

    /**
     * The shell alone leaves the permission worth having: manufacturing date has no
     * dumpsys value on the hardware this was verified against, so the shell cannot supply
     * it however well it is working.
     */
    @Test
    fun theShellAloneStillLeavesThePermissionWorthOffering() {
        assertEquals(UnlockNeed.Permission, need(granted = false, availability = ready))
    }

    /**
     * A connected tier whose reads come back empty supplies no cycle count, so it counts
     * as missing here. The card still distinguishes it when rendering, because "retry" and
     * "set this up" are different actions.
     */
    @Test
    fun aReadyButFailingShellCountsAsMissing() {
        assertEquals(UnlockNeed.Shell, need(granted = true, availability = ready, dumpFailed = true))
        assertEquals(UnlockNeed.Both, need(granted = false, availability = ready, dumpFailed = true))
    }

    /** Every in-progress shell state leaves the shell not yet working. */
    @Test
    fun anInProgressShellIsNotYetAWorkingOne() {
        listOf(
            PrivilegedAvailability.Connecting,
            PrivilegedAvailability.AwaitingAuthorization,
            PrivilegedAvailability.Denied,
        ).forEach { availability ->
            assertEquals("$availability", UnlockNeed.Shell, need(granted = true, availability = availability))
        }
    }
}

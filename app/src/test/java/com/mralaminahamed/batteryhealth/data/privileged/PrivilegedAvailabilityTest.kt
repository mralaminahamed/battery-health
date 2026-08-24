package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedAvailabilityTest {

    @Test
    fun rootWinsWhenBothTransportsAreReady() {
        // Root survives a reboot with no setup and adb does not, so a user with both
        // belongs on the one that still works tomorrow.
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Root),
            privilegedAvailability(root = TransportState.Ready, adb = TransportState.Ready),
        )
    }

    @Test
    fun adbIsUsedWhenRootIsUnavailable() {
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Adb),
            privilegedAvailability(root = TransportState.Unavailable, adb = TransportState.Ready),
        )
    }

    @Test
    fun aReadyTransportOutranksAPendingOne() {
        assertEquals(
            PrivilegedAvailability.Ready(Transport.Adb),
            privilegedAvailability(
                root = TransportState.AwaitingAuthorization,
                adb = TransportState.Ready,
            ),
        )
    }

    @Test
    fun awaitingAuthorizationOutranksDeniedBecauseAPromptIsOnScreen() {
        assertEquals(
            PrivilegedAvailability.AwaitingAuthorization,
            privilegedAvailability(
                root = TransportState.Denied,
                adb = TransportState.AwaitingAuthorization,
            ),
        )
    }

    @Test
    fun connectingOutranksDenied() {
        assertEquals(
            PrivilegedAvailability.Connecting,
            privilegedAvailability(root = TransportState.Denied, adb = TransportState.Connecting),
        )
    }

    @Test
    fun deniedOutranksUnavailableSoTheUserIsToldWhyRatherThanNothing() {
        assertEquals(
            PrivilegedAvailability.Denied,
            privilegedAvailability(root = TransportState.Denied, adb = TransportState.Unavailable),
        )
    }

    @Test
    fun unavailableWhenNeitherTransportOffersAnything() {
        assertEquals(
            PrivilegedAvailability.Unavailable,
            privilegedAvailability(
                root = TransportState.Unavailable,
                adb = TransportState.Unavailable,
            ),
        )
    }

    @Test
    fun rootAwaitingAuthorizationWinsOverAdbUnavailable() {
        // Pins the root == AwaitingAuthorization side of the || that only adb was testing.
        assertEquals(
            PrivilegedAvailability.AwaitingAuthorization,
            privilegedAvailability(
                root = TransportState.AwaitingAuthorization,
                adb = TransportState.Unavailable,
            ),
        )
    }

    @Test
    fun rootConnectingWinsOverAdbUnavailable() {
        // Pins the root == Connecting side of the || that only adb was testing.
        assertEquals(
            PrivilegedAvailability.Connecting,
            privilegedAvailability(
                root = TransportState.Connecting,
                adb = TransportState.Unavailable,
            ),
        )
    }

    @Test
    fun adbDeniedIsReportedToUnrootedPhone() {
        // Unrooted phone, ADB pairing attempted and refused. Pins adb == Denied,
        // which was not tested and must not be deleted -- user needs to know why
        // instead of being told "nothing is available".
        assertEquals(
            PrivilegedAvailability.Denied,
            privilegedAvailability(root = TransportState.Unavailable, adb = TransportState.Denied),
        )
    }
}

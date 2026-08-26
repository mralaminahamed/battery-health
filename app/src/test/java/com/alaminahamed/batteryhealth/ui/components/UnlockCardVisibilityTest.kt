package com.alaminahamed.batteryhealth.ui.components

import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability
import com.alaminahamed.batteryhealth.data.privileged.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockCardVisibilityTest {

    private val ready = PrivilegedAvailability.Ready(Transport.Adb)

    private fun shows(
        availability: PrivilegedAvailability,
        dumpFailed: Boolean = false,
        dismissed: Boolean = false,
        permissionGranted: Boolean = false,
        shellSupported: Boolean = true,
    ) = UnlockCardVisibility.shouldShow(
        need = UnlockNeed.of(permissionGranted, availability, dumpFailed, shellSupported),
        availability = availability,
        dumpFailed = dumpFailed,
        dismissed = dismissed,
    )

    // ---- behaviour that predates dismissal, which must not regress -------------------

    /**
     * Both routes in place, so there is nothing left to offer.
     *
     * `permissionGranted` is load-bearing and this test used to omit it, back when the
     * shell was the only route and a working shell therefore meant everything was
     * available. That stopped being true: the shell has no manufacturing date on the
     * hardware this was verified against, so a working shell alone still leaves the
     * permission worth offering.
     */
    @Test
    fun bothRoutesInPlaceNeedNoCard() {
        assertFalse(shows(ready, permissionGranted = true))
    }

    /**
     * The other half of the correction. A working shell with no permission is not
     * "everything is fine" -- state of health, first use and manufacturing date are all
     * still worth unlocking, so the card stays.
     */
    @Test
    fun aWorkingShellAloneStillHasSomethingToOffer() {
        assertTrue(shows(ready, permissionGranted = false))
    }

    @Test
    fun withoutDismissalEveryOtherStateShowsTheCard() {
        // permissionGranted defaults to false here, which is the ordinary install.
        assertTrue(shows(PrivilegedAvailability.Unavailable))
        assertTrue(shows(PrivilegedAvailability.Denied))
        assertTrue(shows(PrivilegedAvailability.AwaitingAuthorization))
        assertTrue(shows(PrivilegedAvailability.Connecting))
        assertTrue(shows(ready, dumpFailed = true))
    }

    // ---- what dismissal silences ----------------------------------------------------

    /**
     * The states dismissal exists for. Both are the card advertising a feature the user
     * has not asked for, and a user who has decided they do not want the privileged tier
     * should not be told about it on every launch forever.
     */
    @Test
    fun dismissalSilencesTheStatesThatAreJustAnOffer() {
        assertFalse(shows(PrivilegedAvailability.Unavailable, dismissed = true))
        assertFalse(shows(PrivilegedAvailability.Denied, dismissed = true))
    }

    /**
     * The line dismissal must not cross. These three are not an offer -- they are the
     * consequence of something the user themselves started, and each is the only place
     * the app explains it.
     *
     * `AwaitingAuthorization` is the card telling them to look at the system dialog they
     * are being shown right now; hiding it strands them in front of a prompt with no
     * explanation. `Connecting` is feedback for a button they just pressed. Ready with a
     * failed dump is the only route to a retry, and without it the symptom is every
     * privileged row silently reading "needs privileged access" again, indistinguishable
     * from never having connected.
     *
     * A dismissal that also swallowed these would turn one tap into a permanently broken
     * setup flow with no way back.
     */
    @Test
    fun dismissalDoesNotSilenceWhatTheUserThemselvesStarted() {
        assertTrue(shows(PrivilegedAvailability.AwaitingAuthorization, dismissed = true))
        assertTrue(shows(PrivilegedAvailability.Connecting, dismissed = true))
        assertTrue(shows(ready, dumpFailed = true, dismissed = true))
    }

    /**
     * Dismissal is not a reason to render a card in the one state that has nothing to
     * say. Ready-and-working stays silent whether or not it was ever dismissed.
     */
    @Test
    fun dismissalCannotResurrectTheCardWhenNothingIsNeeded() {
        assertFalse(shows(ready, dismissed = true, permissionGranted = true))
    }

    // ---- which states offer the control at all ---------------------------------------

    /**
     * The dismiss control appears exactly where dismissal has an effect. Offering it on a
     * state it cannot silence would be a button that visibly does nothing.
     */
    @Test
    fun onlyTheDismissibleStatesOfferTheControl() {
        assertTrue(UnlockCardVisibility.isDismissible(PrivilegedAvailability.Unavailable, dumpFailed = false))
        assertTrue(UnlockCardVisibility.isDismissible(PrivilegedAvailability.Denied, dumpFailed = false))

        assertFalse(UnlockCardVisibility.isDismissible(PrivilegedAvailability.AwaitingAuthorization, dumpFailed = false))
        assertFalse(UnlockCardVisibility.isDismissible(PrivilegedAvailability.Connecting, dumpFailed = false))
        assertFalse(UnlockCardVisibility.isDismissible(ready, dumpFailed = true))
        assertFalse(UnlockCardVisibility.isDismissible(ready, dumpFailed = false))
    }

    /**
     * Every state the card can render must either be dismissible or be shown despite a
     * dismissal -- never neither, which would be a card nobody can get rid of and nobody
     * asked to see. Driven off the sealed hierarchy so a sixth state has to answer this.
     */
    @Test
    fun everyRenderedStateIsEitherDismissibleOrDeliberatelyPersistent() {
        val states = listOf(
            PrivilegedAvailability.Unavailable to false,
            PrivilegedAvailability.Denied to false,
            PrivilegedAvailability.AwaitingAuthorization to false,
            PrivilegedAvailability.Connecting to false,
            ready to true,
        )
        states.forEach { (availability, dumpFailed) ->
            val rendered = shows(availability, dumpFailed, dismissed = false)
            assertTrue("$availability should render", rendered)
            val dismissible = UnlockCardVisibility.isDismissible(availability, dumpFailed)
            val persistsAnyway = shows(availability, dumpFailed, dismissed = true)
            assertTrue(
                "$availability is neither dismissible nor deliberately persistent",
                dismissible || persistsAnyway,
            )
        }
    }

    /**
     * A build with no transport compiled in cannot be helped by `adb tcpip`, so with the
     * permission granted there is nothing left to offer and the card must not appear.
     *
     * This is the case that exposed the split derivation: `shouldShow` computed its own
     * need and defaulted `shellSupported` to true, so it returned "show" while the text
     * function returned "" -- a card with a heading, a button and no words, on a real
     * device. The need is now derived once and passed in.
     */
    @Test
    fun aBuildWithoutATransportOffersNothingOnceThePermissionIsGranted() {
        assertFalse(shows(PrivilegedAvailability.Unavailable, permissionGranted = true, shellSupported = false))
    }

    /** Without the permission there is still one real thing to offer, even with no shell. */
    @Test
    fun aBuildWithoutATransportStillOffersThePermission() {
        assertTrue(shows(PrivilegedAvailability.Unavailable, permissionGranted = false, shellSupported = false))
    }
}

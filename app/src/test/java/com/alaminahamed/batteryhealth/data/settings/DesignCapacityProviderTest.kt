package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DesignCapacityProvider.effective` itself needs a real `DeviceIdentity`/`Named` wiring
 * from Hilt, not available to a plain JVM test in this project (no Robolectric, no
 * third-party test doubles; see the module's hard constraint on new dependencies). The
 * precedence rule it applies is pure, though, so it is pulled out as
 * [DesignCapacityProvider.resolve] purely so it is provable on the JVM instead of only
 * through an instrumented test this environment cannot even run.
 */
class DesignCapacityProviderTest {

    private fun samsung(model: String) =
        DeviceIdentity(manufacturer = "samsung", brand = "samsung", model = model, device = "")

    /** A device no table row can claim, so only the argued-about sources are in play. */
    private val unlisted = samsung("SM-X999Z")

    @Test
    fun theTableIsUsedWhenThisDeviceIsListed() {
        val result = DesignCapacityProvider.resolve(
            identity = samsung("SM-A356E"),
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity(5000, DesignCapacitySource.Table), result)
    }

    // ---- the device's own declaration ------------------------------------------------

    /**
     * The case that makes this app work on devices no table lists, which is nearly all of
     * them. Before the device source existed, an unlisted model reported nothing and the
     * user had to find the figure themselves -- and now cannot supply it any other way
     * either, since the override that used to let them type one in is gone.
     */
    @Test
    fun anUnlistedDeviceFallsBackToItsOwnDeclaration() {
        val result = DesignCapacityProvider.resolve(
            identity = unlisted,
            powerProfileMah = 4820,
        )
        assertEquals(EffectiveDesignCapacity(4820, DesignCapacitySource.PowerProfile), result)
    }

    /**
     * The table outranks the device's own declaration, and this pins that deliberately
     * rather than leaving it to whichever branch happened to be written first. Table rows
     * are held to two independent published sources; `power_profile.xml` is a field OEMs
     * are known to ship unfilled or wrong. When both exist, the more strongly evidenced
     * one wins. This order is unchanged from before the (now-removed) override sat above
     * both of them -- see this class's own doc.
     */
    @Test
    fun theTableOutranksTheDevicesOwnDeclaration() {
        val result = DesignCapacityProvider.resolve(
            identity = samsung("SM-A356E"),
            powerProfileMah = 4200,
        )
        assertEquals(EffectiveDesignCapacity(5000, DesignCapacitySource.Table), result)
    }

    /**
     * A device that offers nothing usable must still report absence rather than reaching
     * for a default. `powerProfileMah` arrives here already filtered by
     * `PowerProfileCapacity.interpret`, so null is how an implausible declaration -- AOSP's
     * placeholder of `2`, most often -- presents itself at this layer. With the override
     * removed, this is now also the terminal case: there is no further way for the user to
     * supply a value from inside the app.
     */
    @Test
    fun anImplausibleOrMissingDeclarationOnAnUnlistedDeviceReportsNone() {
        val result = DesignCapacityProvider.resolve(
            identity = unlisted,
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity.None, result)
    }
}

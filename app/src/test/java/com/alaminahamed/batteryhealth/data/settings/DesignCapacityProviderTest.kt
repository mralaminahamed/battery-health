package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `DesignCapacityProvider.effective` itself needs a real `SettingsStore`, which needs a
 * real `Context` -- not available to a plain JVM test in this project (no Robolectric,
 * no third-party test doubles; see the module's hard constraint on new dependencies).
 * The precedence rule it applies is pure, though, so it is pulled out as
 * [DesignCapacityProvider.resolve] purely so it is provable on the JVM instead of only
 * through an instrumented test this environment cannot even run.
 */
class DesignCapacityProviderTest {

    private fun samsung(model: String) =
        DeviceIdentity(manufacturer = "samsung", brand = "samsung", model = model, device = "")

    /** A device no table row can claim, so only the argued-about sources are in play. */
    private val unlisted = samsung("SM-X999Z")

    @Test
    fun anOverrideWinsOverAKnownTableEntry() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = 5555,
            identity = samsung("SM-A356E"),
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity(5555, DesignCapacitySource.Override), result)
    }

    @Test
    fun clearingTheOverrideFallsBackToTheTable() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = null,
            identity = samsung("SM-A356E"),
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity(5000, DesignCapacitySource.Table), result)
    }

    @Test
    fun anOverrideWinsEvenOnAnUnknownModel() {
        // The whole point of the override: it must work precisely where the table can't.
        val result = DesignCapacityProvider.resolve(
            overrideMah = 4500,
            identity = unlisted,
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity(4500, DesignCapacitySource.Override), result)
    }

    @Test
    fun nothingAtAllReportsNone() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = null,
            identity = unlisted,
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity.None, result)
    }

    // ---- the device's own declaration ------------------------------------------------

    /**
     * The case that makes this app work on devices no table lists, which is nearly all of
     * them. Before the device source existed, an unlisted model reported nothing and the
     * user had to find the figure themselves.
     */
    @Test
    fun anUnlistedDeviceFallsBackToItsOwnDeclaration() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = null,
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
     * one wins.
     */
    @Test
    fun theTableOutranksTheDevicesOwnDeclaration() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = null,
            identity = samsung("SM-A356E"),
            powerProfileMah = 4200,
        )
        assertEquals(EffectiveDesignCapacity(5000, DesignCapacitySource.Table), result)
    }

    @Test
    fun anOverrideOutranksTheDevicesOwnDeclaration() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = 4500,
            identity = unlisted,
            powerProfileMah = 4820,
        )
        assertEquals(EffectiveDesignCapacity(4500, DesignCapacitySource.Override), result)
    }

    /**
     * A device that offers nothing usable must still report absence rather than reaching
     * for a default. `powerProfileMah` arrives here already filtered by
     * `PowerProfileCapacity.interpret`, so null is how an implausible declaration -- AOSP's
     * placeholder of `2`, most often -- presents itself at this layer.
     */
    @Test
    fun anImplausibleDeclarationIsIndistinguishableFromNone() {
        val result = DesignCapacityProvider.resolve(
            overrideMah = null,
            identity = unlisted,
            powerProfileMah = null,
        )
        assertEquals(EffectiveDesignCapacity.None, result)
    }
}

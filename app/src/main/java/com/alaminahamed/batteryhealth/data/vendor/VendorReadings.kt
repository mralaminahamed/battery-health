package com.alaminahamed.batteryhealth.data.vendor

import com.alaminahamed.batteryhealth.domain.Reading

/**
 * Vendor facts published where any app can read them.
 *
 * An interface for the same reason [com.alaminahamed.batteryhealth.data.framework.GrantedReadings]
 * is one: the concrete implementation answers differently depending on which phone the
 * suite happens to run on. `BatteryRepositoryTest` asserts what the repository does when
 * these are absent, and on a Samsung host the real source answers, so those assertions
 * were passing or failing on hardware rather than on behaviour.
 *
 * That already happened once with the granted readings and was fixed there. It recurred
 * here the moment a second host-dependent source was added, which is the argument for
 * making this the shape such a source takes by default.
 */
interface VendorReadings {
    fun batteryProtectEnabled(): Reading<Boolean>
    fun batteryProtectThresholdPct(): Reading<Int>

    companion object {
        /**
         * A device that publishes nothing -- every non-Samsung phone, and the
         * deterministic stand-in for tests.
         *
         * [Reading.Unsupported] rather than [Reading.NeedsPrivilegedAccess]: no privilege
         * produces these, so inviting the user to unlock something would be a lie.
         */
        val None: VendorReadings = object : VendorReadings {
            override fun batteryProtectEnabled() = Reading.Unsupported
            override fun batteryProtectThresholdPct() = Reading.Unsupported
        }
    }
}

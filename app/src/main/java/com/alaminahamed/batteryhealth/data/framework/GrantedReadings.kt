package com.alaminahamed.batteryhealth.data.framework

import com.alaminahamed.batteryhealth.domain.Reading

/**
 * The readings a granted `BATTERY_STATS` unlocks.
 *
 * An interface rather than the concrete [GrantedBatterySource] because the concrete one
 * answers differently depending on whether the *host device* has the permission granted --
 * and a test whose result depends on host state is not a test. `BatteryRepositoryTest`
 * asserts what the repository does when these readings are absent, and that assertion has
 * to hold whether or not somebody has run `pm grant` on the phone the suite happens to be
 * running on. [None] gives it that.
 */
interface GrantedReadings {
    fun stateOfHealthPct(): Reading<Int>
    fun manufacturingDateEpochDay(): Reading<Long>
    fun firstUsageDateEpochDay(): Reading<Long>

    companion object {
        /**
         * Nothing granted, deterministically.
         *
         * [Reading.NeedsPrivilegedAccess] rather than [Reading.Unsupported]: these values
         * genuinely can be unlocked, so this stands in for an ungranted device rather than
         * one that lacks the properties entirely.
         */
        val None: GrantedReadings = object : GrantedReadings {
            override fun stateOfHealthPct() = Reading.NeedsPrivilegedAccess
            override fun manufacturingDateEpochDay() = Reading.NeedsPrivilegedAccess
            override fun firstUsageDateEpochDay() = Reading.NeedsPrivilegedAccess
        }
    }
}

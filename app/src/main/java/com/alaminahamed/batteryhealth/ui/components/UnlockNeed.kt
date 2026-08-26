package com.alaminahamed.batteryhealth.ui.components

import com.alaminahamed.batteryhealth.data.privileged.PrivilegedAvailability

/**
 * What, if anything, is still worth setting up.
 *
 * The unlock card used to key on the shell tier alone, because for most of this app's life
 * the shell was the only way to get anything. That stopped being true: a granted
 * `BATTERY_STATS` supplies state of health and both dates, and `Settings.Global` supplies
 * Battery Protect and its charge limit with no setup whatsoever.
 *
 * The card kept advertising the shell regardless, so a user who had already granted the
 * permission was told to go and run `adb tcpip` for readings that were on their screen.
 * That is the same class of defect as the health headline promising progress while nothing
 * was recording: a prompt describing a state that no longer holds.
 */
enum class UnlockNeed {
    /** Both routes are in place. Nothing to offer, so nothing is shown. */
    Nothing,

    /**
     * The permission is missing. It supplies state of health, first-use date and
     * manufacturing date, and costs one adb command that survives reboots.
     */
    Permission,

    /**
     * The shell tier is missing. It is the only source of Samsung's accumulated cycle
     * count and its BSOH figure -- neither has a `BATTERY_PROPERTY` id, so the permission
     * cannot reach them.
     */
    Shell,

    /** Neither route is in place. */
    Both,
    ;

    companion object {
        /**
         * Pure, so the rule is provable without a device -- which matters because it
         * decides whether a user is shown setup instructions they do not need.
         *
         * [dumpFailed] counts as the shell being unavailable rather than as its own state:
         * a connected tier whose reads come back empty supplies no cycle count, so from
         * this rule's point of view it is missing. The card still tells the two apart when
         * it renders, because "retry" and "set this up" are different actions.
         */
        fun of(
            permissionGranted: Boolean,
            availability: PrivilegedAvailability,
            dumpFailed: Boolean,
            shellSupported: Boolean = true,
        ): UnlockNeed {
            // A build with no transport compiled in cannot be helped by `adb tcpip`, so
            // offering it would be telling the user to run a command that achieves
            // nothing. Treated as satisfied for the same reason a screen the permission
            // cannot help is treated as granted: there is no offer left to make.
            val shellWorking =
                !shellSupported || (availability is PrivilegedAvailability.Ready && !dumpFailed)
            return when {
                permissionGranted && shellWorking -> Nothing
                permissionGranted -> Shell
                shellWorking -> Permission
                else -> Both
            }
        }
    }
}

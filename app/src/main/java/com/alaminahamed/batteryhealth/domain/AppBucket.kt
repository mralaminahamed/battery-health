package com.alaminahamed.batteryhealth.domain

/**
 * Which list on the Apps screen a uid belongs to.
 *
 * The three answer different questions a user actually has: what that I opened is using
 * the CPU, what is using it that I never opened, and what belongs to the phone itself.
 * Folding the middle one into either neighbour is what makes battery screens feel
 * dishonest -- a background service with no launcher entry is neither something the user
 * chose to run nor part of the platform.
 *
 * "Hidden" means no launcher entry, not invisible to this app. Packages genuinely outside
 * this build's visibility cannot be enumerated at all -- there is no bucket for them
 * because there is no way to know they exist. See `UidCpuTimeSource` for what that costs.
 */
enum class AppBucket {
    /** Installed, and openable from the launcher. */
    Visible,

    /**
     * Installed with no launcher entry: services, providers and helper packages the user
     * never opens directly. Real CPU consumers, and the ones a user is most likely to be
     * surprised by, which is precisely why they get their own list rather than being
     * dropped or blended into the system bucket.
     */
    Hidden,

    /** Part of the platform -- below the app uid boundary, or the adb shell. */
    System,
    ;

    companion object {
        /**
         * Pure: [kind] comes from the uid number and [hasLauncherEntry] from
         * `PackageManager`, both decided by the caller, so the rule itself is provable
         * without a device.
         *
         * Kind wins over the launcher check. A platform uid with a launcher entry -- the
         * Settings app, for instance -- is still part of the phone, and listing it beside
         * things the user installed would misrepresent what it is.
         */
        fun of(kind: UidKind, hasLauncherEntry: Boolean): AppBucket = when (kind) {
            UidKind.System, UidKind.Shell -> System
            UidKind.App -> if (hasLauncherEntry) Visible else Hidden
        }
    }
}

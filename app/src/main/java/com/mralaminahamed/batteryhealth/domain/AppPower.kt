package com.mralaminahamed.batteryhealth.domain

/**
 * What kind of consumer a uid represents, decided purely from the uid number itself --
 * never from a package name, a heuristic on how much power it drew, or anything else that
 * could vary row to row. Kept as three distinct cases rather than one "app" bucket with an
 * `isSystem` flag: an app, a platform/system uid and the adb shell mean three different
 * things to a user, and this codebase's own history is full of defects shaped exactly
 * like "one rule applied to cases that needed different ones" -- see
 * `AppPowerAggregator`'s own doc (`data.repo`) for the concrete example this type exists
 * to prevent.
 */
enum class UidKind {
    /** A user-installed app, per Android's own uid-space convention
     * ([Process.FIRST_APPLICATION_UID][android.os.Process.FIRST_APPLICATION_UID] == 10000).
     * The only kind [AppLabelResolver][com.mralaminahamed.batteryhealth.data.apps.AppLabelResolver]
     * is ever asked to resolve a label for. */
    App,

    /** A platform/system uid below the app boundary -- the phone's own services, radios
     * and daemons, root included. Never something a user installed or can act on the way
     * they can an app; a shared uid like `1000` can legitimately own dozens of packages,
     * which is itself a reason not to resolve and show one arbitrary app icon for it. */
    System,

    /** uid `2000` specifically: `adb`/USB-debugging's own shell uid. Kept apart from
     * [System] rather than folded into it because its presence, and often its sheer size,
     * on a real device is a side effect of development and testing having been done on
     * that device -- not a normal-use consumer at all, and not something folding it into
     * an undifferentiated "system" bucket would make clear. */
    Shell;

    companion object {
        private const val SHELL_UID = 2000
        private const val FIRST_APPLICATION_UID = 10_000

        fun of(uid: Int): UidKind = when {
            uid == SHELL_UID -> Shell
            uid < FIRST_APPLICATION_UID -> System
            else -> App
        }
    }
}

/**
 * One uid's row on the Apps screen: how much power it drew, its share of everything the
 * dump accounted for, which [UidKind] it is, and the raw package names `--checkin`'s own
 * uid dictionary attributes to it (zero, one, or many). Label/icon resolution is
 * deliberately not done here: that needs `PackageManager`, which this pure domain type
 * has no access to, and only makes sense for [UidKind.App] rows in the first place (see
 * `AppRowMapper` in `data.apps` for where that split actually happens).
 */
data class AppPowerEntry(
    val uid: Int,
    val mAh: Double,
    val sharePct: Double,
    val kind: UidKind,
    val packages: List<String>,
)

package com.alaminahamed.batteryhealth.data.settings

/**
 * Maps a raw `AppOpsManager.checkOpNoThrow` mode to whether an appop-gated permission --
 * `PACKAGE_USAGE_STATS` in this app, the only one it declares -- is actually held right
 * now.
 *
 * Kept as a pure function over the raw `Int`, not `android.app.AppOpsManager` itself, so
 * this stays JVM-testable without Robolectric -- the same reasoning `AdbPortValidation`
 * documents for staying free of `Context`. [MODE_ALLOWED] and [MODE_DEFAULT] are duplicated
 * from the platform's own constants rather than imported so this file never touches
 * `android.app`; they have not been renumbered since API 19, and
 * `AppOpPermissionStateTest` pins the literal values so a platform change would be caught
 * here rather than silently misreading every other mode as "held".
 *
 * `MODE_DEFAULT` is the case worth naming explicitly: it means the user has never touched
 * the appop, which is the ordinary state for nearly every app on the system. It is
 * deliberately not resolved as "not held" -- `PACKAGE_USAGE_STATS` is
 * `signature|privileged|development`, so a permission granted with `adb shell pm grant`
 * (no Settings toggle involved at all, the same route `BATTERY_STATS` uses) leaves the
 * appop at its untouched default while the underlying permission is genuinely granted.
 * Reporting that as "not held" would tell a user who has already unlocked this that they
 * have not. It is equally not resolved as "held": that would claim access for every app
 * that has simply never been asked about usage access, which is nearly all of them. Instead
 * `MODE_DEFAULT` falls through to whatever [checkSelfPermissionGranted] the caller measured
 * for `PACKAGE_USAGE_STATS` itself.
 */
object AppOpPermissionState {
    /** Mirrors `AppOpsManager.MODE_ALLOWED`. The appop was explicitly turned on. */
    const val MODE_ALLOWED = 0

    /** Mirrors `AppOpsManager.MODE_DEFAULT`. Neither turned on nor off -- see class doc. */
    const val MODE_DEFAULT = 3

    /**
     * @param mode the raw mode `checkOpNoThrow` returned.
     * @param checkSelfPermissionGranted whether `Context.checkSelfPermission` reports
     *   `PACKAGE_USAGE_STATS` itself granted. Consulted only when [mode] is [MODE_DEFAULT];
     *   every other mode (including `MODE_IGNORED` and `MODE_ERRORED`, an explicit denial
     *   either way) decides the answer on its own.
     */
    fun isHeld(mode: Int, checkSelfPermissionGranted: Boolean): Boolean = when (mode) {
        MODE_ALLOWED -> true
        MODE_DEFAULT -> checkSelfPermissionGranted
        else -> false
    }
}

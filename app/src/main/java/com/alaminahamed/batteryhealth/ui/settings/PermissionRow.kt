package com.alaminahamed.batteryhealth.ui.settings

/**
 * How a declared permission can actually be advanced, which decides both its state wording
 * and the one action the Permissions section offers for it -- see `PermissionCatalog` and
 * `permissionStateLabel`.
 *
 * Two other kinds used to live here. One was [Requestable]'s sibling for
 * `signature|privileged|development` permissions grantable only by `adb shell pm grant`,
 * for the two permissions (`BATTERY_STATS`, `DUMP`) that needed it: gone because this app
 * now asks for nothing a normal Android permission flow cannot grant, and an adb-only
 * permission can never be held by a real install. The other was `AppOp`, appop-gated and
 * grantable only from the system's own Usage access screen, for `PACKAGE_USAGE_STATS`
 * alone: gone because that permission existed only for the Apps screen's per-app drain
 * estimate, and that screen has been removed entirely. See the task report for both
 * removals.
 */
enum class PermissionKind {
    /** A real runtime dialog can ask for this one: `POST_NOTIFICATIONS`. */
    Requestable,

    /** Granted automatically at install. Nothing in this app or Settings can change it. */
    InstallTime,
}

/**
 * One row of the Permissions section: a permission this app declares, and what is actually
 * true about it on this device right now.
 *
 * @param shortName the permission's name with the `android.permission.` prefix dropped --
 *   `"POST_NOTIFICATIONS"`, not the fully-qualified constant. Every permission this app
 *   declares is an Android platform permission, so the prefix is implied and dropping it is
 *   what keeps the row label matching the wording already used elsewhere in this screen.
 */
data class PermissionRow(
    val shortName: String,
    val kind: PermissionKind,
    val held: Boolean,
)

/**
 * The state word shown for a row, chosen per [PermissionKind] rather than uniformly, so
 * that two permissions in the same not-held state read differently when the reason is
 * different: [PermissionKind.Requestable] can be answered "no" by a real dialog, which
 * "Denied" reflects. [PermissionKind.InstallTime] has one answer regardless of
 * [PermissionRow.held]: a row for one of these only exists because the platform already
 * granted it at install, so nothing else is worth saying.
 */
fun permissionStateLabel(row: PermissionRow): String = when (row.kind) {
    PermissionKind.Requestable -> if (row.held) "Granted" else "Denied"
    PermissionKind.InstallTime -> "Held"
}

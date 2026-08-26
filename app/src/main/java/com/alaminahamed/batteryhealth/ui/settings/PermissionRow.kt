package com.alaminahamed.batteryhealth.ui.settings

/**
 * How a declared permission can actually be advanced, which decides both its state wording
 * and the one action the Permissions section offers for it -- see `PermissionCatalog` and
 * `permissionStateLabel`.
 *
 * There used to be a third kind here, [Requestable]/[AppOp]'s sibling for
 * `signature|privileged|development` permissions grantable only by `adb shell pm grant`.
 * That kind, and the two permissions (`BATTERY_STATS`, `DUMP`) it existed for, are gone:
 * this app now asks for nothing a normal Android permission flow cannot grant, and an
 * adb-only permission can never be held by a real install, so it was declared for
 * nothing. See the task report for the removal.
 */
enum class PermissionKind {
    /** A real runtime dialog can ask for this one: `POST_NOTIFICATIONS`. */
    Requestable,

    /** Appop-gated; only the system's own Usage access screen can grant it. */
    AppOp,

    /** Granted automatically at install. Nothing in this app or Settings can change it. */
    InstallTime,
}

/**
 * One row of the Permissions section: a permission this app declares, and what is actually
 * true about it on this device right now.
 *
 * @param shortName the permission's name with the `android.permission.` prefix dropped --
 *   `"PACKAGE_USAGE_STATS"`, not the fully-qualified constant. Every permission this app
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
    PermissionKind.AppOp -> if (row.held) "Held" else "Not held"
    PermissionKind.InstallTime -> "Held"
}

package com.alaminahamed.batteryhealth.ui.settings

/**
 * How a declared permission can actually be advanced, which decides both its state wording
 * and the one action the Permissions section offers for it -- see `PermissionCatalog` and
 * `permissionStateLabel`.
 */
enum class PermissionKind {
    /** A real runtime dialog can ask for this one: `POST_NOTIFICATIONS`. */
    Requestable,

    /** Appop-gated; only the system's own Usage access screen can grant it. */
    AppOp,

    /** `signature|privileged|development`; only `adb shell pm grant` can grant it. */
    AdbGrant,

    /** Granted automatically at install. Nothing in this app or Settings can change it. */
    InstallTime,
}

/**
 * One row of the Permissions section: a permission this app declares, and what is actually
 * true about it on this device right now.
 *
 * @param shortName the permission's name with the `android.permission.` prefix dropped --
 *   `"BATTERY_STATS"`, not the fully-qualified constant. Every permission this app declares
 *   is an Android platform permission, so the prefix is implied and dropping it is what
 *   keeps the row label matching the wording already used elsewhere in this screen (the
 *   existing `BATTERY_STATS` row in "Privileged readings" is titled the same way).
 * @param adbCommand the exact command that grants this permission, present only for
 *   [PermissionKind.AdbGrant] rows -- there is no dialog and no Settings screen for these,
 *   so the command is the entire action.
 */
data class PermissionRow(
    val shortName: String,
    val kind: PermissionKind,
    val held: Boolean,
    val adbCommand: String? = null,
)

/**
 * The state word shown for a row, chosen per [PermissionKind] rather than uniformly, so
 * that two permissions in the same not-held state read differently when the reason is
 * different: [PermissionKind.Requestable] can be answered "no" by a real dialog, which
 * "Denied" reflects; [PermissionKind.AdbGrant] cannot be answered at all without a
 * computer, which "Not granted" reflects instead of implying a decision was made on the
 * phone. [PermissionKind.InstallTime] has one answer regardless of [PermissionRow.held]:
 * a row for one of these only exists because the platform already granted it at install,
 * so nothing else is worth saying.
 */
fun permissionStateLabel(row: PermissionRow): String = when (row.kind) {
    PermissionKind.Requestable -> if (row.held) "Granted" else "Denied"
    PermissionKind.AppOp -> if (row.held) "Held" else "Not held"
    PermissionKind.AdbGrant -> if (row.held) "Granted" else "Not granted"
    PermissionKind.InstallTime -> "Held"
}

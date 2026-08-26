package com.alaminahamed.batteryhealth.ui.settings

/**
 * Assembles every permission this app declares into the rows the Permissions section
 * renders, each already carrying its live state.
 *
 * A pure function over already-measured booleans, not a `Context`-reading one: every real
 * platform check (`Context.checkSelfPermission`) happens in `SettingsViewModel`, which is
 * where a live device is actually available. Keeping the assembly itself free of Android
 * types is what makes the shape of the list -- which permissions appear, in what order,
 * with which [PermissionKind] -- testable on the JVM.
 *
 * `BATTERY_STATS` and `DUMP` used to have rows here too, each carrying the exact
 * `adb shell pm grant` command that was their only route to being held. Both are gone: an
 * adb-only permission can never be granted on a real install, so declaring either was
 * asking for nothing, and this app now asks for nothing it cannot get through a normal
 * permission flow. `PACKAGE_USAGE_STATS` is gone too, for a different reason: it existed
 * only for the Apps screen's per-app drain estimate, and that screen has been removed
 * entirely -- see the task report.
 *
 * @param installTimeGranted the install-time permissions this app declares, keyed by
 *   [PermissionRow.shortName], in the order they should render.
 */
object PermissionCatalog {
    fun rows(
        notificationsGranted: Boolean,
        installTimeGranted: Map<String, Boolean>,
    ): List<PermissionRow> = buildList {
        add(PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, notificationsGranted))
        installTimeGranted.forEach { (shortName, granted) ->
            add(PermissionRow(shortName, PermissionKind.InstallTime, granted))
        }
    }
}

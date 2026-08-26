package com.alaminahamed.batteryhealth.ui.settings

/**
 * Assembles every permission this app declares into the rows the Permissions section
 * renders, each already carrying its live state.
 *
 * A pure function over already-measured booleans, not a `Context`-reading one: every real
 * platform check ([UsageAccessState], `Context.checkSelfPermission`) happens in
 * `SettingsViewModel`, which is where a live device is actually available. Keeping the
 * assembly itself free of Android types is what makes the shape of the list -- which
 * permissions appear, in what order, with which [PermissionKind] -- testable on the JVM.
 *
 * `BATTERY_STATS` and `DUMP` used to have rows here too, each carrying the exact
 * `adb shell pm grant` command that was their only route to being held. Both are gone: an
 * adb-only permission can never be granted on a real install, so declaring either was
 * asking for nothing, and this app now asks for nothing it cannot get through a normal
 * permission flow. See the task report for the removal.
 *
 * @param installTimeGranted the install-time permissions this app declares, keyed by
 *   [PermissionRow.shortName], in the order they should render. The caller decides which
 *   keys are present -- `QUERY_ALL_PACKAGES` exists only in the `full` flavour (see
 *   `app/src/full/AndroidManifest.xml`), so `SettingsViewModel` includes it only on that
 *   flavour, and this function has no flavour of its own to reason about -- it renders
 *   whatever map it is given.
 */
object PermissionCatalog {
    fun rows(
        notificationsGranted: Boolean,
        usageAccessHeld: Boolean,
        installTimeGranted: Map<String, Boolean>,
    ): List<PermissionRow> = buildList {
        add(PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, notificationsGranted))
        add(PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, usageAccessHeld))
        installTimeGranted.forEach { (shortName, granted) ->
            add(PermissionRow(shortName, PermissionKind.InstallTime, granted))
        }
    }
}

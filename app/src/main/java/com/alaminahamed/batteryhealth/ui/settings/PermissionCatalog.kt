package com.alaminahamed.batteryhealth.ui.settings

/**
 * Assembles every permission this app declares into the rows the Permissions section
 * renders, each already carrying its live state.
 *
 * A pure function over already-measured booleans, not a `Context`-reading one: every real
 * platform check ([UsageAccessState], `GrantedReadings.isGranted`,
 * `Context.checkSelfPermission`) happens in `SettingsViewModel`, which is where a live
 * device is actually available. Keeping the assembly itself free of Android types is what
 * makes the shape of the list -- which permissions appear, in what order, with which
 * [PermissionKind], and whether the two `full`-only ones are present at all -- testable on
 * the JVM.
 *
 * @param installTimeGranted the install-time permissions this app declares, keyed by
 *   [PermissionRow.shortName], in the order they should render. The caller decides which
 *   keys are present: `INTERNET` and `QUERY_ALL_PACKAGES` exist only in the `full` flavour
 *   (see `app/src/full/AndroidManifest.xml`), so `SettingsViewModel` includes them only
 *   when `privilegedTierSupported` is true, and this function has no flavour of its own to
 *   reason about -- it renders whatever map it is given.
 */
object PermissionCatalog {
    fun rows(
        packageName: String,
        notificationsGranted: Boolean,
        usageAccessHeld: Boolean,
        batteryStatsGranted: Boolean,
        dumpGranted: Boolean,
        installTimeGranted: Map<String, Boolean>,
    ): List<PermissionRow> = buildList {
        add(PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, notificationsGranted))
        add(PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, usageAccessHeld))
        add(
            PermissionRow(
                shortName = "BATTERY_STATS",
                kind = PermissionKind.AdbGrant,
                held = batteryStatsGranted,
                adbCommand = adbGrantCommand(packageName, "BATTERY_STATS"),
            ),
        )
        add(
            PermissionRow(
                shortName = "DUMP",
                kind = PermissionKind.AdbGrant,
                held = dumpGranted,
                adbCommand = adbGrantCommand(packageName, "DUMP"),
            ),
        )
        installTimeGranted.forEach { (shortName, granted) ->
            add(PermissionRow(shortName, PermissionKind.InstallTime, granted))
        }
    }

    private fun adbGrantCommand(packageName: String, shortName: String) =
        "adb shell pm grant $packageName android.permission.$shortName"
}

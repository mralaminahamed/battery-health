package com.alaminahamed.batteryhealth.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `PermissionCatalog.rows` assembles ten-plus booleans and a map into one ordered list,
 * and the failure mode worth guarding against is not "does it run" but "did it wire the
 * right input to the right row" -- a transposed parameter compiles fine and only shows up
 * as one permission silently reporting another's state. Every held value below is chosen
 * distinct from its neighbours (`true`/`false` alternating across the four named
 * parameters) specifically so a swap between any two of them changes a concrete
 * assertion's outcome rather than passing by coincidence.
 */
class PermissionCatalogTest {

    private fun rows(installTimeGranted: Map<String, Boolean> = linkedMapOf("FOREGROUND_SERVICE" to true)) =
        PermissionCatalog.rows(
            packageName = "com.example.probe",
            notificationsGranted = true,
            usageAccessHeld = false,
            batteryStatsGranted = true,
            dumpGranted = false,
            installTimeGranted = installTimeGranted,
        )

    @Test
    fun theFourNamedPermissionsCarryTheirOwnDistinctHeldValueInOrder() {
        val result = rows()

        assertEquals(
            listOf(
                PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = true),
                PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = false),
            ),
            result.take(2),
        )
        // BATTERY_STATS and DUMP checked separately below: they also carry an adbCommand,
        // which equals() already compares, but asserting held here first isolates a wrong
        // held value from a wrong command in a separate, more specific test.
        assertEquals(true, result[2].held)
        assertEquals(false, result[3].held)
    }

    @Test
    fun batteryStatsAndDumpEachGetTheirOwnAdbGrantCommandForThisPackage() {
        val result = rows()

        val batteryStats = result[2]
        assertEquals("BATTERY_STATS", batteryStats.shortName)
        assertEquals(PermissionKind.AdbGrant, batteryStats.kind)
        assertEquals(
            "adb shell pm grant com.example.probe android.permission.BATTERY_STATS",
            batteryStats.adbCommand,
        )

        val dump = result[3]
        assertEquals("DUMP", dump.shortName)
        assertEquals(PermissionKind.AdbGrant, dump.kind)
        // Distinct from BATTERY_STATS's command above -- catches an implementation that
        // reuses one formatted string for both AdbGrant rows instead of naming each.
        assertEquals(
            "adb shell pm grant com.example.probe android.permission.DUMP",
            dump.adbCommand,
        )
    }

    @Test
    fun nonAdbGrantRowsCarryNoCommand() {
        val result = rows()
        assertNull(result[0].adbCommand)
        assertNull(result[1].adbCommand)
    }

    @Test
    fun installTimePermissionsAppendInTheGivenMapOrderAsInstallTimeKind() {
        val result = rows(
            installTimeGranted = linkedMapOf(
                "FOREGROUND_SERVICE" to true,
                "WAKE_LOCK" to false,
                "RECEIVE_BOOT_COMPLETED" to true,
            ),
        )

        val installTimeRows = result.drop(4)
        assertEquals(
            listOf(
                PermissionRow("FOREGROUND_SERVICE", PermissionKind.InstallTime, held = true),
                PermissionRow("WAKE_LOCK", PermissionKind.InstallTime, held = false),
                PermissionRow("RECEIVE_BOOT_COMPLETED", PermissionKind.InstallTime, held = true),
            ),
            installTimeRows,
        )
    }

    @Test
    fun fullFlavourOnlyPermissionsAppearOnlyWhenThePlayFlavourCallerOmitsThem() {
        // SettingsViewModel decides which keys are present, not this function -- the play
        // caller's map (no INTERNET/QUERY_ALL_PACKAGES) must not surface them, which is
        // the actual mechanism that keeps the `play` build's Permissions section from ever
        // claiming a permission that build's manifest does not declare.
        val playLike = rows(installTimeGranted = linkedMapOf("FOREGROUND_SERVICE" to true))
        assertEquals(5, playLike.size) // 4 named + 1 install-time
        assertEquals(emptyList<String>(), playLike.map { it.shortName }.filter { it in setOf("INTERNET", "QUERY_ALL_PACKAGES") })

        val fullLike = rows(
            installTimeGranted = linkedMapOf(
                "FOREGROUND_SERVICE" to true,
                "INTERNET" to true,
                "QUERY_ALL_PACKAGES" to true,
            ),
        )
        assertEquals(7, fullLike.size) // 4 named + 3 install-time
        assertEquals(
            listOf("INTERNET", "QUERY_ALL_PACKAGES"),
            fullLike.map { it.shortName }.filter { it in setOf("INTERNET", "QUERY_ALL_PACKAGES") },
        )
    }
}

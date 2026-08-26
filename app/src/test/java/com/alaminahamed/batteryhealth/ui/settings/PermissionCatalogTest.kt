package com.alaminahamed.batteryhealth.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PermissionCatalog.rows` assembles a handful of booleans and a map into one ordered
 * list, and the failure mode worth guarding against is not "does it run" but "did it wire
 * the right input to the right row" -- a transposed parameter compiles fine and only shows
 * up as one permission silently reporting another's state. The two named parameters below
 * are chosen distinct from each other (`true`/`false`) specifically so a swap between them
 * changes a concrete assertion's outcome rather than passing by coincidence.
 */
class PermissionCatalogTest {

    private fun rows(installTimeGranted: Map<String, Boolean> = linkedMapOf("FOREGROUND_SERVICE" to true)) =
        PermissionCatalog.rows(
            notificationsGranted = true,
            usageAccessHeld = false,
            installTimeGranted = installTimeGranted,
        )

    @Test
    fun theTwoNamedPermissionsCarryTheirOwnDistinctHeldValueInOrder() {
        val result = rows()

        assertEquals(
            listOf(
                PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = true),
                PermissionRow("PACKAGE_USAGE_STATS", PermissionKind.AppOp, held = false),
            ),
            result.take(2),
        )
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

        val installTimeRows = result.drop(2)
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
        // caller's map (no QUERY_ALL_PACKAGES) must not surface it, which is the actual
        // mechanism that keeps the `play` build's Permissions section from ever claiming a
        // permission that build's manifest does not declare.
        val playLike = rows(installTimeGranted = linkedMapOf("FOREGROUND_SERVICE" to true))
        assertEquals(3, playLike.size) // 2 named + 1 install-time
        assertEquals(emptyList<String>(), playLike.map { it.shortName }.filter { it == "QUERY_ALL_PACKAGES" })

        val fullLike = rows(
            installTimeGranted = linkedMapOf(
                "FOREGROUND_SERVICE" to true,
                "QUERY_ALL_PACKAGES" to true,
            ),
        )
        assertEquals(4, fullLike.size) // 2 named + 2 install-time
        assertEquals(
            listOf("QUERY_ALL_PACKAGES"),
            fullLike.map { it.shortName }.filter { it == "QUERY_ALL_PACKAGES" },
        )
    }
}

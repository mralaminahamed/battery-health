package com.alaminahamed.batteryhealth.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `PermissionCatalog.rows` assembles a handful of booleans and a map into one ordered
 * list, and the failure mode worth guarding against is not "does it run" but "did it wire
 * the right input to the right row" -- a transposed parameter compiles fine and only shows
 * up as one permission silently reporting another's state.
 */
class PermissionCatalogTest {

    private fun rows(installTimeGranted: Map<String, Boolean> = linkedMapOf("FOREGROUND_SERVICE" to true)) =
        PermissionCatalog.rows(
            notificationsGranted = true,
            installTimeGranted = installTimeGranted,
        )

    @Test
    fun theNamedPermissionCarriesItsOwnHeldValueFirst() {
        val result = rows()

        assertEquals(
            PermissionRow("POST_NOTIFICATIONS", PermissionKind.Requestable, held = true),
            result.first(),
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

        val installTimeRows = result.drop(1)
        assertEquals(
            listOf(
                PermissionRow("FOREGROUND_SERVICE", PermissionKind.InstallTime, held = true),
                PermissionRow("WAKE_LOCK", PermissionKind.InstallTime, held = false),
                PermissionRow("RECEIVE_BOOT_COMPLETED", PermissionKind.InstallTime, held = true),
            ),
            installTimeRows,
        )
    }
}

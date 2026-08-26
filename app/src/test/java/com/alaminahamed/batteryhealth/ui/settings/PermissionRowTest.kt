package com.alaminahamed.batteryhealth.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `permissionStateLabel` chooses different wording for the *same* `held` value depending
 * on [PermissionKind] -- see its own doc for why "Denied" (a real dialog answered "no")
 * and "Not granted" (nothing on the phone could even ask) are deliberately different
 * words. Every test below pins one (kind, held) combination to its exact word, which is
 * what catches an implementation that collapses two kinds to the same text or returns the
 * held-word for the not-held case.
 */
class PermissionRowTest {

    private fun row(kind: PermissionKind, held: Boolean) =
        PermissionRow(shortName = "X", kind = kind, held = held)

    @Test
    fun requestableGrantedReadsGranted() {
        assertEquals("Granted", permissionStateLabel(row(PermissionKind.Requestable, held = true)))
    }

    @Test
    fun requestableNotGrantedReadsDenied() {
        assertEquals("Denied", permissionStateLabel(row(PermissionKind.Requestable, held = false)))
    }

    @Test
    fun appOpHeldReadsHeld() {
        assertEquals("Held", permissionStateLabel(row(PermissionKind.AppOp, held = true)))
    }

    @Test
    fun appOpNotHeldReadsNotHeld() {
        assertEquals("Not held", permissionStateLabel(row(PermissionKind.AppOp, held = false)))
    }

    @Test
    fun adbGrantGrantedReadsGranted() {
        assertEquals("Granted", permissionStateLabel(row(PermissionKind.AdbGrant, held = true)))
    }

    @Test
    fun adbGrantNotGrantedReadsNotGranted() {
        // The word that distinguishes this from Requestable's "Denied": nothing on the
        // phone answered no, there is simply no route to grant this without a computer.
        assertEquals("Not granted", permissionStateLabel(row(PermissionKind.AdbGrant, held = false)))
    }

    @Test
    fun installTimeAlwaysReadsHeldRegardlessOfTheHeldFlag() {
        // A row for one of these only exists because the platform already granted it at
        // install, so the state word does not vary with `held` the way every other kind's
        // does -- a broken implementation that instead branches on `held` here (as it
        // correctly does for every other kind) would fail this specific assertion.
        assertEquals("Held", permissionStateLabel(row(PermissionKind.InstallTime, held = false)))
        assertEquals("Held", permissionStateLabel(row(PermissionKind.InstallTime, held = true)))
    }
}

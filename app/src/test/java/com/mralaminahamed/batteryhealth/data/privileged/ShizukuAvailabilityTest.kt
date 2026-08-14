package com.mralaminahamed.batteryhealth.data.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuAvailabilityTest {

    @Test
    fun notInstalledWinsOverEveryOtherFactBeingTrue() {
        // A stale binder-alive/permission/bound set left over from a previous install is
        // exactly the scenario this ordering exists for: package absence must dominate.
        assertEquals(
            ShizukuAvailability.NotInstalled,
            shizukuAvailability(
                packageInstalled = false,
                binderAlive = true,
                permissionGranted = true,
                serviceBound = true,
            ),
        )
    }

    @Test
    fun notRunningWhenInstalledButBinderIsDead() {
        assertEquals(
            ShizukuAvailability.NotRunning,
            shizukuAvailability(
                packageInstalled = true,
                binderAlive = false,
                permissionGranted = true,
                serviceBound = true,
            ),
        )
    }

    @Test
    fun permissionNotGrantedWhenBinderAliveButPermissionMissing() {
        assertEquals(
            ShizukuAvailability.PermissionNotGranted,
            shizukuAvailability(
                packageInstalled = true,
                binderAlive = true,
                permissionGranted = false,
                serviceBound = false,
            ),
        )
    }

    @Test
    fun connectingWhenGrantedButServiceNotYetBound() {
        assertEquals(
            ShizukuAvailability.Connecting,
            shizukuAvailability(
                packageInstalled = true,
                binderAlive = true,
                permissionGranted = true,
                serviceBound = false,
            ),
        )
    }

    @Test
    fun boundWhenEveryFactHolds() {
        assertEquals(
            ShizukuAvailability.Bound,
            shizukuAvailability(
                packageInstalled = true,
                binderAlive = true,
                permissionGranted = true,
                serviceBound = true,
            ),
        )
    }

    @Test
    fun aDeadBinderDominatesOverAnAlreadyBoundService() {
        // Guards against a "was bound, now stale" defect: if the binder dies, the fact
        // that a service was bound a moment ago must not outrank the deader, more
        // fundamental fact underneath it.
        assertEquals(
            ShizukuAvailability.NotRunning,
            shizukuAvailability(
                packageInstalled = true,
                binderAlive = false,
                permissionGranted = true,
                serviceBound = true,
            ),
        )
    }
}

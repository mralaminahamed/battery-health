package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppOpPermissionState.isHeld]'s whole job is getting `MODE_DEFAULT` right -- the other
 * three modes each have one unambiguous answer, but `MODE_DEFAULT` is the state almost
 * every app is actually in for `PACKAGE_USAGE_STATS`, and getting it backwards either
 * locks out a user who already holds the underlying permission (an `adb pm grant`, the
 * same route `BATTERY_STATS` uses) or claims access for every app that has simply never
 * been asked -- see the class doc for why neither of those is acceptable.
 *
 * Every test here is a case a wrong implementation would actually fail, not a restatement
 * of the code: an implementation that ignores [AppOpPermissionState.isHeld]'s `mode`
 * parameter and returns `checkSelfPermissionGranted` outright passes
 * [modeAllowedIsHeldEvenWithoutTheUnderlyingPermission] only by accident and fails
 * [anExplicitlyIgnoredModeIsNotHeldEvenWithTheUnderlyingPermissionGranted] for real; one
 * that ignores `checkSelfPermissionGranted` entirely and always returns `mode ==
 * MODE_ALLOWED` fails both `MODE_DEFAULT` cases; one that treats `MODE_DEFAULT` as always
 * denied fails [modeDefaultFallsBackToTheUnderlyingPermissionWhenGranted]; one that treats
 * it as always granted fails [modeDefaultFallsBackToTheUnderlyingPermissionWhenNotGranted].
 */
class AppOpPermissionStateTest {

    @Test
    fun modeAllowedIsHeldEvenWithoutTheUnderlyingPermission() {
        // The appop was explicitly turned on from Settings' Usage access screen -- the
        // ordinary end-user route, which never touches the manifest permission's own
        // grant state at all.
        assertTrue(
            AppOpPermissionState.isHeld(
                mode = AppOpPermissionState.MODE_ALLOWED,
                checkSelfPermissionGranted = false,
            ),
        )
    }

    @Test
    fun modeDefaultFallsBackToTheUnderlyingPermissionWhenGranted() {
        // The appop was never touched, but `adb shell pm grant ... PACKAGE_USAGE_STATS`
        // granted the underlying signature|privileged|development permission directly --
        // the same route BATTERY_STATS uses. This is the case the class doc calls out by
        // name: MODE_DEFAULT must not read as "not held" here.
        assertTrue(
            AppOpPermissionState.isHeld(
                mode = AppOpPermissionState.MODE_DEFAULT,
                checkSelfPermissionGranted = true,
            ),
        )
    }

    @Test
    fun modeDefaultFallsBackToTheUnderlyingPermissionWhenNotGranted() {
        // The ordinary state for nearly every app on the system: appop untouched, and the
        // underlying permission was never adb-granted either. This must not read as
        // "held" -- that would claim access nothing actually unlocked.
        assertFalse(
            AppOpPermissionState.isHeld(
                mode = AppOpPermissionState.MODE_DEFAULT,
                checkSelfPermissionGranted = false,
            ),
        )
    }

    @Test
    fun anExplicitlyIgnoredModeIsNotHeldEvenWithTheUnderlyingPermissionGranted() {
        // MODE_IGNORED (1): the user was asked and said no, or the platform denied it.
        // That explicit "no" must win even if the manifest permission itself would
        // otherwise read granted -- an appop denial is not something the underlying
        // permission grant can override.
        assertFalse(AppOpPermissionState.isHeld(mode = 1, checkSelfPermissionGranted = true))
    }

    @Test
    fun anErroredModeIsNotHeldEvenWithTheUnderlyingPermissionGranted() {
        // MODE_ERRORED (2): same reasoning as MODE_IGNORED above, the other explicit
        // non-allowed mode AppOpsManager defines.
        assertFalse(AppOpPermissionState.isHeld(mode = 2, checkSelfPermissionGranted = true))
    }

    @Test
    fun theModeConstantsMatchAppOpsManagersOwnValues() {
        // AppOpPermissionState duplicates these rather than importing android.app --
        // see the class doc. If the platform ever renumbered them this app would silently
        // misread every mode; this pins the two values this file actually branches on.
        assertEquals(0, AppOpPermissionState.MODE_ALLOWED)
        assertEquals(3, AppOpPermissionState.MODE_DEFAULT)
    }
}

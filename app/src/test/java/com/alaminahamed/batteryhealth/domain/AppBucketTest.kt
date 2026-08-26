package com.alaminahamed.batteryhealth.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AppBucketTest {

    @Test
    fun anAppYouCanOpenIsVisible() {
        assertEquals(AppBucket.Visible, AppBucket.of(UidKind.App, hasLauncherEntry = true))
    }

    /**
     * The bucket this taxonomy exists for. A background service with no launcher entry is
     * neither something the user chose to run nor part of the platform, and it is the kind
     * of row a user is most likely to be surprised by -- so it gets its own list instead
     * of being dropped or blended into the system one.
     */
    @Test
    fun anInstalledAppWithNoLauncherEntryIsHidden() {
        assertEquals(AppBucket.Hidden, AppBucket.of(UidKind.App, hasLauncherEntry = false))
    }

    @Test
    fun platformUidsAreSystemWhateverTheirLauncherEntry() {
        assertEquals(AppBucket.System, AppBucket.of(UidKind.System, hasLauncherEntry = false))
        // Settings has a launcher entry and is still part of the phone. Listing it beside
        // things the user installed would misrepresent what it is.
        assertEquals(AppBucket.System, AppBucket.of(UidKind.System, hasLauncherEntry = true))
    }

    @Test
    fun theShellIsSystemNotHidden() {
        assertEquals(AppBucket.System, AppBucket.of(UidKind.Shell, hasLauncherEntry = false))
    }

    /** Every kind lands somewhere; none falls through to a default. */
    @Test
    fun everyKindIsClassified() {
        UidKind.entries.forEach { kind ->
            listOf(true, false).forEach { launchable ->
                assertEquals(
                    "$kind launchable=$launchable",
                    1,
                    AppBucket.entries.count { it == AppBucket.of(kind, launchable) },
                )
            }
        }
    }
}

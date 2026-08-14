package com.mralaminahamed.batteryhealth.data.apps

import com.mralaminahamed.batteryhealth.domain.AppPowerEntry
import com.mralaminahamed.batteryhealth.domain.UidKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRowMapperTest {

    /** Never touches a real PackageManager, so this is a plain JVM test -- returns
     * whatever [next] currently holds and records every call so a test can assert
     * resolve() was (or was not) invoked at all. */
    private class FakeAppLabelResolver(var next: AppLabel = AppLabel.Unknown) : AppLabelResolver {
        var callCount = 0
        var lastPackageNames: List<String>? = null

        override fun resolve(packageNames: List<String>): AppLabel {
            callCount++
            lastPackageNames = packageNames
            return next
        }
    }

    private fun entry(
        uid: Int,
        kind: UidKind,
        mAh: Double = 1.0,
        sharePct: Double = 10.0,
        packages: List<String> = emptyList(),
    ) = AppPowerEntry(uid = uid, mAh = mAh, sharePct = sharePct, kind = kind, packages = packages)

    @Test
    fun anAppEntryBecomesAnAppRowCarryingWhateverTheResolverReturned() {
        val resolver = FakeAppLabelResolver(next = AppLabel.Resolved("Camera", icon = null))
        val mapper = AppRowMapper(resolver)

        val row = mapper.toRow(entry(uid = 10106, kind = UidKind.App, packages = listOf("com.sec.android.app.camera")))

        val app = row as AppRow.App
        assertEquals(10106, app.uid)
        assertEquals(AppLabel.Resolved("Camera", icon = null), app.label)
        assertEquals(listOf("com.sec.android.app.camera"), resolver.lastPackageNames)
    }

    @Test
    fun aSystemEntryIsNeverSentToTheLabelResolver() {
        val resolver = FakeAppLabelResolver()
        val mapper = AppRowMapper(resolver)

        val row = mapper.toRow(
            entry(uid = 1000, kind = UidKind.System, packages = List(82) { "pkg$it" }),
        )

        val system = row as AppRow.System
        assertEquals(1000, system.uid)
        assertEquals(82, system.packageCount)
        assertEquals(0, resolver.callCount)
    }

    @Test
    fun aShellEntryIsNeverSentToTheLabelResolverEither() {
        val resolver = FakeAppLabelResolver()
        val mapper = AppRowMapper(resolver)

        val row = mapper.toRow(entry(uid = 2000, kind = UidKind.Shell, packages = listOf("com.android.shell")))

        assertTrue(row is AppRow.Shell)
        assertEquals(0, resolver.callCount)
    }

    @Test
    fun powerAndShareAreCarriedThroughUnchangedForEveryKind() {
        val mapper = AppRowMapper(FakeAppLabelResolver())

        val appRow = mapper.toRow(entry(uid = 10106, kind = UidKind.App, mAh = 15.6, sharePct = 3.5))
        val systemRow = mapper.toRow(entry(uid = 1000, kind = UidKind.System, mAh = 6.23, sharePct = 1.4))
        val shellRow = mapper.toRow(entry(uid = 2000, kind = UidKind.Shell, mAh = 422.0, sharePct = 94.7))

        assertEquals(15.6, appRow.mAh, 0.0001)
        assertEquals(3.5, appRow.sharePct, 0.0001)
        assertEquals(6.23, systemRow.mAh, 0.0001)
        assertEquals(422.0, shellRow.mAh, 0.0001)
        assertEquals(94.7, shellRow.sharePct, 0.0001)
    }
}

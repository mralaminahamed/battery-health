package com.alaminahamed.batteryhealth.data.settings

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether `PACKAGE_USAGE_STATS` is actually held right now, read from the platform.
 *
 * `checkOpNoThrow`, never `unsafeCheckOpNoThrow`: the latter needs API 29, and this app's
 * `minSdk` is 26 -- calling it on an older device is a `NoSuchMethodError`, not a graceful
 * fallback. `checkOpNoThrow` has existed since API 19 and covers this app's whole range.
 *
 * The mapping from the raw mode to a held/not-held answer lives in [AppOpPermissionState],
 * kept separate and free of any Android import specifically so it can be exercised on the
 * JVM; this class exists only to supply that function's two real-device inputs.
 */
@Singleton
class UsageAccessState @Inject constructor(@ApplicationContext private val context: Context) {

    fun isHeld(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        val selfPermissionGranted = context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
            PackageManager.PERMISSION_GRANTED
        return AppOpPermissionState.isHeld(mode, selfPermissionGranted)
    }
}

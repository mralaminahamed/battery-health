package com.alaminahamed.batteryhealth.ui.apps

import com.alaminahamed.batteryhealth.data.apps.AppCpuRow
import com.alaminahamed.batteryhealth.domain.Reading

/**
 * The Apps screen's whole content, now that per-app battery power ([Reading]-shaped mAh
 * rows from `dumpsys batterystats --checkin`, over the now-removed adb/root shell tier)
 * has no source left in this app at any price. What remains is [cpuRows]: per-uid CPU
 * time from `SystemHealthManager`, the one per-app figure this app can obtain without
 * privileged access -- see `UidCpuTimeSource`'s own doc for exactly what that needs and
 * why it is time, not power.
 */
data class AppsUiState(
    /**
     * Per-uid CPU time. [Reading.NeedsPrivilegedAccess] until `BATTERY_STATS` is held for
     * a uid other than this app's own -- which, per the owner's decision this app now
     * follows throughout, is never reachable through this app for a normal install. See
     * the task report for why this field is kept rather than deleted: `UidCpuTimeSource`
     * and its `Reading` semantics were not part of this task's named scope, and changing
     * them is a product decision left for the owner.
     */
    val cpuRows: Reading<List<AppCpuRow>> = Reading.NeedsPrivilegedAccess,
)

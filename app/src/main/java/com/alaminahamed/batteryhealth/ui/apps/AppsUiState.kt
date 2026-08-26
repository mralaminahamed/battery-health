package com.alaminahamed.batteryhealth.ui.apps

import com.alaminahamed.batteryhealth.data.apps.AppCpuRow
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading

/**
 * The Apps screen's whole content: two independent per-app views, neither of which needs
 * adb, root or a companion app.
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
    /**
     * A per-app battery-drain *estimate*, apportioned from this app's own measured
     * discharge by how long each package held the foreground -- see `EstimateWindow`,
     * `EstimatedAppDrain` and `EstimatedDrainReading` (`data.repo`) for the arithmetic and
     * the absence rules.
     *
     * [Reading.NeedsUsageAccess] until `PACKAGE_USAGE_STATS` is held -- an ordinary
     * Settings toggle, unlike [cpuRows]' now-unreachable `BATTERY_STATS`, and this app's
     * one real answer to the dead [cpuRows] tab: the same per-app question, answered a
     * way an ordinary install can actually unlock. [Reading.NotYetMeasured] once access is
     * held but there is not yet a usable discharge figure or any foreground time to split
     * it by. [Reading.Available] with
     * [Source.Inferred][com.alaminahamed.batteryhealth.domain.Source.Inferred] once both
     * exist. Never [Reading.Unsupported] or [Reading.NeedsPrivilegedAccess] by
     * construction -- kept reachable in every renderer that switches on this field anyway,
     * the same discipline every other [Reading] in this app is held to.
     *
     * Defaults to [Reading.NotYetMeasured] only as the cold-start placeholder before the
     * real flow first emits, the same convention `HealthUiState.measured` uses --
     * production always replaces it on the first `state` emission.
     */
    val estimatedDrainRows: Reading<EstimatedDrain> = Reading.NotYetMeasured,
)

package com.alaminahamed.batteryhealth.ui.apps

import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading

/**
 * The Apps screen's whole content: a single per-app view that needs nothing beyond a
 * normal Android permission flow -- no adb, no root, no companion app.
 *
 * This used to carry a second field, `cpuRows`, for per-uid CPU time read through
 * `SystemHealthManager`. That needs `BATTERY_STATS` for any uid but this app's own, which
 * this app has never declared and will never ask anyone to grant via a shell, so the field
 * could only ever resolve to [Reading.NeedsPrivilegedAccess] on a real install. It was
 * deleted along with `UidCpuTimeSource`, `AppCpuRow` and the section that rendered it,
 * leaving [estimatedDrainRows] as this screen's one per-app answer.
 */
data class AppsUiState(
    /**
     * A per-app battery-drain *estimate*, apportioned from this app's own measured
     * discharge by how long each package held the foreground -- see `EstimateWindow`,
     * `EstimatedAppDrain` and `EstimatedDrainReading` (`data.repo`) for the arithmetic and
     * the absence rules.
     *
     * [Reading.NeedsUsageAccess] until `PACKAGE_USAGE_STATS` is held -- an ordinary
     * Settings toggle, and this app's one route to per-app data at all now that the old
     * CPU-time tab's `BATTERY_STATS` is permanently unreachable. [Reading.NotYetMeasured]
     * once access is held but there is not yet a usable discharge figure or any foreground
     * time to split it by. [Reading.Available] with
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

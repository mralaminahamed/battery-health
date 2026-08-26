package com.alaminahamed.batteryhealth.data.apps

/**
 * One row of the screen-time-derived drain estimate. Keyed on a package name, not a uid --
 * usage stats report package names with no uid at all, unlike the now-deleted per-uid
 * CPU-time feature this screen used to also carry (`AppCpuRow`, keyed on a uid from
 * `PackageManager`'s own uid grouping, where a uid can own several packages). [label] is
 * resolved through the existing [AppLabelResolver], so `play`'s reduced package visibility
 * degrades it the same way it degrades every other label this app resolves. There is
 * deliberately no second label mechanism.
 */
data class EstimatedAppRow(
    val packageName: String,
    val label: AppLabel,
    val foregroundMs: Long,
    val estimatedMah: Double,
    val sharePct: Double,
)

/**
 * The estimate's whole result once usage access is held and a discharge figure is known:
 * every row plus the window and total the rows were split from, bundled together so the
 * screen's caption and its rows can never disagree about what period or what total they
 * describe -- the exact defect this port's own history shipped once (a caption naming a
 * 3-hour span above a row naming 10 hours of screen time) and had to fix.
 */
data class EstimatedDrain(
    val rows: List<EstimatedAppRow>,
    val totalMah: Double,
    val windowStartMs: Long,
    val windowEndMs: Long,
)

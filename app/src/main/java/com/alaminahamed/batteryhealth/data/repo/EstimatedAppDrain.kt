package com.alaminahamed.batteryhealth.data.repo

/**
 * Apportions a measured discharge across apps by foreground time.
 *
 * ## What this is, and what it is not
 *
 * `appMah = totalDischargeMah x (appForegroundMs / Σ allForegroundMs)`
 *
 * The single assumption is that drain is proportional to time on screen. It is a *useful*
 * assumption and a *false* one: background work, radio activity, GPS, and screen
 * brightness are not attributed at all, and the screen itself is usually the largest
 * single consumer, so it is credited to whatever happened to be in front of it. An app
 * that merely *was* on screen while the screen drained absorbs the screen's drain. This is
 * why the result carries
 * [Source.Inferred][com.alaminahamed.batteryhealth.domain.Source.Inferred] and is never
 * presented in the same shape as a real per-uid measurement.
 *
 * ## Why shares rather than absolute times
 *
 * `UsageStatsManager.queryAndAggregateUsageStats` returns day-aligned buckets that can
 * extend past the requested window -- its own documentation says as much. Normalising to
 * a *share* of total foreground time is what makes that not matter: the overshoot
 * inflates every package roughly uniformly and cancels in the ratio. This is the reason to
 * apportion by share, not a convenience, so do not "improve" this by multiplying absolute
 * foreground times against a per-hour drain rate.
 */
object EstimatedAppDrain {

    fun apportion(
        totalDischargeMah: Double,
        foregroundMsByPackage: Map<String, Long>,
    ): List<EstimatedAppDrainEntry> {
        // Packages under a minute of foreground time are dropped before the sum, not
        // after -- the same reasoning as the zero-time case below, extended to the
        // resolution the screen can actually show: Formatters.duration floors to whole
        // minutes, so 1 ms to 59 s all render as "0 m on screen". A row reading "0 m on
        // screen" beside a non-zero mAh figure asserts a drain the row itself shows no
        // visible screen time to justify -- self-contradictory, and the mirror image of
        // the zero-time guard's own reasoning: a sub-minute package was running, but not
        // long enough for this display to say how long.
        val active = foregroundMsByPackage.filterValues { it >= MIN_FOREGROUND_MS }
        val totalMs = active.values.sum()
        if (totalMs <= 0L) return emptyList()

        return active.map { (packageName, ms) ->
            val share = ms.toDouble() / totalMs
            EstimatedAppDrainEntry(
                packageName = packageName,
                foregroundMs = ms,
                estimatedMah = totalDischargeMah * share,
                sharePct = share * 100.0,
            )
        }.sortedByDescending { it.estimatedMah }
    }

    /**
     * One minute: the display's own resolution floor (`Formatters.duration` floors to
     * whole minutes), not an arbitrary noise cutoff. Below this a row's stated screen time
     * and its stated drain would disagree on whether the app did anything at all.
     */
    private const val MIN_FOREGROUND_MS = 60_000L
}

data class EstimatedAppDrainEntry(
    val packageName: String,
    val foregroundMs: Long,
    val estimatedMah: Double,
    val sharePct: Double,
)

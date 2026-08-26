package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity

/**
 * The pure half of the Apps screen's per-app estimate -- everything downstream of "the
 * samples have already been read from Room" -- kept free of `Context` and coroutines so
 * the window this object decides can be pinned by a JVM test rather than trusted to a
 * manual device check.
 *
 * That distinction matters because of a real defect this shape exists to prevent: querying
 * foreground time over the *requested* lookback while a caption states the *samples'* own,
 * usually much shorter, span produces a card reading "over the past 3 h 0 m" directly
 * above a row reading "10 h 2 m on screen" -- impossible on its face, because the
 * numerator (screen time) and the figure the caption names would describe two different
 * periods.
 *
 * [queryForegroundMs] stands in for `ForegroundUsageSource.query` so this stays testable
 * with no `Context`, no `UsageStatsManager`, and no coroutine -- a fake lambda that
 * records what bounds it was called with is enough to pin the window rule directly.
 */
object EstimateWindow {

    data class Result(
        val entries: List<EstimatedAppDrainEntry>,
        val totalDischargeMah: Double?,
        val windowStartMs: Long,
        val windowEndMs: Long,
    )

    /**
     * [requestedStartMs]/[requestedEndMs] are used only as the window reported when
     * [samples] is empty -- a case [WindowDischarge.mah] always answers `null` for, so
     * [Result.entries] is always empty then too and these fallback bounds are never
     * actually rendered. Every other case uses the *samples'* own first-to-last span, not
     * the requested one.
     *
     * [queryForegroundMs] is invoked at most once, and only when there is an actual
     * discharge figure to apportion ([WindowDischarge.mah] returned non-null) *and*
     * [usageAccessGranted]: querying `UsageStatsManager` for a result that can only ever
     * be discarded -- an unknown discharge rate, or access not held -- is a live Binder
     * call this app does not need to make.
     *
     * Deliberately queries the *whole* sample span in one call, not just the
     * sub-intervals [WindowDischarge.mah] actually counted toward the discharge total (it
     * excludes intervals plugged in throughout -- see that object's own doc). Summing
     * several narrower `queryAndAggregateUsageStats` calls back together to match those
     * sub-intervals exactly would multiply that method's own documented day-aligned
     * bucket overshoot by however many sub-intervals the window happened to contain -- a
     * worse and less predictable error than the one this function exists to fix. Instead
     * the caption states the mismatch in words (screen time covers the whole period
     * shown, including any charging; the mAh figure does not) -- see `AppsScreen`'s own
     * copy -- rather than pretending a second query mechanism removes it.
     */
    fun compute(
        samples: List<SampleEntity>,
        requestedStartMs: Long,
        requestedEndMs: Long,
        designCapacityMah: Int?,
        usageAccessGranted: Boolean,
        queryForegroundMs: (fromMs: Long, toMs: Long) -> Map<String, Long>,
    ): Result {
        val totalDischargeMah = WindowDischarge.mah(samples, designCapacityMah)
        val windowStartMs = samples.firstOrNull()?.timestampMs ?: requestedStartMs
        val windowEndMs = samples.lastOrNull()?.timestampMs ?: requestedEndMs

        val entries = if (totalDischargeMah != null && usageAccessGranted) {
            val foregroundMs = queryForegroundMs(windowStartMs, windowEndMs)
            EstimatedAppDrain.apportion(totalDischargeMah, foregroundMs)
        } else {
            emptyList()
        }

        return Result(entries, totalDischargeMah, windowStartMs, windowEndMs)
    }
}

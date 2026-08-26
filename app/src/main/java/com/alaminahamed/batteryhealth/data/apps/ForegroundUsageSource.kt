package com.alaminahamed.batteryhealth.data.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-package foreground time over a window, for
 * [EstimatedAppDrain][com.alaminahamed.batteryhealth.data.repo.EstimatedAppDrain] to
 * apportion a measured discharge by. The one caller of
 * `UsageStatsManager.queryAndAggregateUsageStats` in this app.
 *
 * Needs no [com.alaminahamed.batteryhealth.data.settings.UsageAccessState] check of its
 * own: `queryAndAggregateUsageStats` already returns an empty map, not an exception, when
 * this app does not hold usage access. [UsageAccessState] exists anyway because the *UI*
 * needs to tell "access not held" apart from "access held, but genuinely nothing to show"
 * -- see
 * [EstimatedDrainReading][com.alaminahamed.batteryhealth.data.repo.EstimatedDrainReading],
 * a distinction this class has no way to make from an empty map alone. `runCatching` is
 * still worth keeping: this is a live Binder call into a system service this app does not
 * control, and a call that fails in some other way (a dead system process, an OEM quirk)
 * must degrade to "nothing usable" rather than crash the collecting flow.
 */
@Singleton
class ForegroundUsageSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * `[fromMs, toMs)`, matching [UsageStatsManager]'s own convention. Excludes this app's
     * own package -- its own foreground time is not a finding about the user, and leaving
     * it in would let this app's own usage compete for a share of a drain figure that is
     * supposed to describe everything else running on the device.
     *
     * `queryAndAggregateUsageStats` returns day-aligned buckets that can overshoot the
     * requested window; that is
     * [EstimatedAppDrain][com.alaminahamed.batteryhealth.data.repo.EstimatedAppDrain]'s
     * problem to normalise away by apportioning a *share*, not this method's to correct,
     * so the raw totals are returned unchanged.
     */
    fun query(fromMs: Long, toMs: Long): Map<String, Long> {
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val ownPackage = context.packageName
        val stats = runCatching {
            usageStatsManager.queryAndAggregateUsageStats(fromMs, toMs)
        }.getOrNull() ?: return emptyMap()

        return stats
            .filterKeys { it != ownPackage }
            .mapValues { (_, usageStats) -> usageStats.totalTimeInForeground }
    }
}

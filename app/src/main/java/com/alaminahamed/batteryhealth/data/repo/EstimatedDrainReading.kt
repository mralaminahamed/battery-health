package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.apps.AppLabelResolver
import com.alaminahamed.batteryhealth.data.apps.EstimatedAppRow
import com.alaminahamed.batteryhealth.data.apps.EstimatedDrain
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * Turns one [EstimateWindow.Result] into the [Reading] the Apps screen actually renders --
 * the absence classification `AppsViewModel` would otherwise have to inline.
 *
 * Pulled out as its own pure function, like [EstimateWindow] itself, because the *order*
 * [usageAccessGranted] is checked in relative to [EstimateWindow.Result.entries]'
 * emptiness is exactly the kind of predicate this codebase has shipped backwards before.
 * Checking emptiness first would answer [Reading.NotYetMeasured] for a device that has
 * never granted usage access at all, which tells the user the wrong thing to do next --
 * there is nothing to "wait for" until they grant it. [usageAccessGranted] is therefore
 * checked first and dominates, even for the contrived case of a non-empty [entries] with
 * access not held (which [EstimateWindow.compute] never actually produces, since it never
 * queries foreground time without access -- but this function does not lean on that
 * upstream guarantee to skip its own check).
 */
object EstimatedDrainReading {

    fun from(
        usageAccessGranted: Boolean,
        result: EstimateWindow.Result,
        labelResolver: AppLabelResolver,
    ): Reading<EstimatedDrain> {
        if (!usageAccessGranted) return Reading.NeedsUsageAccess
        if (result.entries.isEmpty()) return Reading.NotYetMeasured

        // EstimateWindow.compute only ever produces a non-empty entries list when it also
        // had a real totalDischargeMah to apportion -- see that object's own doc. A null
        // here would mean that invariant broke somewhere upstream, which this makes loud
        // rather than silently rendering a false "0.00 mAh" that looks like a real, if
        // tiny, measurement.
        val totalMah = checkNotNull(result.totalDischargeMah) {
            "EstimateWindow.compute returned non-empty entries with no totalDischargeMah"
        }

        val rows = result.entries.map { entry ->
            EstimatedAppRow(
                packageName = entry.packageName,
                label = labelResolver.resolve(listOf(entry.packageName)),
                foregroundMs = entry.foregroundMs,
                estimatedMah = entry.estimatedMah,
                sharePct = entry.sharePct,
            )
        }

        return Reading.Available(
            EstimatedDrain(
                rows = rows,
                totalMah = totalMah,
                windowStartMs = result.windowStartMs,
                windowEndMs = result.windowEndMs,
            ),
            Source.Inferred,
        )
    }
}

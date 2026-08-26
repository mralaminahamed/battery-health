package com.alaminahamed.batteryhealth.data.apps

import com.alaminahamed.batteryhealth.domain.AppBucket
import com.alaminahamed.batteryhealth.domain.RankedCpu
import com.alaminahamed.batteryhealth.domain.UidKind
import javax.inject.Inject

/**
 * One CPU-time row, with its identity resolved.
 *
 * Separate from [AppRow] because the two carry different quantities -- that one holds mAh
 * the platform attributed, this holds milliseconds -- and a shared type would let the
 * screen render one under the other's heading. Identity resolution is shared, though:
 * both go through [AppLabelResolver], so a name and icon shown here mean exactly what
 * they mean there.
 */
data class AppCpuRow(
    val uid: Int,
    val kind: UidKind,
    val bucket: AppBucket,
    val label: AppLabel,
    val totalCpuMs: Long,
    val userCpuMs: Long,
    val systemCpuMs: Long,
    val sharePct: Double,
)

/**
 * Resolves each ranked uid's label and icon.
 *
 * Labels are attempted only for [UidKind.App], the same rule [AppRowMapper] follows: a
 * shared system uid can own dozens of unrelated packages, so resolving one of them would
 * misrepresent the row as being about that package rather than the uid.
 */
class AppCpuRowMapper @Inject constructor(
    private val labelResolver: AppLabelResolver,
) {
    fun toRow(ranked: RankedCpu): AppCpuRow {
        val entry = ranked.entry
        return AppCpuRow(
            uid = entry.uid,
            kind = entry.kind,
            // Resolved for any uid that owns exactly one package, whatever its kind.
            //
            // AppRowMapper resolves only App rows, and for its data that is right: the
            // uid dictionary in a `batterystats` dump routinely attributes dozens of
            // unrelated packages to a shared system uid, so picking one would present the
            // row as being about that package rather than the uid.
            //
            // These rows come from PackageManager's own uid grouping instead, where a
            // single-package uid is unambiguous -- the icon and name genuinely belong to
            // the only thing that uid is. A uid owning several still falls back, because
            // there the original objection applies exactly as before.
            label = when {
                entry.packages.size == 1 -> labelResolver.resolve(entry.packages)
                entry.kind == UidKind.App -> labelResolver.resolve(entry.packages)
                else -> entry.packages.firstOrNull()
                    ?.let { AppLabel.PackageNameOnly(it) }
                    ?: AppLabel.Unknown
            },
            bucket = entry.bucket,
            totalCpuMs = entry.totalCpuMs,
            userCpuMs = entry.userCpuMs,
            systemCpuMs = entry.systemCpuMs,
            sharePct = ranked.sharePct,
        )
    }
}

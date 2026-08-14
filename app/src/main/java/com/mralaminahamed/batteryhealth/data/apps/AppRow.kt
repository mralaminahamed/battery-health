package com.mralaminahamed.batteryhealth.data.apps

import com.mralaminahamed.batteryhealth.domain.AppPowerEntry
import com.mralaminahamed.batteryhealth.domain.UidKind
import javax.inject.Inject

/**
 * The Apps screen's own per-row model: one variant per [UidKind], not one class with a
 * `kind` field and optional properties for whichever fields happen to apply. A
 * [UidKind.App] row's [App.label] genuinely has no equivalent for a [UidKind.System] row
 * (label resolution is not even attempted for it -- see [AppRowMapper]'s own doc for
 * why), and a nullable-field shape would let a future change accidentally read one kind's
 * field off another kind's row with no compiler help catching it. This mirrors why
 * [Reading][com.mralaminahamed.batteryhealth.domain.Reading] itself is a sealed type with
 * distinct absences rather than one class with a nullable "reason".
 */
sealed interface AppRow {
    val uid: Int
    val mAh: Double
    val sharePct: Double

    /** A user-installed app. [label] is whatever [AppLabelResolver] could confirm for
     * it -- see [AppLabel]'s own doc for why a raw package name and a confirmed label are
     * kept as visibly distinct cases here, all the way to the UI. */
    data class App(
        override val uid: Int,
        override val mAh: Double,
        override val sharePct: Double,
        val label: AppLabel,
    ) : AppRow

    /** A platform/system uid, root included. [packageCount] is shown instead of any one
     * resolved label -- a shared uid like `1000` can own dozens of packages, and picking
     * one of them to resolve and display would misrepresent the row as being about that
     * one package specifically rather than the uid as a whole. */
    data class System(
        override val uid: Int,
        override val mAh: Double,
        override val sharePct: Double,
        val packageCount: Int,
    ) : AppRow

    /** uid `2000` specifically: the adb/USB-debugging shell. No label resolution and no
     * package count -- its identity is the uid itself, not anything `PackageManager`
     * could add to it. */
    data class Shell(
        override val uid: Int,
        override val mAh: Double,
        override val sharePct: Double,
    ) : AppRow
}

/**
 * Turns one [AppPowerEntry] into the [AppRow] variant matching its [UidKind]. The
 * `when` below is exhaustive over [UidKind] with no `else`, so a fourth kind added later
 * fails to compile here rather than silently falling into whichever branch happens to be
 * last -- the same preemption hazard this codebase's own history keeps warning about.
 *
 * [AppLabelResolver.resolve] is called *only* for [UidKind.App] entries, never for
 * [UidKind.System] or [UidKind.Shell]: those two kinds are not apps, and resolving "a"
 * label for a uid that (for System) may own dozens of unrelated packages, or (for Shell)
 * owns exactly one deliberately-uninteresting package (`com.android.shell`), would invite
 * exactly the "one general rule applied to cases that needed different ones" mistake this
 * whole feature was built to avoid.
 */
class AppRowMapper @Inject constructor(
    private val labelResolver: AppLabelResolver,
) {
    fun toRow(entry: AppPowerEntry): AppRow = when (entry.kind) {
        UidKind.App -> AppRow.App(
            uid = entry.uid,
            mAh = entry.mAh,
            sharePct = entry.sharePct,
            label = labelResolver.resolve(entry.packages),
        )

        UidKind.System -> AppRow.System(
            uid = entry.uid,
            mAh = entry.mAh,
            sharePct = entry.sharePct,
            packageCount = entry.packages.size,
        )

        UidKind.Shell -> AppRow.Shell(
            uid = entry.uid,
            mAh = entry.mAh,
            sharePct = entry.sharePct,
        )
    }
}

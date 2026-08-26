package com.alaminahamed.batteryhealth.domain

/**
 * How long one uid has held the CPU, split the way the platform splits it.
 *
 * Deliberately **not** expressed in mAh, and deliberately not folded into
 * [AppPowerEntry]. Those two describe different quantities: `AppPowerEntry.mAh` is charge
 * the platform attributed to a uid, and this is time. Reusing that field would let the UI
 * render CPU milliseconds under a heading that says milliamp-hours, which is the precise
 * shape of misrepresentation this app exists to avoid.
 *
 * ## Why this is time and not power
 *
 * `SystemHealthManager.takeUidSnapshot` is the only public API that reports per-uid usage,
 * and on real hardware its power buckets are empty: `MEASUREMENT_CPU_POWER_MAMS` reads 0
 * for every uid and the wifi, mobile and bluetooth equivalents are absent entirely. The
 * CPU *time* buckets are populated and genuinely differentiated -- one uid read 447180ms
 * of user time against another's 27ms on the same device at the same moment.
 *
 * Android's own "estimated power use (mAh)" is those same times multiplied by coefficients
 * from `power_profile.xml`. This app could do that arithmetic too, and chooses not to
 * without saying so: it would be a model presented beside measurements, in an app whose
 * entire position is that the two must never look alike.
 */
data class AppCpuTime(
    val uid: Int,
    val userCpuMs: Long,
    val systemCpuMs: Long,
    val kind: UidKind,
    val packages: List<String>,
    val bucket: AppBucket,
) {
    /**
     * User plus system time.
     *
     * Both halves are kept on the type rather than only this sum: they answer different
     * questions -- user time is the app's own code, system time is the kernel working on
     * its behalf -- and a row that is almost all system time means something different
     * from one that is almost all user time.
     */
    val totalCpuMs: Long get() = userCpuMs + systemCpuMs
}

/**
 * Orders uids by CPU time and works out each one's share.
 *
 * Pure, so the ordering and the share arithmetic are provable without a device.
 */
object AppCpuRanking {

    /**
     * Ranked highest-first, with each entry's percentage of the total.
     *
     * Zero-time uids are dropped. Android reports a row for every uid it has ever seen,
     * most of which have done nothing measurable; listing them would bury the handful that
     * matter under dozens of zeroes and imply the app knows something about each.
     *
     * Share is of the total *this app could see*, not of the device's whole consumption --
     * see [rankedShare]. Without `QUERY_ALL_PACKAGES` the visible uid set is a fraction of
     * what is installed, so a share computed here can only ever describe the rows shown.
     */
    fun ranked(entries: List<AppCpuTime>): List<RankedCpu> {
        val active = entries.filter { it.totalCpuMs > 0L }
        val total = active.sumOf { it.totalCpuMs }
        if (total <= 0L) return emptyList()
        return active
            .sortedByDescending { it.totalCpuMs }
            .map { RankedCpu(it, rankedShare(it.totalCpuMs, total)) }
    }

    /**
     * A percentage of the visible total, never rounded to hide a small value: an entry
     * that genuinely accounts for 0.4% reports 0.4, not 0. Callers format for display.
     */
    private fun rankedShare(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else part.toDouble() * 100.0 / total.toDouble()
}

/**
 * @property sharePct percentage of the CPU time across the rows this app could see, which
 *   is not the same as a share of the device's total -- see [AppCpuRanking.ranked].
 */
data class RankedCpu(val entry: AppCpuTime, val sharePct: Double)

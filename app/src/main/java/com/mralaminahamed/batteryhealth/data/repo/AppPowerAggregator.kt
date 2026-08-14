package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.data.privileged.ParsedBatteryStats
import com.mralaminahamed.batteryhealth.domain.AppPowerEntry
import com.mralaminahamed.batteryhealth.domain.UidKind

/**
 * Reduces a parsed `dumpsys batterystats --checkin` capture into the rows the Apps screen
 * renders, sorted by descending power. Pure and JVM-tested against hand-computed
 * expectations, independent of `BatteryStatsCheckinParser`'s own tests -- this is a
 * second, separate reduction step, not more parsing.
 *
 * The concrete example [UidKind] exists to prevent: on the real fixture this was built
 * against, uid `2000` (the adb/USB-debugging shell) is the single largest consumer at 422
 * mAh -- roughly 94% of everything every uid on the device drew combined -- purely because
 * of the testing done on that device. A single "this uid used N mAh, here's an app row for
 * it" rule, applied uniformly, would present "adb shell -- 422 mAh, 94% of total" as if it
 * were a real app the user could act on (uninstall, restrict background usage, ...), which
 * is actively misleading; a rule that instead only ever showed [UidKind.App] rows would
 * silently drop the single largest real consumer on that device, which is its own kind of
 * dishonesty this app's whole design exists to avoid. Keeping all three [UidKind]s, each
 * presented for what it actually is (see `AppRowMapper`/`AppsScreen`), is the resolution:
 * nothing is hidden, and nothing is misrepresented as something it is not.
 *
 * [AppPowerEntry.sharePct] is computed against the sum of *every* uid's power in the dump
 * -- [UidKind.App], [UidKind.System] and [UidKind.Shell] alike, not just app uids --
 * because that sum is the actual total `batterystats` accounted for. Computing a
 * percentage against a narrower "apps only" denominator would silently inflate every
 * app's apparent share whenever a device (like the one this was built against) has a
 * large non-app consumer.
 */
object AppPowerAggregator {

    fun aggregate(stats: ParsedBatteryStats): List<AppPowerEntry> {
        val totalMah = stats.uidPowerMah.values.sum()
        return stats.uidPowerMah.entries
            .map { (uid, mAh) ->
                AppPowerEntry(
                    uid = uid,
                    mAh = mAh,
                    sharePct = if (totalMah > 0.0) (mAh / totalMah) * 100.0 else 0.0,
                    kind = UidKind.of(uid),
                    packages = stats.uidPackages[uid].orEmpty(),
                )
            }
            .sortedByDescending { it.mAh }
    }
}

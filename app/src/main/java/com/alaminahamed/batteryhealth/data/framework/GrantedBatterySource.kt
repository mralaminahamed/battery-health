package com.alaminahamed.batteryhealth.data.framework

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import com.alaminahamed.batteryhealth.data.vendor.discovery.BatteryPropertyId
import com.alaminahamed.batteryhealth.data.vendor.discovery.FrameworkStateOfHealth
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The readings that become available once `BATTERY_STATS` is granted, taken straight from
 * `BatteryManager` with no shell involved.
 *
 * `BATTERY_STATS` is `signature|privileged|development`. A user can never grant it by
 * tapping something, but the `development` flag means adb can:
 *
 * ```
 * adb shell pm grant com.alaminahamed.batteryhealth android.permission.BATTERY_STATS
 * ```
 *
 * Verified on an SM-S948B: before the grant, six properties reported `SecurityException`;
 * after it, state of health read 100 and the manufacturing and first-usage dates returned
 * real values.
 *
 * ## Why this is better than the adb tier it sits beside
 *
 * The privileged tier needs `adb tcpip 5555` re-run after **every reboot**, then an
 * in-app RSA handshake over a loopback socket, and it raises a system "Allow USB
 * debugging?" dialog. A granted permission is one command, ever: Android persists it in
 * package state, so it survives reboots and app updates. Nothing is required at runtime
 * beyond calling the ordinary public API.
 *
 * The adb tier is still the only route to Samsung's cycle count and Battery Protect
 * threshold, neither of which has a `BATTERY_PROPERTY` id, so this does not replace it.
 *
 * ## Nothing here is a hidden-API access
 *
 * `getIntProperty`/`getLongProperty` are public methods and the ids are integer literals.
 * No reflection, and the non-SDK interface restrictions are not engaged. The constants are
 * absent from the public SDK, which means this app cannot *name* them -- not that it
 * cannot pass them.
 */
@Singleton
class GrantedBatterySource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val batteryManager: BatteryManager,
) {
    /**
     * Whether the platform has actually granted the permission.
     *
     * Checked rather than inferred from a successful read: it lets the UI say "run this
     * one command" instead of leaving every row reading "needs privileged access" with no
     * explanation of which setup would fix it. The reads below are still individually
     * guarded, because being granted the permission does not oblige a device to have any
     * particular property.
     */
    val isGranted: Boolean
        get() = context.checkSelfPermission(Manifest.permission.BATTERY_STATS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * State of health as a percentage.
     *
     * Delegates to [FrameworkStateOfHealth] rather than repeating its rules: the
     * plausibility bound and the denial-versus-absence distinction already live there and
     * are tested there. A value outside 1..100 is refused rather than clamped.
     */
    fun stateOfHealthPct(): Reading<Int> =
        FrameworkStateOfHealth.read { id -> batteryManager.getIntProperty(id) }

    fun manufacturingDateEpochDay(): Reading<Long> =
        dateProperty(BatteryPropertyId.ManufacturingDate)

    fun firstUsageDateEpochDay(): Reading<Long> = dateProperty(BatteryPropertyId.FirstUsageDate)

    /**
     * Reads one epoch-second date property and converts it to a calendar day.
     *
     * `getLongProperty`, not `getIntProperty`: these are seconds since the epoch and
     * passed 2038 they no longer fit in an `Int`. Reading them narrowly would not fail
     * loudly -- it would wrap into a different, plausible-looking date, which is the
     * quietest possible way to be wrong about a battery's age.
     *
     * A denial is [Reading.NeedsPrivilegedAccess] rather than [Reading.Unsupported]: the
     * platform has the value and is withholding it, and telling the user it can be
     * unlocked is accurate. Anything else -- a sentinel, an implausible date, a device
     * that has never heard of the property -- is [Reading.Unsupported], because no amount
     * of unlocking will change it.
     */
    private fun dateProperty(property: BatteryPropertyId): Reading<Long> {
        val raw = try {
            batteryManager.getLongProperty(property.id)
        } catch (e: SecurityException) {
            return Reading.NeedsPrivilegedAccess
        } catch (t: Throwable) {
            return Reading.Unsupported
        }
        val day = GrantedBatteryDates.toEpochDay(
            rawSeconds = raw,
            zone = ZoneId.systemDefault(),
            todayEpochDay = LocalDate.now().toEpochDay(),
        )
        return day?.let { Reading.Available(it, Source.Framework) } ?: Reading.Unsupported
    }
}

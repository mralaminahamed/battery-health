package com.alaminahamed.batteryhealth.data.vendor.discovery

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * State of health read straight from the framework, with no privileged transport.
 *
 * This exists because AOSP's own enforcement has an exception for exactly one property.
 * `BatteryService.BatteryPropertiesRegistrar.getProperty` gates the restricted ids on
 * `BATTERY_STATS`, but state of health is checked first:
 *
 * ```java
 * case BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH:
 *     if (stateOfHealthPublic()) {
 *         break;
 *     }
 *     // falls through to enforceCallingPermission(BATTERY_STATS)
 * ```
 *
 * When that platform flag is on, an ordinary app reads the vendor's own state of health
 * with no permission at all. When it is off, Java's switch fall-through carries the call
 * into the same `BATTERY_STATS` check as everything else and it throws.
 *
 * So this is a per-device, per-build question with a real yes case, and asking is the only
 * way to answer it. That is a correction to an assumption this codebase held: the previous
 * position was that state of health is privileged-only on all hardware at all API levels,
 * which was true when it was written and is not true on flag-enabled builds.
 *
 * ## Nothing here is a hidden-API access
 *
 * `BatteryManager.getIntProperty(int)` is public. The id `10` is an integer literal, not a
 * reference to a hidden field, so no reflection is involved and the non-SDK interface
 * restrictions that have applied since Android 9 are not engaged. The constant is absent
 * from the public SDK, which means this app cannot name it — not that it cannot pass it.
 */
object FrameworkStateOfHealth {

    /**
     * The percentage, or the reason there isn't one.
     *
     * [read] should call `BatteryManager.getIntProperty` and is expected to throw
     * `SecurityException` when the platform refuses. Injected rather than taking a
     * `BatteryManager` so the mapping below is provable on the JVM.
     *
     * The three absences are deliberately different values, because they mean different
     * things to a user:
     *
     * - [Reading.NeedsPrivilegedAccess] on a denial. The platform has the figure and is
     *   withholding it, and the privileged tier can still fetch the vendor equivalent, so
     *   telling the user it might be unlocked is accurate.
     * - [Reading.Unsupported] on a sentinel or an implausible value. The platform answered
     *   and had nothing, and no amount of unlocking changes that.
     * - [Reading.Unsupported] on a failure too. A `NoSuchMethodError` from a platform that
     *   predates the property is a permanent no for that device, not something to retry.
     *
     * A value outside 1..100 is refused rather than clamped. A state of health of 0 would
     * mean a dead cell and 137 would mean nothing at all; either is a stub or a
     * misinterpreted register, and reporting it as a health percentage would be exactly
     * the invented-number failure this app exists to avoid.
     */
    fun read(read: (Int) -> Int): Reading<Int> {
        val raw = try {
            read(BatteryPropertyId.StateOfHealth.id)
        } catch (e: SecurityException) {
            return Reading.NeedsPrivilegedAccess
        } catch (t: Throwable) {
            return Reading.Unsupported
        }
        return if (raw in PLAUSIBLE_PCT) {
            Reading.Available(raw, Source.Framework)
        } else {
            Reading.Unsupported
        }
    }

    /**
     * 1..100. Zero is excluded because a running phone does not have a zero-capacity
     * battery, so a zero here is a stub rather than a reading. Values above 100 are
     * excluded because a cell above its rated capacity is not what this property means,
     * whatever a particular fuel gauge decides to report.
     */
    val PLAUSIBLE_PCT = 1..100
}

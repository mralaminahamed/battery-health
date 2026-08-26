package com.alaminahamed.batteryhealth.data.vendor

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source

/**
 * Battery Protect, read from `Settings.Global` with no permission at all.
 *
 * Samsung publishes the switch's state as `protect_battery` in the global settings table.
 * Reading `Settings.Global` requires nothing -- no runtime permission, no privileged
 * shell, no setup -- so on a Samsung device this is one of the app's headline vendor
 * readings available to a user who has done nothing but install it.
 *
 * Confirmed rather than assumed: the key read `1` on an SM-S948B at a moment when the
 * app's privileged tier, going through `dumpsys` over a completely separate path,
 * independently reported Battery Protect as on.
 *
 * ## What this deliberately does not read
 *
 * `battery_protection_threshold` *is* the enforced charge limit, and getting to that took
 * being wrong first.
 *
 * A privileged `dumpsys battery` reports `mProtectionThreshold: 80` alongside
 * `mMaximumProtectionThreshold: 95`, and this app originally rendered the first as the
 * charge limit. Samsung's own Battery protection screen, on the same device at the same
 * moment, showed Maximum mode selected with its slider on 95% and the words "Your battery
 * will stop charging when it reaches 95%".
 *
 * So `mProtectionThreshold` is the *floor* of that slider (its stops are 80/85/90/95), not
 * the value chosen. `mMaximumProtectionThreshold` is the chosen value, and this settings
 * key mirrors it. The app was showing 80% to a user whose phone stops at 95%.
 *
 * The lesson is not about these two field names. Both readings were real, both came from
 * the platform, and the wrong one was the more plausibly-named. Only the vendor's own UI
 * could say which described the thing a user sees, and it was never consulted until the
 * user pointed at the discrepancy.
 */
object VendorBatteryProtect {

    /** The `Settings.Global` key. */
    const val KEY = "protect_battery"

    /**
     * Turns the raw setting into a reading, or reports that this device has nothing to say.
     *
     * Pure so every rule is provable without a device.
     *
     * The claim is deliberately minimal: zero is off, any other non-negative value is on.
     * The neighbouring keys (`battery_protection_default_value=3`,
     * `adaptive_protection_current_switch_value=1`) show this family encodes modes, so
     * `protect_battery` may itself be a mode rather than a boolean -- and this app has no
     * evidence for which number names which mode. "Some protection is active" is what the
     * evidence supports, so it is all that is claimed.
     *
     * Negative values are not "on". `prev_protect_battery` reads `-1` on this hardware, so
     * -1 is a real "no value recorded" marker in this key family; letting it fall through a
     * bare `!= 0` test would report protection as enabled because of a sentinel, which is
     * precisely the accidental-truth-by-arithmetic this codebase keeps catching in itself.
     *
     * A missing key is [Reading.Unsupported], never `false`. Every non-Samsung device
     * lands there, and answering "off" would invent a fact about hardware that has no such
     * feature.
     */
    fun interpret(raw: String?): Reading<Boolean> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Reading.Unsupported
        val value = text.toIntOrNull() ?: return Reading.Unsupported
        if (value < 0) return Reading.Unsupported
        return Reading.Available(value != 0, Source.Vendor)
    }
}

/**
 * The charge percentage Battery Protect stops at, from `Settings.Global`.
 *
 * Needs no permission, so on a Samsung device this is available with no setup at all --
 * and it is more correct than the privileged tier's `mProtectionThreshold`, which is the
 * slider's floor rather than its value. See [VendorBatteryProtect] for how that was
 * established.
 *
 * ## What is verified, and what is not
 *
 * Verified: with Battery protection **on** and **Maximum** mode selected, this key equals
 * the percentage Samsung's own screen says charging stops at.
 *
 * Not verified: Basic mode. Samsung describes Basic as charging to 100% and resuming at
 * 95%, so the number a user would call "the limit" there is arguably 100, not this key.
 * Until someone reads these keys with Basic selected, [interpretThreshold] answers only
 * for the case that has been checked -- see [VendorBatteryProtectMode].
 */
object VendorBatteryProtectThreshold {

    /** The `Settings.Global` key. */
    const val KEY = "battery_protection_threshold"

    /**
     * The slider's stops on the device this was verified against. A value outside them is
     * not necessarily wrong, but it is not something this app has ever seen, so the bound
     * below stays wide enough to admit any percentage while still rejecting a sentinel.
     */
    val PLAUSIBLE_PCT = 50..100

    /**
     * The limit, or [Reading.Unsupported] when this device publishes nothing usable.
     *
     * Rejects the -1 that `prev_protect_battery` shows this key family uses for "no value",
     * and anything outside a plausible percentage. A charge limit is a number a user will
     * compare against their own Settings screen, so a wrong one is immediately visible as
     * a lie -- which is exactly what happened with the 80.
     */
    fun interpretThreshold(raw: String?): Reading<Int> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Reading.Unsupported
        val value = text.toIntOrNull() ?: return Reading.Unsupported
        return if (value in PLAUSIBLE_PCT) {
            Reading.Available(value, Source.Vendor)
        } else {
            Reading.Unsupported
        }
    }
}

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
 * The neighbouring `battery_protection_threshold` is *not* used as a charge limit. It read
 * `95` on the same device at the same moment that the privileged `dumpsys` reported the
 * active limit as 80%. Those two cannot both describe the limit in force, so at least one
 * means something else -- most plausibly these hold per-mode defaults while One UI's
 * Basic/Adaptive/Maximum selection lives elsewhere. Rendering 95% as "your charge limit"
 * would be a confident, plausible, wrong number, which is the specific failure this app
 * exists not to produce. The threshold stays a privileged-tier reading until someone
 * establishes the mapping by watching the keys change as the mode changes.
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

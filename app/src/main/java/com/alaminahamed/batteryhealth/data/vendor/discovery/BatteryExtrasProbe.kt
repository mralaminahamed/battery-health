package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * Enumerates the keys an `ACTION_BATTERY_CHANGED` broadcast actually carries.
 *
 * This is the one channel that can find something nobody documented. The extras are a
 * plain `Bundle` and OEMs add their own keys to it freely; reading only the constants
 * named in `BatteryManager` finds exactly the keys AOSP defines and nothing a vendor put
 * there. Listing every key instead is how a device gets to report what it has rather than
 * being asked what AOSP expects.
 *
 * No permission is involved. The broadcast is sticky and readable by any app, which is
 * what makes this worth doing at all.
 *
 * The keys and their classification are handled here as pure data so the interesting part
 * — deciding what is a documented key, what is a vendor addition, and what is worth
 * surfacing — is JVM-testable without a device.
 */
object BatteryExtrasProbe {

    /**
     * Extra keys AOSP defines, verified by disassembling the API 37 `android.jar`.
     *
     * Note the two shapes: the older keys are bare words (`level`, `scale`) while newer
     * ones are namespaced (`android.os.extra.CYCLE_COUNT`). Both forms are AOSP's, so
     * neither shape can be used as a test for whether a key is a vendor addition — which
     * is exactly the shortcut [isVendorSpecific] must not take.
     */
    val documentedKeys: Set<String> = setOf(
        "health",
        "icon-small",
        "level",
        "plugged",
        "present",
        "scale",
        "status",
        "technology",
        "temperature",
        "voltage",
        "battery_low",
        "android.os.extra.CAPACITY_LEVEL",
        "android.os.extra.CHARGING_STATUS",
        "android.os.extra.CYCLE_COUNT",
        // Present on many builds and carried in the same broadcast, though not declared
        // as a BatteryManager constant. Listed as documented rather than vendor-specific
        // because it is AOSP's, and calling it a vendor addition would be wrong.
        "seq",
        "max_charging_current",
        "max_charging_voltage",
        "charge_counter",
        "invalid_charger",
    )

    /**
     * Whether [key] is something this device added rather than something AOSP defines.
     *
     * Membership in [documentedKeys], nothing cleverer. A prefix rule on
     * `android.os.extra.` would misclassify every bare AOSP key like `level` as a vendor
     * addition, and a rule based on vendor name prefixes would miss the many OEM keys that
     * are named plainly.
     *
     * Being vendor-specific says nothing about whether a key is useful. It says only that
     * its meaning is not documented anywhere this project can cite, so any value read from
     * it must be reported as what it is — an undocumented number from this device — and
     * never promoted into a metric the app presents as understood.
     */
    fun isVendorSpecific(key: String): Boolean = key !in documentedKeys

    /**
     * Turns a set of key/value pairs pulled from the broadcast into probe results.
     *
     * [extras] arrives as strings because a `Bundle` holds mixed types and the report
     * records what was seen rather than a parsed interpretation of it. Interpretation is a
     * separate concern, and one that cannot be done at all for a key whose meaning is
     * unknown.
     *
     * A null value is recorded as [ProbeOutcome.Absent] rather than dropped: a key that is
     * present but empty is a different finding from a key that is not there, and losing
     * that distinction would make two different devices look identical in the report.
     */
    fun resultsFrom(extras: Map<String, String?>): List<ProbeResult> =
        extras.entries
            .sortedBy { it.key }
            .map { (key, value) ->
                ProbeResult(
                    channel = ProbeChannel.BroadcastExtra,
                    key = key,
                    outcome = value?.takeIf { it.isNotBlank() }
                        ?.let { ProbeOutcome.Value(it) }
                        ?: ProbeOutcome.Absent,
                )
            }
}

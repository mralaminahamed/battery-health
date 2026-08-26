package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.Vendor

/**
 * How one table row recognises a device.
 *
 * Two shapes because vendors number their phones two different ways, and forcing either
 * one on the other loses devices — see [com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity]
 * for the evidence.
 */
sealed interface DeviceMatch {

    /**
     * Matches when `Build.MODEL` starts with [prefix]. Right for vendors whose regional
     * variants share a stem: `SM-S938B`, `SM-S938U` and `SM-S938W` are one phone, and
     * `SM-S938` covers all three.
     */
    data class ModelPrefix(val prefix: String) : DeviceMatch

    /**
     * Matches when `Build.DEVICE` equals [code] exactly. Right for vendors whose model
     * codes vary by region while the internal board name does not — one `OP5D55L1` row
     * covers four OnePlus 13 model codes.
     *
     * Exact rather than prefix: board names are short and share stems freely
     * (`OP5D55L1` vs `OP5D5BL1`), so a prefix rule here would match neighbours.
     */
    data class DeviceCode(val code: String) : DeviceMatch
}

/**
 * Where a capacity figure came from. Two sources are required by construction, not by
 * convention, because a single source is how a wrong figure gets in: spec aggregators
 * copy each other, and a typo propagates intact. Requiring a second, independent
 * confirmation is the only cheap check available without holding the phone.
 *
 * A wrong entry here is worse than a missing one. A missing entry shows the user "not
 * available" and offers the override; a wrong entry silently scales every health
 * percentage this app will ever show for that model, and looks entirely plausible while
 * doing it.
 *
 * @property primary the main citation — normally the manufacturer's own specification.
 * @property corroborating an independent confirmation. Not a second page from the same
 *   publisher, and not a spec aggregator quoting the primary; the point is independence,
 *   which this type can require the *presence* of but cannot itself verify.
 */
data class CapacitySources(val primary: String, val corroborating: String) {
    init {
        require(primary.isNotBlank()) { "primary source must not be blank" }
        require(corroborating.isNotBlank()) { "corroborating source must not be blank" }
        require(primary != corroborating) {
            "corroborating source must differ from the primary: both were '$primary'"
        }
    }
}

/**
 * One device family's design capacity, with the provenance that justifies it.
 *
 * [vendor] is part of the row rather than implied by the key, and matching is scoped to
 * it. Without that scoping a model prefix from one vendor could match another vendor's
 * model code — the namespaces are unrelated and nothing stops them colliding — and the
 * app would measure one phone's battery against another phone's design figure.
 */
data class CapacityEntry(
    val vendor: Vendor,
    val match: DeviceMatch,
    val marketingName: String,
    val designMah: Int,
    val sources: CapacitySources,
) {
    init {
        require(marketingName.isNotBlank()) { "marketingName must not be blank" }
        require(designMah in PLAUSIBLE_MAH) {
            "design capacity ${designMah}mAh for '$marketingName' is outside $PLAUSIBLE_MAH"
        }
    }

    companion object {
        /**
         * A transcription guard, not a specification. It catches the mistakes that
         * actually happen when copying figures by hand — a dropped digit (500 instead of
         * 5000), an extra one (50000), or a Wh figure pasted into a mAh column — while
         * staying wide enough to admit any real phone. The smallest Android phones sit
         * near 1500mAh and the largest current handsets near 8000mAh, so both bounds have
         * real headroom and neither is load-bearing for any entry in the table.
         *
         * It cannot catch a plausible-but-wrong figure. Nothing mechanical can; that is
         * what [CapacitySources] is for.
         */
        val PLAUSIBLE_MAH = 1_000..12_000
    }
}

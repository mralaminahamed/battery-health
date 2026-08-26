package com.alaminahamed.batteryhealth.data.vendor

/**
 * The phone makers this app knows something specific about.
 *
 * A vendor exists here only when there is a vendor-specific *fact* to attach to it — a
 * design-capacity table keyed on that vendor's model scheme, a battery-changed extra only
 * that vendor populates, or a documented quirk. It is not a directory of every Android
 * brand: a vendor with no facts attached is [Unknown] with extra steps, and would only
 * create the impression the app knows more about a device than it does.
 *
 * [Unknown] is a first-class value, not an error. On an unrecognised device every vendor
 * rule simply does not apply, and the app falls back to the framework readings that work
 * everywhere. That is the intended outcome, not a degraded one.
 *
 * @property manufacturerTokens lowercase prefixes matched against `Build.MANUFACTURER`.
 *   Several vendors need more than one because they have shipped both a brand name and a
 *   legal entity name across different models and Android versions.
 */
enum class Vendor(val manufacturerTokens: List<String>) {
    Samsung(listOf("samsung")),
    Google(listOf("google")),

    /**
     * Redmi and POCO are sub-brands, not separate vendors: both report
     * `Build.MANUFACTURER` as `Xiaomi` and differ only in `Build.BRAND`. They share
     * Xiaomi's model scheme and its battery behaviour, so they share its rules.
     */
    Xiaomi(listOf("xiaomi")),

    /**
     * OnePlus is a distinct `Build.MANUFACTURER` from Oppo despite the corporate
     * relationship, and its models use their own `CPH`/`PJ` codes. Kept separate because
     * the identifiers differ, which is the only thing this enum is used for.
     */
    OnePlus(listOf("oneplus")),
    Oppo(listOf("oppo")),

    /** iQOO ships as `Build.MANUFACTURER` = `vivo`; same reasoning as the Xiaomi sub-brands. */
    Vivo(listOf("vivo")),
    Realme(listOf("realme")),

    /**
     * `motorola` on most models, `Motorola Mobility LLC` on some — both have shipped, so
     * both are matched rather than picking whichever one a single test device reported.
     */
    Motorola(listOf("motorola")),
    Nothing(listOf("nothing")),
    Asus(listOf("asus")),
    Honor(listOf("honor")),
    Huawei(listOf("huawei")),
    Sony(listOf("sony")),

    /** Nokia-branded phones since 2020 report `HMD Global`, and newer ones report `HMD`. */
    Hmd(listOf("hmd")),
    Lenovo(listOf("lenovo")),
    Zte(listOf("zte")),
    Tecno(listOf("tecno")),
    Infinix(listOf("infinix")),
    Fairphone(listOf("fairphone")),

    /**
     * Not a vendor: the absence of a recognised one. Carries no tokens, so
     * `everyVendorIsReachableFromSomeManufacturerToken` excludes it explicitly rather
     * than it quietly satisfying the rule.
     */
    Unknown(emptyList());

    companion object {

        /**
         * Resolves from `Build.MANUFACTURER`, with `Build.BRAND` accepted for symmetry
         * with the call site but deliberately unused.
         *
         * Brand is *not* consulted, and that is the whole point: Redmi, POCO and iQOO are
         * brands whose devices are Xiaomi and vivo hardware running Xiaomi and vivo
         * firmware. Branching on brand would split them into vendors with no table and no
         * rules of their own, turning three well-covered device families into [Unknown].
         * The parameter stays so that a future vendor genuinely needing brand-level
         * discrimination can be added without changing every call site — see
         * `xiaomiSubBrandsResolveToXiaomi` and `iqooResolvesToVivo` for the behaviour
         * this pins.
         *
         * Matching is `startsWith` on a lowercased, trimmed manufacturer, never
         * `contains`. `contains` would resolve a hypothetical `NotSamsung` to
         * [Samsung] and then apply Samsung's model table to a phone that is not one — the
         * silent-wrong-vendor failure this enum exists to make impossible.
         * `manufacturerTokensAreUnambiguousAcrossVendors` proves no token shadows
         * another, so the result does not depend on declaration order.
         */
        fun of(manufacturer: String, @Suppress("UNUSED_PARAMETER") brand: String): Vendor {
            val normalised = manufacturer.trim().lowercase()
            if (normalised.isEmpty()) return Unknown
            return entries.firstOrNull { vendor ->
                vendor.manufacturerTokens.any { normalised.startsWith(it) }
            } ?: Unknown
        }
    }
}

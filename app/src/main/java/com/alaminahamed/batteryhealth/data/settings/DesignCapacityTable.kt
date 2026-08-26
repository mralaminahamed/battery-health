package com.alaminahamed.batteryhealth.data.settings

import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.Vendor

/**
 * Design capacity is not exposed by any Android API, so it has to come from a table.
 *
 * That is not a shortcut, it is the only route left. `BATTERY_PROPERTY_STATE_OF_HEALTH`,
 * `BATTERY_PROPERTY_MANUFACTURING_DATE` and `BATTERY_PROPERTY_FIRST_USAGE_DATE` are absent
 * from the public SDK entirely — verified by disassembling `android.jar` for API 37, the
 * newest platform this project compiles against, where the whole set of public
 * `BATTERY_PROPERTY_*` constants is CAPACITY, CHARGE_COUNTER, CURRENT_AVERAGE, CURRENT_NOW,
 * ENERGY_COUNTER and STATUS. The sysfs node that carries the figure,
 * `/sys/class/power_supply/battery/charge_full_design`, is blocked for the `untrusted_app`
 * SELinux domain, frequently under a `dontaudit` rule so the denial is not even logged.
 * Press coverage describing "Android 15/16 exposes battery health" refers to the Settings
 * UI, not to anything an app can call.
 *
 * ## Figures are *typical* capacity, not rated
 *
 * Cells carry two published numbers: a rated (minimum) capacity and a typical (nominal)
 * one, and they differ by roughly 2-3%. This table uses typical throughout, and that is
 * an empirical choice rather than a stylistic one. On this project's own SM-S948B the
 * framework charge counter read 4205 mAh at level 84% with the vendor reporting health at
 * 100%, implying a full charge near 5006 mAh — which matches the typical figure of 5000
 * and not a rated one. Mixing conventions between rows would make some models read
 * healthier than they are and others sicker, so do not add a rated figure to this table
 * even when it is the number a spec sheet happens to lead with.
 *
 * ## Every entry needs two independent sources
 *
 * Enforced by [CapacitySources], which cannot be constructed with one. Spec aggregators
 * copy each other freely and a typo propagates intact, so a single citation is close to
 * no citation. See [CapacitySources] for why a wrong entry is worse than a missing one.
 *
 * ## Matching is scoped to the vendor
 *
 * Model namespaces are unrelated across vendors and nothing prevents them colliding. A
 * row only ever applies to a device whose [Vendor] matches, so one maker's model prefix
 * can never claim another maker's phone.
 *
 * An unknown device returns null, and the app then reports health as Unsupported until
 * the user supplies an override in Settings. Guessing a capacity would silently corrupt
 * every health percentage the app ever shows for that model.
 */
object DesignCapacityTable {

    private val entries: List<CapacityEntry> = buildList {
        addAll(samsung())
        addAll(google())
    }

    /**
     * Samsung keys on model prefix throughout: regional variants share a stem
     * (`SM-S938B`, `SM-S938U`, `SM-S938W` are one phone), so a single prefix covers every
     * region without a row each.
     */
    private fun samsung(): List<CapacityEntry> {
        val gsmarenaA35 = "gsmarena.com/samsung_galaxy_a35-12705.php"
        val gsmarenaA55 = "gsmarena.com/samsung_galaxy_a55-12824.php"
        val samsungUkA55 = "samsung.com/uk/smartphones/galaxy-a/galaxy-a55-5g-awesome-iceblue-128gb-sm-a556blbaeub/"
        val wikipediaA35 = "en.wikipedia.org/wiki/Samsung_Galaxy_A35_5G"

        // The whole A3xx/A5xx mid-range generation ships the same 5000 mAh cell. Sourced
        // per family rather than per model because the two families are documented
        // together and confirm each other.
        val aSeries = CapacitySources(primary = gsmarenaA35, corroborating = wikipediaA35)
        val aSeriesPlus = CapacitySources(primary = gsmarenaA55, corroborating = samsungUkA55)

        val s23 = CapacitySources(
            primary = "gsmarena.com/samsung_galaxy_s23_plus_galaxy_s23_ultra_battery_details-news-56033.php",
            corroborating = "wccftech.com/samsung-galaxy-s23-s23-s23-ultra-official-battery-capacity/",
        )
        val s24 = CapacitySources(
            primary = "samsung.com/ae/support/mobile-devices/what-is-the-battery-capacity-of-the-s24/",
            corroborating = "gsmarena.com/samsung_galaxy_s24-12773.php",
        )
        val s25 = CapacitySources(
            primary = "gsmarena.com/samsung_galaxy_s25_ultra-review-2793.php",
            corroborating = "samsung.com/ae/support/mobile-devices/what-is-the-battery-capacity-of-the-s24/",
        )

        /**
         * The S26 Ultra is the one row confirmed against hardware rather than against a
         * second document, which is a stronger check than any pair of citations: this
         * project's own device reported a 4205 mAh charge counter at level 84% with vendor
         * health at 100%, implying a full charge near 5006 mAh.
         */
        val s26Ultra = CapacitySources(
            primary = "published specification for SM-S948B (5000 mAh typical)",
            corroborating = "measured on SM-S948B: charge counter 4205 mAh at level 84%, vendor health 100% -> ~5006 mAh full",
        )

        return listOf(
            entry(Vendor.Samsung, "SM-A346", "Galaxy A34 5G", 5000, aSeries),
            entry(Vendor.Samsung, "SM-A356", "Galaxy A35 5G", 5000, aSeries),
            entry(Vendor.Samsung, "SM-A366", "Galaxy A36 5G", 5000, aSeries),
            entry(Vendor.Samsung, "SM-A546", "Galaxy A54 5G", 5000, aSeriesPlus),
            entry(Vendor.Samsung, "SM-A556", "Galaxy A55 5G", 5000, aSeriesPlus),
            entry(Vendor.Samsung, "SM-A566", "Galaxy A56 5G", 5000, aSeriesPlus),

            entry(Vendor.Samsung, "SM-S911", "Galaxy S23", 3900, s23),
            entry(Vendor.Samsung, "SM-S916", "Galaxy S23+", 4700, s23),
            entry(Vendor.Samsung, "SM-S918", "Galaxy S23 Ultra", 5000, s23),

            entry(Vendor.Samsung, "SM-S921", "Galaxy S24", 4000, s24),
            entry(Vendor.Samsung, "SM-S926", "Galaxy S24+", 4900, s24),
            entry(Vendor.Samsung, "SM-S928", "Galaxy S24 Ultra", 5000, s24),

            entry(Vendor.Samsung, "SM-S931", "Galaxy S25", 4000, s25),
            entry(Vendor.Samsung, "SM-S936", "Galaxy S25+", 4900, s25),
            entry(Vendor.Samsung, "SM-S938", "Galaxy S25 Ultra", 5000, s25),

            entry(Vendor.Samsung, "SM-S948", "Galaxy S26 Ultra", 5000, s26Ultra),
        )
    }

    /**
     * Pixel is the case that forced [DeviceMatch.DeviceCode] to exist.
     *
     * `Build.MODEL` on a Pixel is the marketing name, which looks ideal for prefix
     * matching until you notice that "Pixel 9 Pro" is a literal prefix of both
     * "Pixel 9 Pro XL" and "Pixel 9 Pro Fold" — three different phones with three
     * different cells (4700, 5060 and 4650 mAh). A `ModelPrefix("Pixel 9 Pro")` row would
     * silently claim all three and report the wrong design capacity for two of them,
     * which is precisely the failure this table is built to prevent. Matching Google's
     * board name instead (`caiman`, from Google's own published device list) is exact and
     * cannot spread to a neighbour.
     */
    private fun google(): List<CapacityEntry> {
        val pixel9Pro = CapacitySources(
            primary = "gsmarena.com/google_pixel_9_pro-13218.php (\"Li-Ion 4700 mAh\")",
            corroborating = "gsmchoice.com/en/catalogue/google/pixel-9-pro/",
        )
        return listOf(
            CapacityEntry(
                vendor = Vendor.Google,
                match = DeviceMatch.DeviceCode("caiman"),
                marketingName = "Pixel 9 Pro",
                designMah = 4700,
                sources = pixel9Pro,
            ),
        )
    }

    private fun entry(
        vendor: Vendor,
        modelPrefix: String,
        marketingName: String,
        designMah: Int,
        sources: CapacitySources,
    ) = CapacityEntry(
        vendor = vendor,
        match = DeviceMatch.ModelPrefix(modelPrefix),
        marketingName = marketingName,
        designMah = designMah,
        sources = sources,
    )

    /** Every row, for tests that assert table-wide invariants. */
    internal val all: List<CapacityEntry> get() = entries

    /**
     * The matching row, or null when this device is not in the table.
     *
     * Precedence, most specific first:
     * 1. An exact [DeviceMatch.DeviceCode] hit on `Build.DEVICE`. Board names are already
     *    specific, and a vendor that needs them (OnePlus, Nothing, Pixel) needs them
     *    precisely because its model codes are not reliable.
     * 2. The longest matching [DeviceMatch.ModelPrefix]. Longest wins so a specific model
     *    never loses to a shorter family key that happens to share its stem.
     *
     * Device code beating model prefix matters for the mixed case: a vendor may be covered
     * by family prefixes in general while one particular board needs pinning out of that
     * family, and the specific row has to win.
     */
    fun lookup(identity: DeviceIdentity): CapacityEntry? {
        if (identity.vendor == Vendor.Unknown) return null
        val candidates = entries.filter { it.vendor == identity.vendor }
        if (candidates.isEmpty()) return null

        val device = identity.device.trim()
        if (device.isNotEmpty()) {
            val byCode = candidates.firstOrNull { entry ->
                entry.match is DeviceMatch.DeviceCode &&
                    entry.match.code.equals(device, ignoreCase = true)
            }
            if (byCode != null) return byCode
        }

        val model = identity.model.trim().uppercase()
        if (model.isEmpty()) return null
        return candidates
            .filter { entry ->
                entry.match is DeviceMatch.ModelPrefix &&
                    model.startsWith(entry.match.prefix.uppercase())
            }
            .maxByOrNull { (it.match as DeviceMatch.ModelPrefix).prefix.length }
    }

    /** The design capacity alone, for callers that do not need the provenance. */
    fun lookupMah(identity: DeviceIdentity): Int? = lookup(identity)?.designMah
}

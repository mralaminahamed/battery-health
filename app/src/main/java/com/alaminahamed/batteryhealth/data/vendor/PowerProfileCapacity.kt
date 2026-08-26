package com.alaminahamed.batteryhealth.data.vendor

import com.alaminahamed.batteryhealth.data.settings.CapacityEntry

/**
 * The device's own declared battery capacity, read from the platform's `power_profile.xml`.
 *
 * Every Android device carries this file in `framework-res.apk`
 * (`frameworks/base/core/res/res/xml/power_profile.xml` upstream). It holds the OEM's
 * declaration of the battery's capacity in mAh at nominal voltage, and it is the only
 * route to a design capacity that comes from the device itself rather than from a table
 * this project maintains by hand.
 *
 * It is a *resource*, not an API, which is what makes it reachable. Reading it goes
 * through `createPackageContext("android")` and `Resources.getIdentifier`, so it does not
 * touch `com.android.internal.os.PowerProfile` and therefore does not run into the
 * non-SDK interface restrictions that have applied since Android 9. Nothing here uses
 * reflection.
 *
 * ## Why the value is never trusted as-is
 *
 * AOSP's own template ships `<item name="battery.capacity">2</item>` — a placeholder,
 * meaningless as a capacity, that each OEM is expected to overwrite. Some do not, or fill
 * it in wrongly: this is a known limitation of the field rather than a hypothetical one.
 * So a reading is accepted only when it is physically plausible, and rejected otherwise,
 * exactly as this app treats every other value it cannot stand behind. A rejected reading
 * falls through to the curated table and then to the user's override.
 */
internal object PowerProfileCapacity {

    /**
     * Candidate `<item name=...>` values carrying a capacity, most preferred first.
     *
     * `battery.capacity` is AOSP's, and on at least one vendor it holds the *rated*
     * (minimum) capacity. `battery.typical.capacity` is not an AOSP field at all -- it does
     * not appear in `frameworks/base/core/res/res/xml/power_profile.xml` -- but Samsung
     * ships it alongside, carrying the typical figure.
     *
     * Observed on an SM-S948B running Android 16:
     *
     * ```
     * battery.capacity          = 4855
     * battery.typical.capacity  = 5000
     * ```
     *
     * The typical figure is the right one for this app, and the device settles that rather
     * than a preference: the same phone reported a 4580 mAh charge counter at level 91%,
     * implying about 5033 mAh at full. Measured against 4855 that is 103.7% health; against
     * 5000 it is 100.7%. Reading `battery.capacity` would have over-reported health by
     * roughly 3% on every Samsung device -- silently, and in the flattering direction,
     * which is the worst way for a health figure to be wrong.
     *
     * Order is preference, not fallback-on-error. See [selectCapacity].
     */
    val CAPACITY_ITEMS_IN_PREFERENCE_ORDER = listOf(
        "battery.typical.capacity",
        "battery.capacity",
    )

    /** AOSP's own field name, kept for callers that specifically mean that one. */
    const val ITEM_NAME = "battery.capacity"

    /** The resource this lives in: `@android:xml/power_profile`. */
    const val RESOURCE_NAME = "power_profile"
    const val RESOURCE_TYPE = "xml"
    const val RESOURCE_PACKAGE = "android"

    /**
     * Turns the raw text of the `battery.capacity` item into a capacity this app is
     * willing to use, or null.
     *
     * Pure, so every rejection rule below is provable on the JVM without a device — which
     * matters because these rules are the entire defence against the platform handing us
     * a placeholder and the app rendering it as a measured fact.
     *
     * Parsed as a floating-point number rather than an integer: the field is declared as a
     * float in AOSP's schema and real devices do write `4000.0`. `toIntOrNull` would
     * reject those outright, silently losing the device source on every phone that writes
     * a decimal point.
     *
     * Rounded rather than truncated, so `4499.7` becomes 4500 and not 4499.
     *
     * The plausibility bound is shared with [CapacityEntry.PLAUSIBLE_MAH] rather than
     * restated, so the table and the device source cannot drift into disagreeing about
     * what counts as a real capacity. AOSP's placeholder of `2` fails it by three orders
     * of magnitude, which is the case it most needs to catch.
     */
    fun interpret(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        val mah = Math.round(value).toInt()
        return if (mah in CapacityEntry.PLAUSIBLE_MAH) mah else null
    }

    /**
     * Picks the best capacity out of everything the profile declared.
     *
     * Walks [CAPACITY_ITEMS_IN_PREFERENCE_ORDER] and returns the first entry that is both
     * present and passes [interpret]. A preferred item failing the plausibility check does
     * not veto the next one: a vendor shipping `battery.typical.capacity` as an unfilled
     * placeholder should fall through to AOSP's field rather than leaving the app with
     * nothing at all.
     */
    fun selectCapacity(items: Map<String, String?>): Int? =
        CAPACITY_ITEMS_IN_PREFERENCE_ORDER.firstNotNullOfOrNull { name -> interpret(items[name]) }
}

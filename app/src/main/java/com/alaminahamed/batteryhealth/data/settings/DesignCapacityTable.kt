package com.alaminahamed.batteryhealth.data.settings

/**
 * Design capacity is not exposed by any Android API, so it has to come from a table
 * keyed on Build.MODEL. Entries are model-family prefixes because Samsung appends a
 * region letter (SM-A356E, SM-A356B, SM-A3560 are all the same phone).
 *
 * An unknown model returns null, and the app then reports health as Unsupported until
 * the user supplies an override. Guessing a capacity would silently corrupt every
 * health percentage the app ever shows.
 */
object DesignCapacityTable {

    private val byPrefix: Map<String, Int> = mapOf(
        // Galaxy A series
        "SM-A346" to 5000, // A34 5G
        "SM-A356" to 5000, // A35 5G — development device
        "SM-A366" to 5000, // A36 5G
        "SM-A546" to 5000, // A54 5G
        "SM-A556" to 5000, // A55 5G
        "SM-A566" to 5000, // A56 5G
        // Galaxy S23 series
        "SM-S911" to 3900,
        "SM-S916" to 4700,
        "SM-S918" to 5000,
        // Galaxy S24 series
        "SM-S921" to 4000,
        "SM-S926" to 4900,
        "SM-S928" to 5000,
        // Galaxy S25 series
        "SM-S931" to 4000,
        "SM-S936" to 4900,
        "SM-S938" to 5000,
        // Galaxy S26 is deliberately absent: no design capacity for it could be sourced
        // with confidence at the time this table was written (see DesignCapacityTable's
        // own doc on why a wrong entry here is worse than no entry). SM-S948B in
        // particular -- this project's own test device -- stays unsupported by the
        // table until a verified figure is available; the override in Settings is the
        // supported way to give it one meanwhile.
    )

    fun lookup(model: String): Int? {
        if (model.isBlank()) return null
        val normalised = model.uppercase()
        // Longest prefix first, so a specific model never loses to a shorter family key.
        return byPrefix.entries
            .filter { normalised.startsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value
    }
}

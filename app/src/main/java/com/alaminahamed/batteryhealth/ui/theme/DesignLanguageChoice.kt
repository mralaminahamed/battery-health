package com.alaminahamed.batteryhealth.ui.theme

/**
 * What the user asked for. [Auto] defers to the device; the other two are an explicit
 * override.
 *
 * The override is not a power-user curiosity. It is what makes both design languages
 * reachable on a single device, which is the difference between the second language being
 * testable and being guesswork — see the spec's Risks section.
 */
enum class DesignLanguageChoice { Auto, Samsung, Material }

/** Which bundle of values the UI actually renders with. */
enum class DesignLanguageId { OneUi, Expressive }

private const val SAMSUNG = "samsung"

/**
 * Pure so it can be tested without a device: [manufacturer] is passed in rather than read
 * from `Build.MANUFACTURER` at the point of use.
 *
 * Exact match, case-insensitive. Samsung devices report the vendor string with
 * inconsistent casing across Android versions, but no shipping device reports a string
 * that merely *contains* "samsung", so a substring rule would widen the claim past the
 * evidence for it.
 */
fun resolveDesignLanguageId(
    choice: DesignLanguageChoice,
    manufacturer: String,
): DesignLanguageId = when (choice) {
    DesignLanguageChoice.Samsung -> DesignLanguageId.OneUi
    DesignLanguageChoice.Material -> DesignLanguageId.Expressive
    DesignLanguageChoice.Auto ->
        if (manufacturer.trim().equals(SAMSUNG, ignoreCase = true)) {
            DesignLanguageId.OneUi
        } else {
            DesignLanguageId.Expressive
        }
}

package com.alaminahamed.batteryhealth.data.settings

/**
 * Validates a user-entered cycle-count baseline before anything is written to DataStore.
 *
 * This app can only count charge it has watched go in, so on a phone that is already old
 * its count starts at zero however many cycles the battery has actually seen. Samsung
 * publishes the real figure to the user -- Settings, Battery, Battery information -- and
 * a number the user read there is a genuine measurement, just one this app could not take
 * itself. Accepting it as a starting point turns a permanently useless row into an
 * accurate one, without this app inventing anything.
 *
 * Rejecting rather than clamping, for the same reason [DesignCapacityValidation] does:
 * clamping a typo would silently fabricate a battery history.
 *
 * Pure -- no Context, no Android types -- so the dialog only branches on the result.
 */
object CycleBaselineValidation {
    /**
     * Zero is allowed, and is not the same as leaving the baseline unset.
     *
     * A brand-new battery genuinely has zero cycles, and a user who reads "0" from their
     * phone's own screen is telling this app something true: that its count and the
     * vendor's now agree. Unset means "nobody has said", which is a different state.
     */
    const val MIN_CYCLES = 0

    /**
     * Well past any real phone battery. Cells are typically rated for several hundred
     * cycles and degrade heavily beyond that; a four-digit figure is a misread or a typo,
     * not a remarkable battery.
     */
    const val MAX_CYCLES = 5_000

    sealed interface Result {
        data class Valid(val cycles: Int) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter a cycle count")

        // Long, not Int, so a value that overflows Int32 is rejected by the range check
        // with an accurate message rather than falling through to "not a whole number".
        val cycles = trimmed.toLongOrNull()
            ?: return Result.Invalid("Enter a whole number of cycles")

        if (cycles < MIN_CYCLES || cycles > MAX_CYCLES) {
            return Result.Invalid("Enter a value between $MIN_CYCLES and $MAX_CYCLES")
        }
        return Result.Valid(cycles.toInt())
    }
}

package com.mralaminahamed.batteryhealth.data.settings

/**
 * Validates a user-entered design-capacity override before anything is written to
 * DataStore. A plausible phone battery sits roughly between 1000 and 10000 mAh; anything
 * outside that band is a typo, not a smaller or larger phone. Rejecting rather than
 * clamping matters: clamping a typo like "50000" down to 10000 would silently fabricate
 * a design capacity, which is exactly the kind of confident-but-wrong number the rest of
 * this app is built to refuse.
 *
 * Kept as a pure function -- no Context, no Android types -- so it is JVM-testable and
 * the composable dialog only has to call it and branch on the result, never re-derive it.
 */
object DesignCapacityValidation {
    const val MIN_MAH = 1_000
    const val MAX_MAH = 10_000

    sealed interface Result {
        data class Valid(val mah: Int) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter a value in mAh")

        // Parsed as Long, not Int: a number that overflows Int32 (e.g. "99999999999")
        // must still be rejected by the range check below with an accurate message,
        // rather than falling through to "not a whole number" just because it was too
        // big for the narrower type.
        val mah = trimmed.toLongOrNull()
            ?: return Result.Invalid("Enter a whole number of mAh")

        if (mah < MIN_MAH || mah > MAX_MAH) {
            return Result.Invalid("Enter a value between $MIN_MAH and $MAX_MAH mAh")
        }
        return Result.Valid(mah.toInt())
    }
}

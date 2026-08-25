package com.alaminahamed.batteryhealth.data.settings

/**
 * Validates a user-entered adb port before it reaches `SettingsStore.setAdbPort`. A TCP
 * port is 1..65535 by definition (0 is reserved, and there is no port above 65535) --
 * that is the whole valid range, not a plausibility band the way design capacity's is.
 *
 * `SettingsStore.setAdbPort` already refuses an out-of-range value silently, which is
 * the right behaviour for a setter whose only real caller was, until this feature, none
 * -- see its own doc. A value coming straight from a Settings screen's text field needs
 * more than silent refusal: the user needs to see *why* nothing happened, the same
 * reasoning `DesignCapacityValidation` is built on.
 *
 * Kept as a pure function -- no Context, no Android types -- so it is JVM-testable and
 * the settings screen only has to call it and branch on the result.
 */
object AdbPortValidation {
    const val MIN_PORT = 1
    const val MAX_PORT = 65535

    sealed interface Result {
        data class Valid(val port: Int) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter a port number")

        // Parsed as Long, not Int, for the same reason DesignCapacityValidation is: a
        // value that overflows Int32 must still land on the range message below, not on
        // a "not a whole number" message that misdescribes why it was rejected.
        val port = trimmed.toLongOrNull()
            ?: return Result.Invalid("Enter a whole port number")

        if (port < MIN_PORT || port > MAX_PORT) {
            return Result.Invalid("Enter a port between $MIN_PORT and $MAX_PORT")
        }
        return Result.Valid(port.toInt())
    }
}

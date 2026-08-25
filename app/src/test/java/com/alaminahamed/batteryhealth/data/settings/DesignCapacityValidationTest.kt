package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one rule this feature depends on: reject outside the plausible range rather
 * than clamp into it. Clamping a typo like "50000" down to 10000 would fabricate exactly
 * the kind of confident-but-wrong number the rest of the app refuses to show.
 */
class DesignCapacityValidationTest {

    @Test
    fun aPlausibleValueIsValid() {
        val result = DesignCapacityValidation.validate("4820")
        assertEquals(DesignCapacityValidation.Result.Valid(4820), result)
    }

    @Test
    fun theLowerBoundIsInclusive() {
        assertEquals(
            DesignCapacityValidation.Result.Valid(DesignCapacityValidation.MIN_MAH),
            DesignCapacityValidation.validate(DesignCapacityValidation.MIN_MAH.toString()),
        )
    }

    @Test
    fun theUpperBoundIsInclusive() {
        assertEquals(
            DesignCapacityValidation.Result.Valid(DesignCapacityValidation.MAX_MAH),
            DesignCapacityValidation.validate(DesignCapacityValidation.MAX_MAH.toString()),
        )
    }

    @Test
    fun justBelowTheLowerBoundIsRejectedNotClamped() {
        val result = DesignCapacityValidation.validate((DesignCapacityValidation.MIN_MAH - 1).toString())
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
        val message = (result as DesignCapacityValidation.Result.Invalid).message
        assertTrue(message.contains(DesignCapacityValidation.MIN_MAH.toString()))
        assertTrue(message.contains(DesignCapacityValidation.MAX_MAH.toString()))
    }

    @Test
    fun justAboveTheUpperBoundIsRejectedNotClamped() {
        val result = DesignCapacityValidation.validate((DesignCapacityValidation.MAX_MAH + 1).toString())
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun aWildlyLargeTypoIsRejectedNotClampedToTheUpperBound() {
        // The exact failure mode this rule exists to prevent: "50000" must not silently
        // become "10000".
        val result = DesignCapacityValidation.validate("50000")
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun emptyInputIsRejectedNotCrashing() {
        assertTrue(DesignCapacityValidation.validate("") is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun blankInputIsRejectedNotCrashing() {
        assertTrue(DesignCapacityValidation.validate("   ") is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun nonNumericInputIsRejectedNotCrashing() {
        assertTrue(DesignCapacityValidation.validate("banana") is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun negativeInputIsRejectedByRangeNotByCrashing() {
        val result = DesignCapacityValidation.validate("-500")
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
    }

    @Test
    fun aNumberTooLargeToFitAnIntStillReportsTheRangeRatherThanOverflowing() {
        // 99999999999 overflows Int32 (max ~2.1 billion) but fits comfortably in Long, so
        // this must land on the range message, not the generic "not a whole number" one --
        // a naive toIntOrNull-only parse would misreport this as non-numeric.
        val result = DesignCapacityValidation.validate("99999999999")
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
        val message = (result as DesignCapacityValidation.Result.Invalid).message
        assertTrue(message.contains(DesignCapacityValidation.MAX_MAH.toString()))
    }

    @Test
    fun surroundingWhitespaceIsTrimmedRatherThanRejected() {
        assertEquals(
            DesignCapacityValidation.Result.Valid(4200),
            DesignCapacityValidation.validate("  4200  "),
        )
    }

    @Test
    fun decimalInputIsRejectedWithAWholeNumberMessage() {
        val result = DesignCapacityValidation.validate("4500.5")
        assertTrue(result is DesignCapacityValidation.Result.Invalid)
    }
}

package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors [DesignCapacityValidationTest]'s own rationale: reject outside the valid TCP
 * port range rather than clamp into it, and refuse rather than silently drop the entry
 * on the floor with no visible reason -- `SettingsStore.setAdbPort` already refuses an
 * out-of-range port silently at the storage layer (fine for a value that can only ever
 * arrive already validated), but a user typing straight into a Settings field needs an
 * explicit, visible reason, not a write that just quietly didn't happen.
 */
class AdbPortValidationTest {

    @Test
    fun aValidPortInRangeIsValid() {
        assertEquals(AdbPortValidation.Result.Valid(5555), AdbPortValidation.validate("5555"))
    }

    @Test
    fun theLowerBoundIsInclusive() {
        assertEquals(AdbPortValidation.Result.Valid(1), AdbPortValidation.validate("1"))
    }

    @Test
    fun theUpperBoundIsInclusive() {
        assertEquals(AdbPortValidation.Result.Valid(65535), AdbPortValidation.validate("65535"))
    }

    @Test
    fun zeroIsRejectedNotClamped() {
        val result = AdbPortValidation.validate("0")
        assertTrue(result is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun justAboveTheUpperBoundIsRejectedNotClamped() {
        val result = AdbPortValidation.validate("65536")
        assertTrue(result is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun aWildlyOutOfRangeValueIsRejectedNotClampedToTheUpperBound() {
        val result = AdbPortValidation.validate("999999")
        assertTrue(result is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun negativeInputIsRejectedByRangeNotByCrashing() {
        assertTrue(AdbPortValidation.validate("-1") is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun nonNumericInputIsRejectedNotCrashing() {
        assertTrue(AdbPortValidation.validate("banana") is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun emptyInputIsRejectedNotCrashing() {
        assertTrue(AdbPortValidation.validate("") is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun blankInputIsRejectedNotCrashing() {
        assertTrue(AdbPortValidation.validate("   ") is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun decimalInputIsRejectedWithAWholeNumberMessage() {
        val result = AdbPortValidation.validate("5555.5")
        assertTrue(result is AdbPortValidation.Result.Invalid)
    }

    @Test
    fun surroundingWhitespaceIsTrimmedRatherThanRejected() {
        assertEquals(AdbPortValidation.Result.Valid(5037), AdbPortValidation.validate("  5037  "))
    }

    @Test
    fun aNumberTooLargeToFitAnIntStillReportsTheRangeRatherThanOverflowing() {
        val result = AdbPortValidation.validate("99999999999")
        assertTrue(result is AdbPortValidation.Result.Invalid)
        val message = (result as AdbPortValidation.Result.Invalid).message
        assertTrue(message.contains("65535"))
    }
}

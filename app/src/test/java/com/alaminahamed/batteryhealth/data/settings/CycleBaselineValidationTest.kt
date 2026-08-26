package com.alaminahamed.batteryhealth.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleBaselineValidationTest {

    private fun valid(input: String) =
        (CycleBaselineValidation.validate(input) as CycleBaselineValidation.Result.Valid).cycles

    @Test
    fun aRealFigureReadFromThePhoneIsAccepted() {
        // The value this project's own device reports through its privileged tier.
        assertEquals(7, valid("7"))
        assertEquals(412, valid("412"))
    }

    /**
     * Zero is a real answer, not an empty one: a new battery genuinely has zero cycles,
     * and a user reporting it is saying this app's count and the vendor's now agree.
     */
    @Test
    fun zeroIsAValidBaselineRatherThanBlank() {
        assertEquals(0, valid("0"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(120, valid("  120 "))
    }

    @Test
    fun anEmptyEntryAsksForOne() {
        val result = CycleBaselineValidation.validate("   ")
        assertTrue(result is CycleBaselineValidation.Result.Invalid)
    }

    @Test
    fun nonNumericIsRejected() {
        assertTrue(CycleBaselineValidation.validate("many") is CycleBaselineValidation.Result.Invalid)
        assertTrue(CycleBaselineValidation.validate("7.5") is CycleBaselineValidation.Result.Invalid)
    }

    /**
     * Rejected, never clamped. Clamping a typo would silently fabricate a battery history,
     * the same reason DesignCapacityValidation refuses to.
     */
    @Test
    fun outOfRangeIsRejectedNotClamped() {
        assertTrue(CycleBaselineValidation.validate("-1") is CycleBaselineValidation.Result.Invalid)
        assertTrue(CycleBaselineValidation.validate("9999") is CycleBaselineValidation.Result.Invalid)
    }

    /**
     * A value too large for Int32 must still be reported as out of range rather than as
     * "not a whole number", which would be a misleading reason.
     */
    @Test
    fun anOverflowingNumberIsReportedAsOutOfRange() {
        val result = CycleBaselineValidation.validate("99999999999")
        val message = (result as CycleBaselineValidation.Result.Invalid).message
        assertTrue("got: $message", message.contains("between"))
    }

    @Test
    fun theBoundsThemselvesAreAccepted() {
        assertEquals(CycleBaselineValidation.MIN_CYCLES, valid("0"))
        assertEquals(CycleBaselineValidation.MAX_CYCLES, valid("5000"))
    }
}

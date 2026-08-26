package com.alaminahamed.batteryhealth.data.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These rules are the entire defence between the platform handing this app a placeholder
 * and the app rendering it as a measured fact, so each rejection is pinned individually.
 */
class PowerProfileCapacityTest {

    @Test
    fun aPlainIntegerCapacityIsAccepted() {
        assertEquals(5000, PowerProfileCapacity.interpret("5000"))
    }

    /**
     * AOSP declares this field as a float and real devices do write `4000.0`. Parsing with
     * `toIntOrNull` would reject every one of them and silently lose the device source on
     * those phones.
     */
    @Test
    fun aDecimalCapacityIsAccepted() {
        assertEquals(4000, PowerProfileCapacity.interpret("4000.0"))
        assertEquals(4500, PowerProfileCapacity.interpret("4500.00"))
    }

    @Test
    fun aDecimalCapacityIsRoundedNotTruncated() {
        assertEquals(4500, PowerProfileCapacity.interpret("4499.7"))
        assertEquals(4499, PowerProfileCapacity.interpret("4499.4"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(5000, PowerProfileCapacity.interpret("  5000  \n"))
    }

    /**
     * The case this check exists for. AOSP's own template ships
     * `<item name="battery.capacity">2</item>` as a placeholder for OEMs to overwrite, and
     * some ship it unchanged. Two milliamp-hours is not a phone battery.
     */
    @Test
    fun theAospPlaceholderIsRejected() {
        assertNull(PowerProfileCapacity.interpret("2"))
    }

    @Test
    fun implausibleMagnitudesAreRejected() {
        assertNull(PowerProfileCapacity.interpret("0"))
        assertNull(PowerProfileCapacity.interpret("500"))     // a dropped digit
        assertNull(PowerProfileCapacity.interpret("50000"))   // an extra one
        assertNull(PowerProfileCapacity.interpret("-5000"))
    }

    @Test
    fun unparseableTextIsRejectedRatherThanGuessed() {
        assertNull(PowerProfileCapacity.interpret(null))
        assertNull(PowerProfileCapacity.interpret(""))
        assertNull(PowerProfileCapacity.interpret("   "))
        assertNull(PowerProfileCapacity.interpret("unknown"))
        assertNull(PowerProfileCapacity.interpret("5000mAh"))
    }

    /**
     * A non-finite value would survive `toDoubleOrNull` — Kotlin parses "NaN" and
     * "Infinity" happily — and then `Math.round` would turn them into 0 and
     * `Int.MAX_VALUE`. The first would be rejected by the range check anyway; the second
     * is caught here rather than relying on that.
     */
    @Test
    fun nonFiniteValuesAreRejected() {
        assertNull(PowerProfileCapacity.interpret("NaN"))
        assertNull(PowerProfileCapacity.interpret("Infinity"))
        assertNull(PowerProfileCapacity.interpret("-Infinity"))
    }

    @Test
    fun theBoundsThemselvesAreAccepted() {
        assertEquals(1000, PowerProfileCapacity.interpret("1000"))
        assertEquals(12000, PowerProfileCapacity.interpret("12000"))
    }

    // ---- choosing between the fields a real profile carries --------------------------

    /**
     * The exact profile an SM-S948B on Android 16 reports. `battery.capacity` is AOSP's
     * field and holds Samsung's *rated* figure; `battery.typical.capacity` is a Samsung
     * addition that is not in AOSP at all and holds the typical one.
     *
     * The device settles which is right. That same phone read a 4580 mAh charge counter at
     * level 91%, implying ~5033 mAh at full: 103.7% against 4855, 100.7% against 5000.
     * Taking AOSP's field would over-report health by ~3% on every Samsung, silently and
     * in the flattering direction.
     */
    @Test
    fun theTypicalFigureWinsOverTheRatedOne() {
        val observed = mapOf(
            "battery.capacity" to "4855",
            "battery.typical.capacity" to "5000",
        )
        assertEquals(5000, PowerProfileCapacity.selectCapacity(observed))
    }

    /** Most devices carry only AOSP's field, and it is used unchanged there. */
    @Test
    fun aospsFieldIsUsedWhenNothingBetterExists() {
        assertEquals(4820, PowerProfileCapacity.selectCapacity(mapOf("battery.capacity" to "4820")))
    }

    /**
     * Preference must not become a veto. A vendor shipping the preferred field as an
     * unfilled placeholder should fall through to AOSP's rather than leaving the app with
     * nothing, which would be strictly worse than not having looked.
     */
    @Test
    fun animplausiblePreferredFieldFallsThroughRatherThanVetoing() {
        val items = mapOf(
            "battery.typical.capacity" to "2",
            "battery.capacity" to "4400",
        )
        assertEquals(4400, PowerProfileCapacity.selectCapacity(items))
    }

    @Test
    fun aProfileWithNoCapacityAtAllYieldsNothing() {
        assertNull(PowerProfileCapacity.selectCapacity(emptyMap()))
        assertNull(PowerProfileCapacity.selectCapacity(mapOf("battery.cpu.idle" to "12")))
        assertNull(
            PowerProfileCapacity.selectCapacity(
                mapOf("battery.capacity" to "2", "battery.typical.capacity" to null),
            ),
        )
    }

    @Test
    fun theTypicalFieldIsPreferredAheadOfAospsInTheDeclaredOrder() {
        assertEquals(
            listOf("battery.typical.capacity", "battery.capacity"),
            PowerProfileCapacity.CAPACITY_ITEMS_IN_PREFERENCE_ORDER,
        )
    }
}

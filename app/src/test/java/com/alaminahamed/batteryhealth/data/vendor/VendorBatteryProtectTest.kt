package com.alaminahamed.batteryhealth.data.vendor

import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class VendorBatteryProtectTest {

    /**
     * The observation this is built on. On an SM-S948B running Android 16,
     * `Settings.Global.protect_battery` read `1` at a moment when the app's privileged
     * tier independently reported Battery Protect as on -- two sources agreeing, not one
     * being assumed.
     */
    @Test
    fun theObservedOnValueReadsAsOn() {
        assertEquals(
            Reading.Available(true, Source.Vendor),
            VendorBatteryProtect.interpret("1"),
        )
    }

    @Test
    fun zeroReadsAsOff() {
        assertEquals(
            Reading.Available(false, Source.Vendor),
            VendorBatteryProtect.interpret("0"),
        )
    }

    /**
     * The deliberately minimal claim. Samsung's neighbouring keys
     * (`battery_protection_default_value=3`, `adaptive_protection_current_switch_value=1`)
     * show this family encodes modes, so `protect_battery` may well be a mode rather than
     * a boolean. This app does not know which mode each number names, and has no evidence
     * for a mapping, so it claims only what it can defend: zero is off, anything else is
     * on. If a later reading proves 2 or 3 means something other than "on", this is the
     * one place that changes.
     */
    @Test
    fun anyOtherModeStillMeansProtectionIsOn() {
        assertEquals(Reading.Available(true, Source.Vendor), VendorBatteryProtect.interpret("2"))
        assertEquals(Reading.Available(true, Source.Vendor), VendorBatteryProtect.interpret("3"))
    }

    /**
     * A device without the key is not a device with protection off. Every non-Samsung
     * phone lands here, and reporting "off" would be inventing a fact about hardware that
     * has no such feature.
     */
    @Test
    fun anAbsentKeyIsUnsupportedNotOff() {
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret(null))
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret(""))
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret("   "))
    }

    /**
     * `prev_protect_battery` reads -1 on this device, so negative values are real in this
     * key family and plainly mean "no value recorded" rather than a mode. Treating -1 as
     * "on" (non-zero) would be the kind of accidental truth-by-arithmetic this codebase
     * keeps finding in itself.
     */
    @Test
    fun negativeOneIsNoValueRatherThanOn() {
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret("-1"))
    }

    @Test
    fun unparseableTextIsUnsupportedRatherThanGuessed() {
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret("on"))
        assertEquals(Reading.Unsupported, VendorBatteryProtect.interpret("true"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(Reading.Available(true, Source.Vendor), VendorBatteryProtect.interpret(" 1 "))
    }

    // ---- the charge limit ------------------------------------------------------------

    /**
     * The value Samsung's own Battery protection screen shows. With protection on and
     * Maximum mode selected, that screen read "Your battery will stop charging when it
     * reaches 95%" while this key read 95 -- and while the privileged dump reported
     * `mProtectionThreshold: 80`, which the app was displaying and which was wrong.
     */
    @Test
    fun theSettingsKeyIsTheLimitTheVendorsOwnScreenShows() {
        assertEquals(
            Reading.Available(95, Source.Vendor),
            VendorBatteryProtectThreshold.interpretThreshold("95"),
        )
    }

    /** The slider's other stops, all of which a user can select. */
    @Test
    fun everySliderStopIsAccepted() {
        listOf(80, 85, 90, 95).forEach { pct ->
            assertEquals(
                "$pct%",
                Reading.Available(pct, Source.Vendor),
                VendorBatteryProtectThreshold.interpretThreshold(pct.toString()),
            )
        }
    }

    /**
     * -1 is this key family's "no value recorded" marker, shown by `prev_protect_battery`
     * reading it on real hardware. A charge limit of -1% is not a limit.
     */
    @Test
    fun theNoValueMarkerIsNotALimit() {
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold("-1"))
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold("0"))
    }

    @Test
    fun animplausiblePercentageIsRefused() {
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold("140"))
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold("5"))
    }

    @Test
    fun anAbsentKeyIsUnsupported() {
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold(null))
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold(""))
        assertEquals(Reading.Unsupported, VendorBatteryProtectThreshold.interpretThreshold("high"))
    }
}

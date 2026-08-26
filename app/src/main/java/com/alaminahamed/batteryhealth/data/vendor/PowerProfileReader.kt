package com.alaminahamed.batteryhealth.data.vendor

import android.content.Context
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/**
 * Reads `battery.capacity` out of the platform's own `power_profile.xml`.
 *
 * See [PowerProfileCapacity] for what the value is, why it is reachable without
 * reflection, and why it is never trusted without a plausibility check.
 *
 * Everything here is best-effort by design. The resource is private to the `android`
 * package: it is present on every AOSP-derived build, but nothing guarantees a given OEM
 * ships it under that name, and a device could rename it, strip it, or restructure its
 * contents. Every one of those cases is a null, not a crash — the app has a curated table
 * and a user override behind this, and a missing device source is an ordinary outcome
 * rather than an error worth propagating.
 */
class PowerProfileReader(private val context: Context) {

    /**
     * The device's declared battery capacity in mAh, or null if it could not be read or
     * was not plausible.
     *
     * `Throwable` is caught rather than a specific list. The call chain crosses a package
     * boundary into resources this app does not own and hands the result to a pull parser:
     * it can fail with `NameNotFoundException`, `Resources.NotFoundException`,
     * `XmlPullParserException`, `IOException`, or — on a device whose resource of this
     * name is some unrelated binary — a `RuntimeException` from the parser itself. None of
     * those is recoverable and none should reach the caller, because "this device does not
     * offer the value" is the same answer in every case. This is the one place in this
     * codebase where a blanket catch is the correct shape, and the reason is that the
     * input is another package's private resource rather than anything this app controls.
     */
    fun batteryCapacityMah(): Int? = try {
        val resources = context.createPackageContext(PowerProfileCapacity.RESOURCE_PACKAGE, 0).resources
        val id = resources.getIdentifier(
            PowerProfileCapacity.RESOURCE_NAME,
            PowerProfileCapacity.RESOURCE_TYPE,
            PowerProfileCapacity.RESOURCE_PACKAGE,
        )
        if (id == 0) null else resources.getXml(id).use { parser -> parser.findBatteryCapacity() }
    } catch (t: Throwable) {
        null
    }

    /**
     * Walks to `<item name="battery.capacity">` and returns the text inside it.
     *
     * The value is the element's *text*, not an attribute, so this has to track whether
     * the item currently open is the right one and then read the following text event.
     * Stops at the first match: the file declares each item once, and a second occurrence
     * would be a malformed profile rather than an override to prefer.
     */
    private fun XmlResourceParser.findBatteryCapacity(): Int? {
        var insideTargetItem = false
        var event = eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG ->
                    insideTargetItem = name == "item" &&
                        getAttributeValue(null, "name") == PowerProfileCapacity.ITEM_NAME

                XmlPullParser.TEXT ->
                    if (insideTargetItem) {
                        return PowerProfileCapacity.interpret(text)
                    }

                XmlPullParser.END_TAG -> insideTargetItem = false
            }
            event = next()
        }
        return null
    }

    private inline fun <T> XmlResourceParser.use(block: (XmlResourceParser) -> T): T = try {
        block(this)
    } finally {
        close()
    }
}

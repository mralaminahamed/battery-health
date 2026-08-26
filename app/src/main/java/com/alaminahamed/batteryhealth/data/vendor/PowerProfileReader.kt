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
    fun batteryCapacityMah(): Int? = PowerProfileCapacity.selectCapacity(batteryItems())

    /**
     * Every `battery.*` item in the profile, as raw text.
     *
     * Used by the discovery sweep rather than by any reading: the neighbouring fields are
     * not values this app interprets, but whether a device populated them at all is a
     * useful signal about how much its `battery.capacity` is worth. A profile left wholly
     * at AOSP defaults and one an OEM actually filled in look identical if you only ever
     * read the single field you wanted.
     *
     * Same blanket failure handling as [batteryCapacityMah], for the same reason: this
     * crosses into another package's private resource, and every way that can fail means
     * the same thing to the caller.
     */
    fun batteryItems(): Map<String, String?> = try {
        val resources = context.createPackageContext(PowerProfileCapacity.RESOURCE_PACKAGE, 0).resources
        val id = resources.getIdentifier(
            PowerProfileCapacity.RESOURCE_NAME,
            PowerProfileCapacity.RESOURCE_TYPE,
            PowerProfileCapacity.RESOURCE_PACKAGE,
        )
        if (id == 0) emptyMap() else resources.getXml(id).use { it.collectBatteryItems() }
    } catch (t: Throwable) {
        emptyMap()
    }

    /**
     * Collects `<item name="battery.*">` elements and their text.
     *
     * Only the `battery.` prefix: a full power profile carries dozens of CPU, radio and
     * screen coefficients that have nothing to do with this app and would bury the
     * relevant rows in a report meant to be read.
     */
    private fun XmlResourceParser.collectBatteryItems(): Map<String, String?> {
        val items = mutableMapOf<String, String?>()
        var openName: String? = null
        var event = eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val attr = if (name == "item") getAttributeValue(null, "name") else null
                    openName = attr?.takeIf { it.startsWith("battery.") }
                    // Recorded on open, so an item with no text still appears as present
                    // with no value rather than vanishing from the report entirely.
                    if (openName != null) items.putIfAbsent(openName, null)
                }

                XmlPullParser.TEXT -> openName?.let { items[it] = text }
                XmlPullParser.END_TAG -> openName = null
            }
            event = next()
        }
        return items
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

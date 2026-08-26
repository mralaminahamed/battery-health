package com.alaminahamed.batteryhealth.data.vendor

/**
 * The four `Build` fields that identify a phone, captured as plain strings so every rule
 * keyed on them is provable on the JVM without a device.
 *
 * Both [model] and [device] are carried because neither alone is sufficient, and that is
 * an empirical finding rather than a defensive one. Google's own published device list
 * (`supported_devices.csv`) shows the OnePlus 13 shipping as four distinct `Build.MODEL`
 * values — `PJZ110`, `CPH2649`, `CPH2653`, `CPH2655` — across regions, while its
 * `Build.DEVICE` stays `OP5D55L1`. Samsung is the mirror image: `SM-S938B`/`SM-S938U`
 * share an obvious model prefix, so a prefix rule covers every region at once. A table
 * keyed on model alone would miss most OnePlus phones; keyed on device alone it would
 * need one row per Samsung variant. Both keys, per vendor, is the only rule that fits
 * both shapes.
 *
 * [vendor] is resolved once at construction rather than on each read: it is a pure
 * function of [manufacturer], so recomputing it could only ever return the same answer
 * more slowly.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
) {
    val vendor: Vendor = Vendor.of(manufacturer, brand)

    companion object {
        /**
         * A device this app knows nothing about. Used by tests that need an identity
         * whose vendor rules cannot possibly apply, and as the safe value for a caller
         * that could not read `Build` at all.
         */
        val Unknown = DeviceIdentity(manufacturer = "", brand = "", model = "", device = "")
    }
}

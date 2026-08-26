package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * Which `BatteryManager` accessor returns a property.
 *
 * Load-bearing rather than cosmetic. `getLongProperty` on a string-typed id returns the
 * unsupported sentinel on every device, which reads exactly like the platform not having
 * the property at all -- so probing all fifteen ids through the numeric accessor would
 * silently and permanently report the three text ones as absent, including on hardware
 * where they are merely withheld, or in fact readable.
 */
enum class PropertyKind { Numeric, Text }

/**
 * Every `BATTERY_PROPERTY_*` id the platform defines, including the ones absent from the
 * public SDK.
 *
 * Values verified against AOSP `frameworks/base/core/java/android/os/BatteryManager.java`
 * (android17-release), and ids 1-6 cross-checked by disassembling the API 37 `android.jar`
 * this project compiles against. They are not guessed, and they must not be edited without
 * re-checking the same two places: an id that drifts turns every reading taken through it
 * into a confident answer about the wrong quantity.
 *
 * ## Why the hidden ids are probed at all
 *
 * `BatteryManager.getIntProperty(int)` is a public method. Passing it `10` is passing an
 * integer literal to public API — it is not reflection, does not name a hidden field, and
 * does not engage the non-SDK interface restrictions that have applied since Android 9.
 * The platform's own permission check is the gate, and asking it is the only way to learn
 * the answer for a given device.
 *
 * ## The one that matters
 *
 * [StateOfHealth] is the exception in AOSP's enforcement, and this is quoted from
 * `BatteryService.BatteryPropertiesRegistrar.getProperty`:
 *
 * ```java
 * case BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH:
 *     if (stateOfHealthPublic()) {
 *         break;
 *     }
 *     // falls through
 * case BatteryManager.BATTERY_PROPERTY_MANUFACTURING_DATE:
 *     ...
 *     mContext.enforceCallingPermission(BATTERY_STATS, null);
 * ```
 *
 * When that flag is on, state of health is readable with no permission whatsoever. When it
 * is off, Java's switch fall-through carries it into the `BATTERY_STATS` check with
 * everything else. So whether this app can read a real, vendor-reported state of health
 * without any privileged transport is a per-device question with a genuine yes case — not
 * the flat no this codebase previously assumed. Every other restricted id enforces
 * unconditionally and has no such escape.
 *
 * @property id the integer passed to `getIntProperty`/`getLongProperty`.
 * @property publicSdk whether the constant appears in the public SDK. False means the
 *   value is still readable *if* the platform allows it; it only means this app cannot
 *   reference a named constant for it.
 * @property permissionGated whether AOSP unconditionally enforces `BATTERY_STATS`. False
 *   for [StateOfHealth] because its gate is conditional, and false for the public six
 *   because they have no gate at all.
 * @property kind which accessor returns this property. `BatteryManager` has two --
 *   `getLongProperty` and `getStringProperty` -- and they are not interchangeable. A
 *   string-typed property read through the numeric accessor returns the unsupported
 *   sentinel on every device, which is indistinguishable from the platform genuinely
 *   not having it. Types come from each constant's own AOSP documentation, where the
 *   three text ones say "as a string" outright.
 * @property identifying whether the value uniquely identifies this physical device.
 *   Only [SerialNumber] is: it returns a real per-cell serial once `BATTERY_STATS`
 *   is granted. The discovery report is meant to be shared, so an identifying value
 *   is recorded as present-but-withheld -- that the property reads at all is the
 *   finding, and the serial itself would turn a diagnostic into a fingerprint.
 */
enum class BatteryPropertyId(
    val id: Int,
    val publicSdk: Boolean,
    val permissionGated: Boolean,
    val kind: PropertyKind = PropertyKind.Numeric,
    val identifying: Boolean = false,
) {
    ChargeCounter(1, publicSdk = true, permissionGated = false),
    CurrentNow(2, publicSdk = true, permissionGated = false),
    CurrentAverage(3, publicSdk = true, permissionGated = false),
    Capacity(4, publicSdk = true, permissionGated = false),
    EnergyCounter(5, publicSdk = true, permissionGated = false),
    Status(6, publicSdk = true, permissionGated = false),

    ManufacturingDate(7, publicSdk = false, permissionGated = true),
    FirstUsageDate(8, publicSdk = false, permissionGated = true),
    ChargingPolicy(9, publicSdk = false, permissionGated = true),

    /**
     * The conditional one. See this enum's own doc for the AOSP switch that makes it so,
     * and [permissionGated] for why it is flagged false despite sitting among gated ids.
     */
    StateOfHealth(10, publicSdk = false, permissionGated = false),

    SerialNumber(11, publicSdk = false, permissionGated = true, kind = PropertyKind.Text, identifying = true),
    PartStatus(12, publicSdk = false, permissionGated = true),
    Manufacturer(13, publicSdk = false, permissionGated = true, kind = PropertyKind.Text),
    ModelName(14, publicSdk = false, permissionGated = true, kind = PropertyKind.Text),
    VoltageMinDesign(15, publicSdk = false, permissionGated = true),
    ;

    companion object {
        /**
         * Ids worth asking about on every device, in id order.
         *
         * All of them, deliberately. Probing a gated id costs one binder call that returns
         * a `SecurityException`, and the refusal is itself a finding: it distinguishes
         * "this platform knows the property and withholds it" from "this platform has
         * never heard of it", and those two want different things said to the user.
         */
        val probeSet: List<BatteryPropertyId> = entries.sortedBy { it.id }
    }
}

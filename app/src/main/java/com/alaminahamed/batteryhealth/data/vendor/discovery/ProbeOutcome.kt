package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * Which surface a finding came from. Provenance is part of the finding, not metadata
 * attached to it: "5000" from the manufacturer's power profile and "5000" measured off a
 * charge counter are different kinds of claim and must never be flattened together.
 */
enum class ProbeChannel {
    /** `BatteryManager.getIntProperty` / `getLongProperty` for a [BatteryPropertyId]. */
    Property,

    /** A key in the `ACTION_BATTERY_CHANGED` sticky broadcast's extras. */
    BroadcastExtra,

    /** An `<item>` in the platform's `power_profile.xml`. */
    PowerProfile,

    /**
     * A battery-adjacent reading from `PowerManager` -- thermal state, the platform's own
     * discharge prediction, battery saver. Public SDK, no permission, and absent from
     * `BatteryManager` entirely.
     */
    PowerManager,

    /**
     * A battery-related key in the `Settings` provider. Readable with no permission, and
     * the channel that found `protect_battery` -- Battery Protect's on/off state, which
     * this app otherwise reads only through the privileged tier.
     */
    Settings,
}

/**
 * What happened when one thing was asked for.
 *
 * The distinctions here are the entire point of the discovery pass. Collapsing them into
 * a nullable value would throw away exactly the information that makes the report worth
 * collecting: [Denied] and [Absent] both yield "no number", but the first says the
 * platform has this property and is withholding it, and the second says it has never
 * heard of it. Only the first is worth telling a user they could unlock.
 */
sealed interface ProbeOutcome {

    /** A value the probe is willing to stand behind. */
    data class Value(val raw: String) : ProbeOutcome

    /**
     * The platform answered, but with its documented "unsupported" sentinel rather than
     * data. `getIntProperty` returns `Integer.MIN_VALUE` here; some properties also use
     * -1, which is genuinely ambiguous for signed quantities — see [ProbeSentinels].
     */
    data object Absent : ProbeOutcome

    /**
     * A `SecurityException`: the property exists and this app is not allowed it. The most
     * informative outcome in the whole set, because it is the only one that tells the user
     * something is there to be had.
     */
    data object Denied : ProbeOutcome

    /**
     * The call failed in some other way — most often `NoSuchMethodError` on an API level
     * that predates the property, or a vendor framework throwing from its own
     * implementation. [reason] is the exception's simple name plus message, never a stack
     * trace: this is diagnostic text a user may end up reading or sharing.
     */
    data class Failed(val reason: String) : ProbeOutcome
}

/** One question asked, and its answer. */
data class ProbeResult(
    val channel: ProbeChannel,
    val key: String,
    val outcome: ProbeOutcome,
)

/**
 * The full picture of what this device's battery subsystem offers this app.
 *
 * Ordered by channel then key so two reports from the same device are comparable line for
 * line, and a diff between two Android versions on one phone shows only what actually
 * changed.
 */
data class BatteryDiscoveryReport(val results: List<ProbeResult>) {

    fun of(channel: ProbeChannel): List<ProbeResult> = results.filter { it.channel == channel }

    /** Everything the platform confirmed it has but will not hand over. */
    val denied: List<ProbeResult> get() = results.filter { it.outcome is ProbeOutcome.Denied }

    /** Everything that produced a usable value. */
    val values: List<ProbeResult> get() = results.filter { it.outcome is ProbeOutcome.Value }
}

/**
 * The values the platform uses to mean "I do not have this", separated from the values
 * that merely look like it.
 */
object ProbeSentinels {

    /**
     * `BatteryManager` documents `Integer.MIN_VALUE`/`Long.MIN_VALUE` as the unsupported
     * marker, and it is unambiguous: no real battery quantity lands there.
     */
    const val UNSUPPORTED = Long.MIN_VALUE

    /**
     * Whether [raw] is data rather than a sentinel, for [property] specifically.
     *
     * The per-property split is not fussiness. -1 is the documented "unsupported" value
     * for counters, but it is a perfectly real reading for a current: a one-microamp draw
     * measures -1, which this project has already observed on real hardware once and
     * disabled a working metric over. Only the unambiguous [UNSUPPORTED] sentinel
     * disqualifies a signed quantity.
     *
     * State of health is treated as a percentage: 0 would mean a completely dead cell,
     * which no device reports while running, so a zero there is a stub rather than a
     * measurement.
     */
    fun isData(property: BatteryPropertyId, raw: Long): Boolean {
        if (raw == UNSUPPORTED) return false
        return when (property) {
            // Signed quantities: -1 is a real reading, not a sentinel.
            BatteryPropertyId.CurrentNow,
            BatteryPropertyId.CurrentAverage,
            -> true

            // Percentages and counters: negative or zero is never a real reading.
            BatteryPropertyId.StateOfHealth,
            BatteryPropertyId.Capacity,
            BatteryPropertyId.ChargeCounter,
            BatteryPropertyId.EnergyCounter,
            BatteryPropertyId.VoltageMinDesign,
            -> raw > 0

            // Dates, enumerations and identifiers: -1 is the platform's "no value".
            BatteryPropertyId.Status,
            BatteryPropertyId.ManufacturingDate,
            BatteryPropertyId.FirstUsageDate,
            BatteryPropertyId.ChargingPolicy,
            BatteryPropertyId.SerialNumber,
            BatteryPropertyId.PartStatus,
            BatteryPropertyId.Manufacturer,
            BatteryPropertyId.ModelName,
            -> raw != -1L
        }
    }
}

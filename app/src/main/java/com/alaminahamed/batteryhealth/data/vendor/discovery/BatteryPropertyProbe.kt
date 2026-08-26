package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * Asks the platform for every [BatteryPropertyId] and records what came back.
 *
 * The reader is injected as a function rather than a `BatteryManager`, so the whole
 * classification — sentinel versus data, denial versus absence, failure versus either —
 * is provable on the JVM. That matters more here than usual: this class's entire job is
 * telling four indistinguishable-looking "no answer" cases apart, and getting that wrong
 * would either hide a readable property or advertise one that is not there.
 *
 * ## What a denial means
 *
 * A `SecurityException` is not a failure to handle quietly. It is the platform confirming
 * the property exists and is withheld, which is the one outcome that tells a user
 * something is available to unlock. It is recorded as [ProbeOutcome.Denied] and kept.
 *
 * ## Why state of health is worth asking every time
 *
 * AOSP gates most restricted properties on `BATTERY_STATS` unconditionally, but
 * [BatteryPropertyId.StateOfHealth] is behind a conditional: when the platform's
 * `stateOfHealthPublic()` flag is on, it is readable with no permission at all. So the
 * answer differs per device and per build, and asking is the only way to find out. See
 * [BatteryPropertyId] for the AOSP switch this rests on.
 */
class BatteryPropertyProbe(
    /**
     * Reads one property id, or throws. Expected to throw `SecurityException` when the
     * platform refuses, and to return [ProbeSentinels.UNSUPPORTED] when it has no value.
     */
    private val read: (Int) -> Long,
) {

    fun probe(): List<ProbeResult> = BatteryPropertyId.probeSet.map { property ->
        ProbeResult(
            channel = ProbeChannel.Property,
            key = property.name,
            outcome = outcomeFor(property),
        )
    }

    private fun outcomeFor(property: BatteryPropertyId): ProbeOutcome {
        val raw = try {
            read(property.id)
        } catch (e: SecurityException) {
            return ProbeOutcome.Denied
        } catch (t: Throwable) {
            // Deliberately broad, and only here. Below this point sits a binder call into
            // a vendor framework implementation: it can throw NoSuchMethodError on an API
            // level that predates the property, or any RuntimeException an OEM's own code
            // decides to raise. None of it is recoverable and none of it should abort the
            // rest of the sweep, which is the whole value of a discovery pass -- one
            // property failing must not cost the report every property after it.
            return ProbeOutcome.Failed(describe(t))
        }
        return if (ProbeSentinels.isData(property, raw)) {
            ProbeOutcome.Value(raw.toString())
        } else {
            ProbeOutcome.Absent
        }
    }

    /**
     * Exception class name plus message, never a stack trace. This text can reach a user's
     * screen and a shared diagnostic report, so it stays short and free of anything
     * identifying.
     */
    private fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "Throwable"
        val message = t.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }
}

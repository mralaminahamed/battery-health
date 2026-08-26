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
     * Reads one numeric property id, or throws. Expected to throw `SecurityException`
     * when the platform refuses, and to return [ProbeSentinels.UNSUPPORTED] when it has
     * no value.
     */
    private val readNumeric: (Int) -> Long,
    /**
     * Reads one string property id, or throws. `BatteryManager.getStringProperty` returns
     * null for a property the platform does not provide.
     *
     * Separate from [readNumeric] because the two accessors are not interchangeable: a
     * text property read numerically comes back as the unsupported sentinel on every
     * device, so the three string-typed ids would be reported permanently absent no
     * matter what the hardware actually holds. Defaulted so a caller that only cares
     * about numbers is not forced to supply one -- it then reports the text properties as
     * absent, which is honest for a probe that never asked.
     */
    private val readText: (Int) -> String? = { null },
) {

    fun probe(): List<ProbeResult> = BatteryPropertyId.probeSet.map { property ->
        ProbeResult(
            channel = ProbeChannel.Property,
            key = property.name,
            outcome = outcomeFor(property),
        )
    }

    private fun outcomeFor(property: BatteryPropertyId): ProbeOutcome = when (property.kind) {
        PropertyKind.Numeric -> numericOutcome(property)
        PropertyKind.Text -> textOutcome(property)
    }

    private fun numericOutcome(property: BatteryPropertyId): ProbeOutcome {
        val raw = try {
            readNumeric(property.id)
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
     * `getStringProperty` is itself behind a platform flag, so on a build that predates it
     * the call throws `NoSuchMethodError` and lands in [ProbeOutcome.Failed] -- which is
     * the accurate answer there, distinct from both "withheld" and "the battery has no
     * serial number".
     *
     * A blank string is treated as absence rather than a value. A vendor that returns ""
     * for an unpopulated field has told us nothing, and recording it as data would put an
     * empty row in the report that looks like a successful read.
     */
    private fun textOutcome(property: BatteryPropertyId): ProbeOutcome {
        val raw = try {
            readText(property.id)
        } catch (e: SecurityException) {
            return ProbeOutcome.Denied
        } catch (t: Throwable) {
            return ProbeOutcome.Failed(describe(t))
        }
        if (raw.isNullOrBlank()) return ProbeOutcome.Absent
        return ProbeOutcome.Value(if (property.identifying) REDACTED else raw)
    }

    /**
     * Exception class name plus message, never a stack trace. This text can reach a user's
     * screen and a shared diagnostic report, so it stays short and free of anything
     * identifying.
     */
    private companion object {
        /**
         * Stands in for a value that is real but must not be recorded.
         *
         * `BATTERY_PROPERTY_SERIAL_NUMBER` returns a genuine per-cell serial, and once
         * `BATTERY_STATS` is granted it reads successfully. The discovery report exists to
         * be read and shared as a diagnostic, so putting a hardware serial into it would
         * turn a debugging aid into a device fingerprint the user hands out without
         * realising. The fact that the property is *readable* is the finding worth
         * recording; the value itself is not.
         */
        const val REDACTED = "<present, withheld>"
    }

    private fun describe(t: Throwable): String {
        val name = t::class.simpleName ?: "Throwable"
        val message = t.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }
}

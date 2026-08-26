package com.alaminahamed.batteryhealth.data.vendor.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's whole job is telling four indistinguishable-looking "no answer" cases apart,
 * so each is pinned separately rather than through a single happy path.
 */
class BatteryPropertyProbeTest {

    private fun probeWith(read: (Int) -> Long) =
        BatteryPropertyProbe(readNumeric = read, readText = { "text" }).probe()

    private fun probeWith(numeric: (Int) -> Long, text: (Int) -> String?) =
        BatteryPropertyProbe(readNumeric = numeric, readText = text).probe()

    private val textProperties =
        BatteryPropertyId.entries.filter { it.kind == PropertyKind.Text }

    private val numericProperties =
        BatteryPropertyId.entries.filter { it.kind == PropertyKind.Numeric }

    private fun outcome(results: List<ProbeResult>, property: BatteryPropertyId) =
        results.first { it.key == property.name }.outcome

    @Test
    fun everyKnownPropertyIsAsked() {
        val asked = mutableListOf<Int>()
        probeWith(
            numeric = { id -> asked += id; ProbeSentinels.UNSUPPORTED },
            text = { id -> asked += id; null },
        )
        assertEquals(BatteryPropertyId.entries.map { it.id }.sorted(), asked.sorted())
    }

    /**
     * Each property must go through the accessor its own type demands. Reading a
     * string-typed id numerically returns the unsupported sentinel on every device, so
     * routing all fifteen through `getLongProperty` would report serial number,
     * manufacturer and model name as permanently absent no matter what the hardware holds
     * -- which is exactly what the first version of this probe did.
     */
    @Test
    fun textPropertiesGoThroughTheStringAccessorAndNumericOnesDoNot() {
        val numericAsked = mutableListOf<Int>()
        val textAsked = mutableListOf<Int>()
        probeWith(
            numeric = { id -> numericAsked += id; ProbeSentinels.UNSUPPORTED },
            text = { id -> textAsked += id; null },
        )
        assertEquals(textProperties.map { it.id }.sorted(), textAsked.sorted())
        assertEquals(numericProperties.map { it.id }.sorted(), numericAsked.sorted())
    }

    @Test
    fun serialManufacturerAndModelNameAreTheStringTypedOnes() {
        // Taken from each constant's own AOSP documentation, which says "as a string"
        // for exactly these three.
        assertEquals(
            listOf(
                BatteryPropertyId.SerialNumber,
                BatteryPropertyId.Manufacturer,
                BatteryPropertyId.ModelName,
            ),
            textProperties,
        )
    }

    @Test
    fun aStringPropertyReadsAsAValue() {
        val results = probeWith(
            numeric = { ProbeSentinels.UNSUPPORTED },
            text = { id -> if (id == BatteryPropertyId.Manufacturer.id) "ATL" else null },
        )
        assertEquals(ProbeOutcome.Value("ATL"), outcome(results, BatteryPropertyId.Manufacturer))
    }

    @Test
    fun aNullStringIsAbsent() {
        val results = probeWith(numeric = { ProbeSentinels.UNSUPPORTED }, text = { null })
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.SerialNumber))
    }

    /**
     * A vendor returning "" for an unpopulated field has told us nothing. Recording it as
     * a value would put an empty row in the report that looks like a successful read.
     */
    @Test
    fun aBlankStringIsAbsentNotAValue() {
        val results = probeWith(numeric = { ProbeSentinels.UNSUPPORTED }, text = { "   " })
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.ModelName))
    }

    /**
     * `getStringProperty` is itself behind a platform flag, so on an older build the call
     * throws `NoSuchMethodError`. That is distinct from both "withheld" and "this battery
     * has no serial number".
     */
    @Test
    fun aMissingStringAccessorIsAFailureNotAnAbsence() {
        val results = probeWith(
            numeric = { ProbeSentinels.UNSUPPORTED },
            text = { throw NoSuchMethodError("getStringProperty") },
        )
        assertTrue(outcome(results, BatteryPropertyId.SerialNumber) is ProbeOutcome.Failed)
    }

    @Test
    fun aDeniedStringPropertyIsDeniedNotAbsent() {
        val results = probeWith(
            numeric = { ProbeSentinels.UNSUPPORTED },
            text = { throw SecurityException("BATTERY_STATS") },
        )
        assertEquals(ProbeOutcome.Denied, outcome(results, BatteryPropertyId.Manufacturer))
    }

    @Test
    fun resultsAreInIdOrderSoTwoReportsCompareLineForLine() {
        val results = probeWith { 4000 }
        assertEquals(
            BatteryPropertyId.entries.sortedBy { it.id }.map { it.name },
            results.map { it.key },
        )
    }

    @Test
    fun aRealReadingIsRecordedAsAValue() {
        val results = probeWith { id -> if (id == BatteryPropertyId.Capacity.id) 87 else ProbeSentinels.UNSUPPORTED }
        assertEquals(ProbeOutcome.Value("87"), outcome(results, BatteryPropertyId.Capacity))
    }

    @Test
    fun theUnsupportedSentinelIsRecordedAsAbsentNotAsData() {
        val results = probeWith(numeric = { ProbeSentinels.UNSUPPORTED }, text = { null })
        assertTrue(results.all { it.outcome is ProbeOutcome.Absent })
    }

    /**
     * The most informative outcome in the set: it is the only one that tells a user
     * something exists to be unlocked. Collapsing it into "no value" would throw that away.
     */
    @Test
    fun aSecurityExceptionIsRecordedAsDeniedNotAbsent() {
        val results = probeWith(
            numeric = { throw SecurityException("BATTERY_STATS") },
            text = { throw SecurityException("BATTERY_STATS") },
        )
        assertTrue(results.all { it.outcome is ProbeOutcome.Denied })
    }

    /**
     * A property that predates nothing and simply is not implemented throws
     * `NoSuchMethodError` on older platforms, and vendor frameworks throw their own things.
     * None of that may abort the sweep -- the point of a discovery pass is that one
     * property failing does not cost the report every property after it.
     */
    @Test
    fun oneFailingPropertyDoesNotAbortTheRest() {
        val results = probeWith(
            numeric = { id ->
                if (id == BatteryPropertyId.ChargingPolicy.id) throw NoSuchMethodError("getIntProperty")
                5000
            },
            text = { "text" },
        )
        assertEquals(BatteryPropertyId.entries.size, results.size)
        val failed = outcome(results, BatteryPropertyId.ChargingPolicy)
        assertTrue(failed is ProbeOutcome.Failed)
        assertEquals(ProbeOutcome.Value("5000"), outcome(results, BatteryPropertyId.Capacity))
    }

    @Test
    fun aFailureRecordsTheReasonWithoutAStackTrace() {
        val results = probeWith { throw IllegalStateException("vendor hal offline") }
        val failed = outcome(results, BatteryPropertyId.Capacity) as ProbeOutcome.Failed
        assertEquals("IllegalStateException: vendor hal offline", failed.reason)
    }

    @Test
    fun aFailureWithNoMessageStillNamesTheException() {
        val results = probeWith { throw IllegalStateException() }
        val failed = outcome(results, BatteryPropertyId.Capacity) as ProbeOutcome.Failed
        assertEquals("IllegalStateException", failed.reason)
    }

    // ---- the -1 ambiguity ------------------------------------------------------------

    /**
     * -1 microamps is a real, tiny current draw. This project has already shipped a defect
     * where treating it as a sentinel disabled a working metric for a whole process
     * lifetime, on real hardware. It must stay data here.
     */
    @Test
    fun negativeOneIsARealCurrentReading() {
        val results = probeWith { -1 }
        assertEquals(ProbeOutcome.Value("-1"), outcome(results, BatteryPropertyId.CurrentNow))
        assertEquals(ProbeOutcome.Value("-1"), outcome(results, BatteryPropertyId.CurrentAverage))
    }

    /**
     * The mirror case: for a counter or a date, -1 is the platform's "no value" and must
     * not be reported as though the battery holds minus one microamp-hour.
     */
    @Test
    fun negativeOneIsASentinelForCountersAndDates() {
        val results = probeWith { -1 }
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.ChargeCounter))
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.ManufacturingDate))
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.FirstUsageDate))
    }

    /** A zero state of health would mean a completely dead cell, which no running phone reports. */
    @Test
    fun zeroStateOfHealthIsAStubNotAMeasurement() {
        val results = probeWith { 0 }
        assertEquals(ProbeOutcome.Absent, outcome(results, BatteryPropertyId.StateOfHealth))
    }

    /**
     * The case this whole probe exists for. On a build where AOSP's `stateOfHealthPublic()`
     * flag is on, state of health comes back with no permission at all -- see
     * [BatteryPropertyId] for the switch in `BatteryService` that allows it. The probe must
     * report that as a real reading rather than assuming the property is always withheld.
     */
    @Test
    fun stateOfHealthIsReadWhenThePlatformAllowsIt() {
        val results = probeWith(
            numeric = { id ->
                if (id == BatteryPropertyId.StateOfHealth.id) 94 else throw SecurityException()
            },
            text = { throw SecurityException() },
        )
        assertEquals(ProbeOutcome.Value("94"), outcome(results, BatteryPropertyId.StateOfHealth))
        // ...while its unconditionally-gated neighbours still report the denial.
        assertEquals(ProbeOutcome.Denied, outcome(results, BatteryPropertyId.ManufacturingDate))
    }

    // ---- the id registry itself ------------------------------------------------------

    /**
     * Ids are the one thing here that cannot be wrong without the whole report becoming
     * confident nonsense: a drifted id yields a real number about the wrong quantity.
     * Verified against AOSP `BatteryManager.java` and, for 1-6, the API 37 `android.jar`.
     */
    @Test
    fun propertyIdsMatchThePlatformConstants() {
        assertEquals(1, BatteryPropertyId.ChargeCounter.id)
        assertEquals(2, BatteryPropertyId.CurrentNow.id)
        assertEquals(3, BatteryPropertyId.CurrentAverage.id)
        assertEquals(4, BatteryPropertyId.Capacity.id)
        assertEquals(5, BatteryPropertyId.EnergyCounter.id)
        assertEquals(6, BatteryPropertyId.Status.id)
        assertEquals(7, BatteryPropertyId.ManufacturingDate.id)
        assertEquals(8, BatteryPropertyId.FirstUsageDate.id)
        assertEquals(9, BatteryPropertyId.ChargingPolicy.id)
        assertEquals(10, BatteryPropertyId.StateOfHealth.id)
        assertEquals(11, BatteryPropertyId.SerialNumber.id)
        assertEquals(12, BatteryPropertyId.PartStatus.id)
        assertEquals(13, BatteryPropertyId.Manufacturer.id)
        assertEquals(14, BatteryPropertyId.ModelName.id)
        assertEquals(15, BatteryPropertyId.VoltageMinDesign.id)
    }

    @Test
    fun noTwoPropertiesShareAnId() {
        val duplicates = BatteryPropertyId.entries.groupBy { it.id }.filterValues { it.size > 1 }
        assertEquals(emptyMap<Int, List<BatteryPropertyId>>(), duplicates)
    }

    /**
     * State of health is the only restricted property whose permission gate is
     * conditional. If a future edit marks it gated like its neighbours, the probe would
     * still ask -- but the reasoning recorded in the enum would have silently become
     * wrong, and this is what catches that.
     */
    @Test
    fun stateOfHealthIsTheOnlyConditionallyGatedRestrictedProperty() {
        val restrictedButNotGated = BatteryPropertyId.entries
            .filter { !it.publicSdk && !it.permissionGated }
        assertEquals(listOf(BatteryPropertyId.StateOfHealth), restrictedButNotGated)
    }

    @Test
    fun everyPublicSdkPropertyIsUngated() {
        val gatedPublic = BatteryPropertyId.entries.filter { it.publicSdk && it.permissionGated }
        assertEquals(emptyList<BatteryPropertyId>(), gatedPublic)
    }

    // ---- values that must never reach a shareable report -----------------------------

    /**
     * `BATTERY_PROPERTY_SERIAL_NUMBER` returns a real per-cell serial once `BATTERY_STATS`
     * is granted -- observed on a real device. The discovery report exists to be read and
     * shared, so recording the serial would turn a diagnostic into a device fingerprint
     * the user hands out without realising. That the property *reads* is the finding; the
     * value is not.
     */
    @Test
    fun theBatterySerialIsRecordedAsPresentButNotRecorded() {
        val results = probeWith(
            numeric = { ProbeSentinels.UNSUPPORTED },
            text = { id -> if (id == BatteryPropertyId.SerialNumber.id) "GH4305293AAA1L328GS05334C" else null },
        )
        val outcome = outcome(results, BatteryPropertyId.SerialNumber)
        assertTrue("serial must still register as readable", outcome is ProbeOutcome.Value)
        val recorded = (outcome as ProbeOutcome.Value).raw
        assertFalse("the serial itself must not appear", recorded.contains("GH4305293"))
    }

    /** Non-identifying text properties are recorded normally; redaction is not blanket. */
    @Test
    fun otherTextPropertiesAreNotRedacted() {
        val results = probeWith(
            numeric = { ProbeSentinels.UNSUPPORTED },
            text = { id -> if (id == BatteryPropertyId.Manufacturer.id) "ATL" else null },
        )
        assertEquals(ProbeOutcome.Value("ATL"), outcome(results, BatteryPropertyId.Manufacturer))
    }

    /** Serial number is the only property flagged identifying; the flag is not decorative. */
    @Test
    fun serialNumberIsTheOnlyIdentifyingProperty() {
        assertEquals(
            listOf(BatteryPropertyId.SerialNumber),
            BatteryPropertyId.entries.filter { it.identifying },
        )
    }
}

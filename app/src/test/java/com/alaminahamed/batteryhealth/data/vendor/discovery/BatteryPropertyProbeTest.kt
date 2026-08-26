package com.alaminahamed.batteryhealth.data.vendor.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's whole job is telling four indistinguishable-looking "no answer" cases apart,
 * so each is pinned separately rather than through a single happy path.
 */
class BatteryPropertyProbeTest {

    private fun probeWith(read: (Int) -> Long) = BatteryPropertyProbe(read).probe()

    private fun outcome(results: List<ProbeResult>, property: BatteryPropertyId) =
        results.first { it.key == property.name }.outcome

    @Test
    fun everyKnownPropertyIsAsked() {
        val asked = mutableListOf<Int>()
        probeWith { id -> asked += id; ProbeSentinels.UNSUPPORTED }
        assertEquals(BatteryPropertyId.entries.map { it.id }.sorted(), asked.sorted())
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
        val results = probeWith { ProbeSentinels.UNSUPPORTED }
        assertTrue(results.all { it.outcome is ProbeOutcome.Absent })
    }

    /**
     * The most informative outcome in the set: it is the only one that tells a user
     * something exists to be unlocked. Collapsing it into "no value" would throw that away.
     */
    @Test
    fun aSecurityExceptionIsRecordedAsDeniedNotAbsent() {
        val results = probeWith { throw SecurityException("BATTERY_STATS") }
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
        val results = probeWith { id ->
            if (id == BatteryPropertyId.ChargingPolicy.id) throw NoSuchMethodError("getIntProperty")
            5000
        }
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
        val results = probeWith { id ->
            if (id == BatteryPropertyId.StateOfHealth.id) 94 else throw SecurityException()
        }
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
}

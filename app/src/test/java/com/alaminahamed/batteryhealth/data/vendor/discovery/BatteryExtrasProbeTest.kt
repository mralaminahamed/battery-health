package com.alaminahamed.batteryhealth.data.vendor.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExtrasProbeTest {

    @Test
    fun aospKeysAreNotTreatedAsVendorAdditions() {
        assertFalse(BatteryExtrasProbe.isVendorSpecific("level"))
        assertFalse(BatteryExtrasProbe.isVendorSpecific("technology"))
        assertFalse(BatteryExtrasProbe.isVendorSpecific("android.os.extra.CYCLE_COUNT"))
    }

    /**
     * The shortcut this must not take. AOSP uses both bare keys (`level`) and namespaced
     * ones (`android.os.extra.CYCLE_COUNT`), so neither shape identifies a vendor
     * addition -- a prefix rule on `android.os.extra.` would misclassify every bare AOSP
     * key as one, which is most of them.
     */
    @Test
    fun keyShapeIsNotUsedToDecideVendorOrigin() {
        val bareAosp = "level"
        val namespacedAosp = "android.os.extra.CYCLE_COUNT"
        assertFalse(BatteryExtrasProbe.isVendorSpecific(bareAosp))
        assertFalse(BatteryExtrasProbe.isVendorSpecific(namespacedAosp))

        // A bare vendor key and a namespaced vendor key are both vendor keys.
        assertTrue(BatteryExtrasProbe.isVendorSpecific("asoc"))
        assertTrue(BatteryExtrasProbe.isVendorSpecific("com.samsung.android.extra.SOMETHING"))
    }

    @Test
    fun anUndocumentedKeyIsVendorSpecific() {
        assertTrue(BatteryExtrasProbe.isVendorSpecific("battery_type"))
        assertTrue(BatteryExtrasProbe.isVendorSpecific("mSavedBatteryAsoc"))
    }

    @Test
    fun everyExtraBecomesAResultOnTheBroadcastChannel() {
        val results = BatteryExtrasProbe.resultsFrom(mapOf("level" to "84", "asoc" to "97"))
        assertEquals(2, results.size)
        assertTrue(results.all { it.channel == ProbeChannel.BroadcastExtra })
    }

    @Test
    fun resultsAreSortedByKeySoTwoDevicesCompareLineForLine() {
        val results = BatteryExtrasProbe.resultsFrom(
            mapOf("voltage" to "4100", "asoc" to "97", "level" to "84"),
        )
        assertEquals(listOf("asoc", "level", "voltage"), results.map { it.key })
    }

    /**
     * A key that is present but empty is a different finding from a key that is not there
     * at all. Dropping the empty one would make two genuinely different devices produce
     * identical reports.
     */
    @Test
    fun aPresentButEmptyKeyIsAbsentRatherThanDropped() {
        val results = BatteryExtrasProbe.resultsFrom(
            mapOf("battery_type" to null, "charge_type" to "", "level" to "84"),
        )
        assertEquals(3, results.size)
        assertEquals(ProbeOutcome.Absent, results.first { it.key == "battery_type" }.outcome)
        assertEquals(ProbeOutcome.Absent, results.first { it.key == "charge_type" }.outcome)
        assertEquals(ProbeOutcome.Value("84"), results.first { it.key == "level" }.outcome)
    }

    @Test
    fun noExtrasAtAllYieldsNoResultsRatherThanFailing() {
        assertEquals(emptyList<ProbeResult>(), BatteryExtrasProbe.resultsFrom(emptyMap()))
    }

    // ---- report shaping --------------------------------------------------------------

    @Test
    fun theReportSeparatesDeniedFromAbsentAndFromValues() {
        val report = BatteryDiscoveryReport(
            listOf(
                ProbeResult(ProbeChannel.Property, "StateOfHealth", ProbeOutcome.Value("94")),
                ProbeResult(ProbeChannel.Property, "ManufacturingDate", ProbeOutcome.Denied),
                ProbeResult(ProbeChannel.Property, "PartStatus", ProbeOutcome.Absent),
                ProbeResult(ProbeChannel.BroadcastExtra, "level", ProbeOutcome.Value("84")),
            ),
        )
        assertEquals(listOf("ManufacturingDate"), report.denied.map { it.key })
        assertEquals(listOf("StateOfHealth", "level"), report.values.map { it.key })
        assertEquals(3, report.of(ProbeChannel.Property).size)
        assertEquals(1, report.of(ProbeChannel.BroadcastExtra).size)
    }
}

package com.alaminahamed.batteryhealth.data.repo

import android.os.BatteryManager
import android.os.PowerManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.alaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.alaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.alaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.alaminahamed.batteryhealth.data.framework.IntPropertyReader
import com.alaminahamed.batteryhealth.data.local.BatteryDatabase
import com.alaminahamed.batteryhealth.data.local.SessionEntity
import com.alaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.alaminahamed.batteryhealth.data.framework.PowerManagerSource
import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.VendorReadings
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import com.alaminahamed.batteryhealth.domain.CapacityMethod
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.isAvailable
import com.alaminahamed.batteryhealth.domain.valueOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SettingsStore reaches the app's real DataStore file (a fixed-name Context delegate with
 * no injectable alternative), so this suite clears it before and after to stay hermetic --
 * `BatteryRepository` itself no longer reads it, but other DataStore-backed classes in the
 * same process might.
 *
 * This suite used to also drive a fake [com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource]
 * through `appPower()`, `retryPrivilegedDump()` and the dump-derived ASOC/BSOH/Battery
 * Protect fields. That entire privileged tier -- an on-device adb client and a `su` shell
 * -- is gone, along with every method and field on `BatteryRepository` that existed only
 * to front it. What is left below tests the repository this app actually ships now: no
 * fake transport, because there is no transport to fake.
 */
class BatteryRepositoryTest {

    private lateinit var db: BatteryDatabase
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, BatteryDatabase::class.java).build()
        settings = SettingsStore(context)
        runBlocking { settings.clearForTesting() }
    }

    @After
    fun tearDown() {
        runBlocking { settings.clearForTesting() }
        db.close()
    }

    /**
     * @param powerProfileMah stands in for the device's own `power_profile.xml`
     *   declaration, exactly like `AppModule`'s real `@Named("powerProfileCapacityMah")`
     *   binding does in production -- `DeviceIdentity.Unknown` keeps the model table out
     *   of the resolution so this is the only source in play, which is what lets
     *   [measuredHealthPropagatesTheMappedCoulombChargeThroughToTheEstimator] and
     *   [measuredHealthFindsQualifyingChargesOutsideARecencyWindowFullOfOtherSessions] pin
     *   a deterministic design capacity with no `SettingsStore` override left to write one
     *   through.
     */
    private fun repository(powerProfileMah: Int? = null): BatteryRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val capabilities = CapabilityProbe(
            reader = IntPropertyReader { batteryManager.getIntProperty(it) },
        ).probe()
        return BatteryRepository(
            broadcasts = BatteryBroadcastSource(context),
            properties = BatteryManagerSource(batteryManager, capabilities, settings),
            sessionDao = db.sessions(),
            estimator = HealthEstimator(),
            designCapacity = DesignCapacityProvider(
                identity = DeviceIdentity.Unknown,
                powerProfileMah = powerProfileMah,
            ),
            // The real source, reading this device's own Settings provider. Not stubbed:
            // on a Samsung host it answers and on any other it reports Unsupported, and
            // both are outcomes these tests must tolerate rather than one being baked in.
            vendorSettings = VendorReadings.None,
            power = PowerManagerSource(context.getSystemService(PowerManager::class.java)),
            batteryManager = batteryManager,
        )
    }

    @Test
    fun snapshotCarriesLiveDataAndReportsPermissionGatedFieldsHonestly() = runBlocking {
        val snapshot = withTimeout(5_000) { repository().snapshots().first() }

        // Level is always present on real hardware; a present framework value is never
        // invented, so it must be Available rather than any absence variant.
        assertTrue(snapshot.levelPct.isAvailable)

        // Whatever the device does or does not expose through BatteryManager, every read
        // is a Reading and an unsupported property is Unsupported rather than a sentinel.
        assertTrue(snapshot.currentUa.isAvailable || snapshot.currentUa == Reading.Unsupported)
        assertTrue(
            snapshot.chargeCounterUah.isAvailable || snapshot.chargeCounterUah == Reading.Unsupported
        )

        // StateOfHealth is the one property AOSP checks a public "state of health is
        // public" flag for before enforcing BATTERY_STATS -- see FrameworkStateOfHealth's
        // own doc. Whether that flag is set is a fact about this specific test device's
        // build, not something this suite can pin, so both outcomes are honest here.
        assertTrue(
            snapshot.stateOfHealthPct is Reading.Available ||
                snapshot.stateOfHealthPct == Reading.NeedsPrivilegedAccess
        )

        // First-use and manufacturing date have no public-exception flag at all -- they
        // are unconditionally gated behind BATTERY_STATS, which this app can no longer be
        // granted at any price now that the adb/root shell tier is gone. Unsupported,
        // deterministically, on every device: there is nothing left to unlock.
        assertEquals(Reading.Unsupported, snapshot.firstUsageDateEpochDay)
        assertEquals(Reading.Unsupported, snapshot.manufacturingDateEpochDay)

        // Samsung's BSOH figure had exactly one source -- `dumpsys battery`, over the
        // now-removed shell tier -- and no public API replaces it.
        assertEquals(Reading.Unsupported, snapshot.bsohPct)
    }

    /**
     * `VendorReadings.None` (this suite's deterministic stand-in) reports both Battery
     * Protect fields as `Unsupported`, and with the privileged dump gone there is no
     * second source left to fall back to -- unlike before this task, when an absent
     * vendor key still had a dump-derived fallback to try.
     */
    @Test
    fun batteryProtectFieldsAreUnsupportedWithNoVendorKeyAndNoFurtherSource() = runBlocking {
        val snapshot = withTimeout(5_000) { repository().snapshots().first() }

        assertEquals(Reading.Unsupported, snapshot.protectBatteryModeEnabled)
        assertEquals(Reading.Unsupported, snapshot.protectionThresholdPct)
    }

    private fun chargeSessionWithoutCounter(id: Long, coulombUah: Long) = SessionEntity(
        id = id,
        type = "CHARGE",
        startedAtMs = 0,
        endedAtMs = 1_000,
        startLevelPct = 20,
        endLevelPct = 80,
        startCounterUah = null,
        endCounterUah = null,
        peakTempDeciC = null,
        avgMilliwatts = null,
        screenOnMs = 0,
        coulombUah = coulombUah,
    )

    @Test
    fun measuredHealthPropagatesTheMappedCoulombChargeThroughToTheEstimator() = runBlocking {
        // Three completed charge sessions, deltaLevelPct 60 on every one, with no counter
        // data at all -- only coulombUah is set. The estimator can only reach a report
        // here by reading the coulomb column, so a report proves the mapper carried
        // SessionEntity.coulombUah all the way through instead of dropping it.
        // fullUah = coulombUah * 100 / deltaLevelPct = 2_460_000 * 100 / 60 = 4_100_000,
        // i.e. 82% of the 5_000_000 uAh design capacity.
        db.sessions().insert(chargeSessionWithoutCounter(1, coulombUah = 2_460_000))
        db.sessions().insert(chargeSessionWithoutCounter(2, coulombUah = 2_460_000))
        db.sessions().insert(chargeSessionWithoutCounter(3, coulombUah = 2_460_000))

        val reading = withTimeout(5_000) { repository(powerProfileMah = 5_000).measuredHealth().first() }
        val report = reading.valueOrNull()
            ?: error("expected a health report, got $reading")

        assertEquals(82, report.healthPct)
        assertEquals(CapacityMethod.Coulomb, report.method)
        assertEquals(3, report.sessionsUsed)
    }

    private fun dischargeSession(id: Long, startedAtMs: Long, endedAtMs: Long) = SessionEntity(
        id = id,
        type = "DISCHARGE",
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        startLevelPct = 80,
        endLevelPct = 70,
        startCounterUah = null,
        endCounterUah = null,
        coulombUah = null,
        peakTempDeciC = null,
        avgMilliwatts = null,
        screenOnMs = 0,
    )

    /** deltaLevelPct 5: a top-up, well under the estimator's 20-point qualifying floor. */
    private fun narrowChargeSession(id: Long, startedAtMs: Long, endedAtMs: Long) = SessionEntity(
        id = id,
        type = "CHARGE",
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        startLevelPct = 50,
        endLevelPct = 55,
        startCounterUah = 1_000_000,
        endCounterUah = 1_050_000,
        coulombUah = null,
        peakTempDeciC = null,
        avgMilliwatts = null,
        screenOnMs = 0,
    )

    /** deltaLevelPct 60: an overnight-scale charge, comfortably qualifying. */
    private fun wideChargeSession(id: Long, startedAtMs: Long, endedAtMs: Long) = SessionEntity(
        id = id,
        type = "CHARGE",
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        startLevelPct = 20,
        endLevelPct = 80,
        startCounterUah = 1_000_000,
        endCounterUah = 4_000_000,
        coulombUah = null,
        peakTempDeciC = null,
        avgMilliwatts = null,
        screenOnMs = 0,
    )

    @Test
    fun measuredHealthFindsQualifyingChargesOutsideARecencyWindowFullOfOtherSessions() = runBlocking {
        // Fifty more-recent rows: 25 discharges and 25 charges too narrow to qualify
        // (deltaLevelPct 5, under the estimator's 20-point floor) -- someone who only
        // ever top-up charges, e.g. from a car mount or a desk pad. If the recency window
        // is spent before the charge/discharge type is known, these fifty rows alone
        // exhaust a limit-50 query and the three qualifying sessions below never reach
        // the estimator at all.
        var t = 10_000L
        repeat(25) { i ->
            db.sessions().insert(dischargeSession(id = 100L + i, startedAtMs = t, endedAtMs = t + 500))
            t += 1_000
        }
        repeat(25) { i ->
            db.sessions().insert(narrowChargeSession(id = 200L + i, startedAtMs = t, endedAtMs = t + 500))
            t += 1_000
        }

        // Three older, wide, qualifying charge sessions -- e.g. three overnight charges
        // from that same person. Present in the database throughout, but older than
        // every row above, so a recency-first window excludes them entirely.
        db.sessions().insert(wideChargeSession(id = 1, startedAtMs = 0, endedAtMs = 1_000))
        db.sessions().insert(wideChargeSession(id = 2, startedAtMs = 1_000, endedAtMs = 2_000))
        db.sessions().insert(wideChargeSession(id = 3, startedAtMs = 2_000, endedAtMs = 3_000))

        val reading = withTimeout(5_000) { repository(powerProfileMah = 5_000).measuredHealth().first() }
        val report = reading.valueOrNull()
            ?: error(
                "expected a health report: the database holds three qualifying charge " +
                    "sessions, just older than the fifty rows ahead of them. Got $reading."
            )

        assertEquals(100, report.healthPct)
        assertEquals(CapacityMethod.Counter, report.method)
        assertEquals(3, report.sessionsUsed)
    }
}

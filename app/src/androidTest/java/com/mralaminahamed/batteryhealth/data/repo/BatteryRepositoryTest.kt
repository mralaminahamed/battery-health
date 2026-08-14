package com.mralaminahamed.batteryhealth.data.repo

import android.os.BatteryManager
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.mralaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.mralaminahamed.batteryhealth.data.framework.IntPropertyReader
import com.mralaminahamed.batteryhealth.data.local.BatteryDatabase
import com.mralaminahamed.batteryhealth.data.local.SessionEntity
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuAvailability
import com.mralaminahamed.batteryhealth.data.settings.DesignCapacityProvider
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import com.mralaminahamed.batteryhealth.domain.AppPowerEntry
import com.mralaminahamed.batteryhealth.domain.CapacityMethod
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import com.mralaminahamed.batteryhealth.domain.UidKind
import com.mralaminahamed.batteryhealth.domain.isAvailable
import com.mralaminahamed.batteryhealth.domain.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SettingsStore reaches the app's real DataStore file (a fixed-name Context delegate with
 * no injectable alternative), so this suite clears it before and after to stay hermetic.
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
     * Configurable in every direction the new tests below need: [ShizukuAvailability]
     * starts wherever the caller sets it (defaulting to [ShizukuAvailability.NotInstalled]
     * so the original, unbound-state test below is unaffected), [dumpBattery] returns
     * whatever [nextDump] currently holds rather than a fixed value, and [dumpCallCount]
     * lets a test confirm a retry actually re-invoked the shell call rather than replaying
     * a cached result. Never touches the real Shizuku singleton, so these assertions hold
     * on their own logic regardless of whether the physical device running this test
     * happens to have Shizuku installed and bound already. `BatteryRepository` depends on
     * the [PrivilegedBatterySource] interface for exactly this reason; see its own doc.
     */
    private class FakePrivilegedBatterySource(
        initial: ShizukuAvailability = ShizukuAvailability.NotInstalled,
    ) : PrivilegedBatterySource {
        private val stateFlow = MutableStateFlow(initial)
        override val state: StateFlow<ShizukuAvailability> = stateFlow

        var nextDump: String? = null
        var dumpCallCount = 0
        var nextCheckin: String? = null
        var checkinCallCount = 0

        override suspend fun dumpBattery(): String? {
            dumpCallCount++
            return nextDump
        }

        override suspend fun dumpBatteryStatsCheckin(): String? {
            checkinCallCount++
            return nextCheckin
        }

        override fun requestPermission() = Unit
        override fun refresh() = Unit

        fun setAvailability(availability: ShizukuAvailability) {
            stateFlow.value = availability
        }
    }

    /** Every field this parser recognises, independently overridable so a test only
     * needs to spell out the one field it actually cares about changing. */
    private fun sampleDumpText(
        asoc: Int = 86,
        bsoh: Int = 95,
        firstUseDate: Int = 20_240_630,
        protectMode: Int = 1,
        protectionThresholdPct: Int = 80,
    ) = """
        mSavedBatteryAsoc: [$asoc]
        mSavedBatteryBsoh: $bsoh
        battery FirstUseDate: [$firstUseDate]
        mProtectBatteryMode: $protectMode
        mProtectionThreshold: $protectionThresholdPct
    """.trimIndent()

    private fun repository(shizuku: PrivilegedBatterySource = FakePrivilegedBatterySource()): BatteryRepository {
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
            // The device's own model is irrelevant here: an explicit override makes the
            // design capacity deterministic regardless of which device runs this test.
            designCapacity = DesignCapacityProvider(settings, model = "unused-in-this-test"),
            shizuku = shizuku,
        )
    }

    @Test
    fun snapshotCarriesLiveDataAndReportsThePrivilegedFieldsAsNeedingShizuku() = runBlocking {
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

        // StateOfHealth and FirstUsageDate require signature-level BATTERY_STATS and are
        // unreachable by any unprivileged app on any device. Reporting Unsupported here
        // would claim the device itself cannot supply the number, which is false -- the
        // privileged (Shizuku) tier can. NeedsShizuku is the honest answer.
        assertEquals(Reading.NeedsShizuku, snapshot.stateOfHealthPct)
        assertEquals(Reading.NeedsShizuku, snapshot.firstUsageDateEpochDay)

        // Critical 1: manufacturing date is a categorically different case from the two
        // above -- no dump this parser was built against has ever carried the field, so
        // "granting Shizuku might produce it" is already known false, unconditionally,
        // even in this unbound state. See manufacturingDateIsUnsupportedEvenWhenShizukuIsBoundAndDumped
        // below for the same pin in the bound state, per the task's explicit request for
        // both.
        assertEquals(Reading.Unsupported, snapshot.manufacturingDateEpochDay)
    }

    /**
     * Critical 1's other half: a real dump in hand, not just the unbound case above.
     * Regresses against `manufacturingDateEpochDay` ever being routed back through the
     * general `privilegedAbsence(dumpAvailable)` helper the other privileged fields use --
     * if it were, this would read `Unsupported` here too, by coincidence, since
     * `dumpAvailable` is true; the real regression this guards is the *unbound* case in
     * the test above, but pinning both states is what the task asked for explicitly, and
     * this is the state where "granting Shizuku produced no date" is the actual mechanism
     * being exercised, not merely a fallback default.
     */
    @Test
    fun manufacturingDateIsUnsupportedEvenWhenShizukuIsBoundAndDumped() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextDump = sampleDumpText()

        val snapshot = withTimeout(5_000) { repository(fake).snapshots().first() }

        // Sanity: the dump was actually used (proves this test reached the bound path at
        // all, not merely repeating the unbound assertion by accident).
        assertEquals(Reading.Available(86, Source.Privileged), snapshot.stateOfHealthPct)
        assertEquals(Reading.Unsupported, snapshot.manufacturingDateEpochDay)
    }

    /**
     * Important 1: a dump that fails while genuinely Bound must not be terminal. The fake
     * starts Bound with [FakePrivilegedBatterySource.nextDump] left `null` (a blank/failed
     * shell call), so the first collection pins every privileged field at `NeedsShizuku`
     * and [BatteryRepository.privilegedDumpFailed] to `true` -- exactly the bug: Shizuku
     * is demonstrably `Bound`, yet the rows read as if it never connected. Calling
     * [BatteryRepository.retryPrivilegedDump] and collecting again (same repository
     * instance, same fake, `dumpBattery` now returning a real dump) is what
     * `HealthViewModel.refreshShizuku` does on every `ON_RESUME` in production; recovering
     * here without ever toggling the bind state itself is the fix.
     */
    @Test
    fun retryPrivilegedDumpRecoversFromAFailedDumpWhileBound() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextDump = null
        val repo = repository(fake)

        val failedSnapshot = withTimeout(5_000) { repo.snapshots().first() }
        assertEquals(Reading.NeedsShizuku, failedSnapshot.stateOfHealthPct)
        assertTrue(repo.privilegedDumpFailed.value)

        fake.nextDump = sampleDumpText(asoc = 86)
        repo.retryPrivilegedDump()

        val recoveredSnapshot = withTimeout(5_000) { repo.snapshots().first() }
        assertEquals(Reading.Available(86, Source.Privileged), recoveredSnapshot.stateOfHealthPct)
        assertFalse(repo.privilegedDumpFailed.value)
    }

    /**
     * Critical 2: Battery Protect's mode and threshold are a live Samsung Settings toggle,
     * not a firmware-stable figure like ASOC/BSOH/first-use -- the bind-boundary-only
     * cadence those three correctly use must not also gate these two. Simulates the
     * task's own reachable flow (bound, then the user changes the setting elsewhere, then
     * returns) by changing what the fake's next dump reports and calling
     * [BatteryRepository.retryPrivilegedDump] -- the same call `HealthViewModel` makes
     * from every `ON_RESUME` -- without the bind state itself ever toggling.
     */
    @Test
    fun retryPrivilegedDumpRefreshesBatteryProtectFieldsOnDemand() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextDump = sampleDumpText(protectMode = 1, protectionThresholdPct = 80)
        val repo = repository(fake)

        val before = withTimeout(5_000) { repo.snapshots().first() }
        assertEquals(Reading.Available(true, Source.Privileged), before.protectBatteryModeEnabled)
        assertEquals(Reading.Available(80, Source.Privileged), before.protectionThresholdPct)

        // The user turned Battery Protect off in Settings while this app was backgrounded.
        fake.nextDump = sampleDumpText(protectMode = 0, protectionThresholdPct = 80)
        repo.retryPrivilegedDump()

        val after = withTimeout(5_000) { repo.snapshots().first() }
        assertEquals(Reading.Available(false, Source.Privileged), after.protectBatteryModeEnabled)
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
        settings.setDesignCapacityOverride(5_000)

        // Three completed charge sessions, deltaLevelPct 60 on every one, with no counter
        // data at all -- only coulombUah is set. The estimator can only reach a report
        // here by reading the coulomb column, so a report proves the mapper carried
        // SessionEntity.coulombUah all the way through instead of dropping it.
        // fullUah = coulombUah * 100 / deltaLevelPct = 2_460_000 * 100 / 60 = 4_100_000,
        // i.e. 82% of the 5_000_000 uAh design capacity.
        db.sessions().insert(chargeSessionWithoutCounter(1, coulombUah = 2_460_000))
        db.sessions().insert(chargeSessionWithoutCounter(2, coulombUah = 2_460_000))
        db.sessions().insert(chargeSessionWithoutCounter(3, coulombUah = 2_460_000))

        val reading = withTimeout(5_000) { repository().measuredHealth().first() }
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
        settings.setDesignCapacityOverride(5_000)

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

        val reading = withTimeout(5_000) { repository().measuredHealth().first() }
        val report = reading.valueOrNull()
            ?: error(
                "expected a health report: the database holds three qualifying charge " +
                    "sessions, just older than the fifty rows ahead of them. Got $reading."
            )

        assertEquals(100, report.healthPct)
        assertEquals(CapacityMethod.Counter, report.method)
        assertEquals(3, report.sessionsUsed)
    }

    private fun sampleCheckinText() = """
        9,0,i,vers,36,1179864,BP4A.251205.006,BP4A.251205.006
        9,0,i,uid,1000,com.samsung.android.provider.filterprovider
        9,0,i,uid,2000,com.android.shell
        9,0,i,uid,10106,com.sec.android.app.camera
        9,2000,l,pwi,uid,422,1,0,0
        9,1000,l,pwi,uid,6.23,1,0,0
        9,10106,l,pwi,uid,15.6,1,0,0
        9,0,l,pwi,cpu,17.5,0,0,0
    """.trimIndent()

    @Test
    fun appPowerReportsNeedsShizukuWhenNotBound() = runBlocking {
        val repo = repository(FakePrivilegedBatterySource(initial = ShizukuAvailability.NotInstalled))

        val reading = withTimeout(5_000) { repo.appPower().first() }

        assertEquals(Reading.NeedsShizuku, reading)
        assertFalse(repo.appPowerFailed.value)
    }

    /**
     * The real end-to-end path: a bound checkin call, parsed and reduced into rows,
     * sorted descending, each classified into the right [UidKind] -- the shell uid (2000)
     * kept apart from the real app (10106) and the system uid (1000), exactly the
     * distinction the Apps screen's whole design exists to preserve. The system-wide
     * component breakdown row (uid "0", component "cpu") must not leak into the per-uid
     * list as a phantom "uid 0" entry.
     */
    @Test
    fun appPowerParsesARealBoundCheckinIntoClassifiedSortedEntries() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextCheckin = sampleCheckinText()
        val repo = repository(fake)

        val reading = withTimeout(5_000) { repo.appPower().first() }

        val entries = reading.valueOrNull() ?: error("expected Available, got $reading")
        assertEquals(listOf(2000, 10106, 1000), entries.map { it.uid })
        assertEquals(UidKind.Shell, entries.first { it.uid == 2000 }.kind)
        assertEquals(UidKind.App, entries.first { it.uid == 10106 }.kind)
        assertEquals(UidKind.System, entries.first { it.uid == 1000 }.kind)
        assertFalse(repo.appPowerFailed.value)
    }

    /**
     * Mirrors [retryPrivilegedDumpRecoversFromAFailedDumpWhileBound] for the checkin call:
     * a bound tier whose checkin call fails must not be terminal, and must be
     * distinguishable (via [BatteryRepository.appPowerFailed]) from having never bound at
     * all.
     */
    @Test
    fun retryPrivilegedDumpRecoversFromAFailedCheckinWhileBound() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextCheckin = null
        val repo = repository(fake)

        val failedReading = withTimeout(5_000) { repo.appPower().first() }
        assertEquals(Reading.NeedsShizuku, failedReading)
        assertTrue(repo.appPowerFailed.value)

        fake.nextCheckin = sampleCheckinText()
        repo.retryPrivilegedDump()

        val recoveredReading = withTimeout(5_000) { repo.appPower().first() }
        assertTrue(recoveredReading.isAvailable)
        assertFalse(repo.appPowerFailed.value)
    }

    /**
     * A checkin call that succeeds (a real, non-null string came back) but happens to
     * contain no "pwi,uid" rows is a real, honest "nothing recorded yet" -- Available with
     * an empty list -- never NeedsShizuku, which would wrongly imply granting Shizuku
     * again might change the answer.
     */
    @Test
    fun appPowerIsAvailableWithAnEmptyListWhenTheCheckinHasNoPerUidRows() = runBlocking {
        val fake = FakePrivilegedBatterySource(initial = ShizukuAvailability.Bound)
        fake.nextCheckin = "9,0,i,vers,36,1179864,BP4A.251205.006,BP4A.251205.006\n"
        val repo = repository(fake)

        val reading = withTimeout(5_000) { repo.appPower().first() }

        assertEquals(Reading.Available(emptyList<AppPowerEntry>(), Source.Privileged), reading)
        assertFalse(repo.appPowerFailed.value)
    }
}

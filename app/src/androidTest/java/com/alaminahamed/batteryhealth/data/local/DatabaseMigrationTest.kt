package com.alaminahamed.batteryhealth.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorded history is the one thing in this app that cannot be regenerated, so a schema
 * change must prove -- not just claim -- that existing rows survive it. This creates a
 * real version-1 database, inserts a row the way version-1 code would have, migrates it,
 * and reads the row back rather than merely checking that the migration runs.
 *
 * This migration only ever ALTERs `sessions`, so `samples` and `capacity_estimates` are
 * seeded and checked too, even though today's ADD COLUMN cannot disturb either. The point
 * is the template: the next schema change to `sessions` may need a table recreation
 * instead, and `capacity_estimates` has ON DELETE CASCADE on sessions(id) -- a recreation
 * that drops and reinserts sessions rows with new ids would silently wipe every stored
 * estimate. A test that only ever looked at `sessions` would keep passing while that
 * happened.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BatteryDatabase::class.java,
    )

    @Test
    fun migrationOneToTwoPreservesExistingRowsInEveryTableAndAddsANullableColumn() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, type, startedAtMs, endedAtMs, startLevelPct, endLevelPct,
                     startCounterUah, endCounterUah, peakTempDeciC, avgMilliwatts, screenOnMs)
                VALUES
                    (1, 'CHARGE', 1000, 9000, 20, 80, 1000000, 3580000, 380, 9000, 500)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO samples
                    (id, timestampMs, levelPct, chargeCounterUah, currentUa, voltageMv,
                     tempDeciC, statusCode, pluggedCode, screenOn, sessionId)
                VALUES
                    (1, 4000, 55, 2000000, 500000, 3900, 300, 2, 2, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO capacity_estimates
                    (id, sessionId, measuredFullUah, deltaLevelPct, method, trustworthy)
                VALUES
                    (1, 1, 4300000, 60, 'COUNTER', 1)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        val sessionCursor = migrated.query("SELECT * FROM sessions WHERE id = 1")
        assertTrue("the pre-migration session row must still be there", sessionCursor.moveToFirst())
        assertEquals("CHARGE", sessionCursor.getString(sessionCursor.getColumnIndexOrThrow("type")))
        assertEquals(1000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("startedAtMs")))
        assertEquals(9000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("endedAtMs")))
        assertEquals(20, sessionCursor.getInt(sessionCursor.getColumnIndexOrThrow("startLevelPct")))
        assertEquals(80, sessionCursor.getInt(sessionCursor.getColumnIndexOrThrow("endLevelPct")))
        assertEquals(1_000_000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("startCounterUah")))
        assertEquals(3_580_000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("endCounterUah")))
        assertEquals(380, sessionCursor.getInt(sessionCursor.getColumnIndexOrThrow("peakTempDeciC")))
        assertEquals(9000, sessionCursor.getInt(sessionCursor.getColumnIndexOrThrow("avgMilliwatts")))
        assertEquals(500L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("screenOnMs")))

        val coulombIndex = sessionCursor.getColumnIndexOrThrow("coulombUah")
        // Undefaulted ADD COLUMN backfills existing rows with NULL, not 0: an unmeasured
        // coulomb value and a measured zero are different facts.
        assertTrue(
            "a pre-migration row's new column must read null, not 0",
            sessionCursor.isNull(coulombIndex),
        )
        sessionCursor.close()

        // This migration never touches these two tables, but the assertion is here so the
        // next author who copies this test as a template for a `sessions`-recreating
        // migration is forced to prove the CASCADE-linked estimate survives too.
        val sampleCursor = migrated.query("SELECT * FROM samples WHERE id = 1")
        assertTrue("the pre-migration sample row must still be there", sampleCursor.moveToFirst())
        assertEquals(4000L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("timestampMs")))
        assertEquals(55, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("levelPct")))
        assertEquals(1L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("sessionId")))
        sampleCursor.close()

        val estimateCursor = migrated.query("SELECT * FROM capacity_estimates WHERE id = 1")
        assertTrue("the pre-migration estimate row must still be there", estimateCursor.moveToFirst())
        assertEquals(1L, estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("sessionId")))
        assertEquals(
            4_300_000L,
            estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("measuredFullUah")),
        )
        assertEquals("COUNTER", estimateCursor.getString(estimateCursor.getColumnIndexOrThrow("method")))
        estimateCursor.close()

        migrated.close()
    }

    /**
     * MIGRATION_2_3 only ever ALTERs `samples`, but every table is seeded and checked here
     * for the same reason migrationOneToTwo... does: the point is the template surviving to
     * the next schema change, not just this one. The device this was found on had a real
     * version-2 database with real recorded sessions -- confirmed directly by installing the
     * fixed build over it and reading the migrated rows back -- so this test is pinning
     * behaviour already proven on hardware, not hoping it works for the first time here.
     */
    @Test
    fun migrationTwoToThreePreservesExistingRowsInEveryTableAndAddsANullableColumn() {
        helper.createDatabase(TEST_DB_2_3, 2).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, type, startedAtMs, endedAtMs, startLevelPct, endLevelPct,
                     startCounterUah, endCounterUah, peakTempDeciC, avgMilliwatts, screenOnMs,
                     coulombUah)
                VALUES
                    (1, 'CHARGE', 1000, 9000, 20, 80, 1000000, 3580000, 380, 9000, 500, 2460000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO samples
                    (id, timestampMs, levelPct, chargeCounterUah, currentUa, voltageMv,
                     tempDeciC, statusCode, pluggedCode, screenOn, sessionId)
                VALUES
                    (1, 4000, 55, 2000000, 500000, 3900, 300, 2, 2, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO capacity_estimates
                    (id, sessionId, measuredFullUah, deltaLevelPct, method, trustworthy)
                VALUES
                    (1, 1, 4300000, 60, 'COUNTER', 1)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_2_3, 3, true, MIGRATION_2_3)

        val sampleCursor = migrated.query("SELECT * FROM samples WHERE id = 1")
        assertTrue("the pre-migration sample row must still be there", sampleCursor.moveToFirst())
        assertEquals(4000L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("timestampMs")))
        assertEquals(55, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("levelPct")))
        assertEquals(500_000, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("currentUa")))
        assertEquals(1L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("sessionId")))

        val rawUnitsIndex = sampleCursor.getColumnIndexOrThrow("currentRawUnits")
        // Undefaulted ADD COLUMN backfills existing rows with NULL: a row recorded before
        // this migration genuinely has no raw reading to backfill, and inventing one would
        // be exactly the fabricated data this whole task exists to avoid.
        assertTrue(
            "a pre-migration row's new column must read null, not a fabricated raw value",
            sampleCursor.isNull(rawUnitsIndex),
        )
        sampleCursor.close()

        // This migration never touches these two tables, but they are checked anyway --
        // the point is the template, not this specific column.
        val sessionCursor = migrated.query("SELECT * FROM sessions WHERE id = 1")
        assertTrue("the pre-migration session row must still be there", sessionCursor.moveToFirst())
        assertEquals("CHARGE", sessionCursor.getString(sessionCursor.getColumnIndexOrThrow("type")))
        assertEquals(2_460_000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("coulombUah")))
        sessionCursor.close()

        val estimateCursor = migrated.query("SELECT * FROM capacity_estimates WHERE id = 1")
        assertTrue("the pre-migration estimate row must still be there", estimateCursor.moveToFirst())
        assertEquals(1L, estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("sessionId")))
        assertEquals(
            4_300_000L,
            estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("measuredFullUah")),
        )
        estimateCursor.close()

        migrated.close()
    }

    /**
     * MIGRATION_3_4 only ever ALTERs `samples`, but every table is seeded and checked
     * here for the same reason the two tests above do: the point is the template, not
     * just this one column.
     */
    @Test
    fun migrationThreeToFourPreservesExistingRowsInEveryTableAndAddsANullableColumn() {
        helper.createDatabase(TEST_DB_3_4, 3).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, type, startedAtMs, endedAtMs, startLevelPct, endLevelPct,
                     startCounterUah, endCounterUah, peakTempDeciC, avgMilliwatts, screenOnMs,
                     coulombUah)
                VALUES
                    (1, 'CHARGE', 1000, 9000, 20, 80, 1000000, 3580000, 380, 9000, 500, 2460000)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO samples
                    (id, timestampMs, levelPct, chargeCounterUah, currentUa, voltageMv,
                     tempDeciC, statusCode, pluggedCode, screenOn, sessionId, currentRawUnits)
                VALUES
                    (1, 4000, 55, 2000000, 500000, 3900, 300, 2, 2, 1, 1, 2409)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO capacity_estimates
                    (id, sessionId, measuredFullUah, deltaLevelPct, method, trustworthy)
                VALUES
                    (1, 1, 4300000, 60, 'COUNTER', 1)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB_3_4, 4, true, MIGRATION_3_4)

        val sampleCursor = migrated.query("SELECT * FROM samples WHERE id = 1")
        assertTrue("the pre-migration sample row must still be there", sampleCursor.moveToFirst())
        assertEquals(4000L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("timestampMs")))
        assertEquals(55, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("levelPct")))
        assertEquals(500_000, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("currentUa")))
        assertEquals(2_409, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("currentRawUnits")))
        assertEquals(1L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("sessionId")))

        val validatedIndex = sampleCursor.getColumnIndexOrThrow("currentScaleValidated")
        // Undefaulted ADD COLUMN backfills existing rows with NULL: a row recorded before
        // this migration has no provenance to backfill, and NULL -- not a fabricated
        // false or true -- is the only honest value for "we don't know which rule wrote
        // this row's currentUa".
        assertTrue(
            "a pre-migration row's new column must read null, not a fabricated true/false",
            sampleCursor.isNull(validatedIndex),
        )
        sampleCursor.close()

        // This migration never touches these two tables, but they are checked anyway --
        // the point is the template, not this specific column.
        val sessionCursor = migrated.query("SELECT * FROM sessions WHERE id = 1")
        assertTrue("the pre-migration session row must still be there", sessionCursor.moveToFirst())
        assertEquals("CHARGE", sessionCursor.getString(sessionCursor.getColumnIndexOrThrow("type")))
        assertEquals(2_460_000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("coulombUah")))
        sessionCursor.close()

        val estimateCursor = migrated.query("SELECT * FROM capacity_estimates WHERE id = 1")
        assertTrue("the pre-migration estimate row must still be there", estimateCursor.moveToFirst())
        assertEquals(1L, estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("sessionId")))
        assertEquals(
            4_300_000L,
            estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("measuredFullUah")),
        )
        estimateCursor.close()

        migrated.close()
    }

    /**
     * Room chains the three registered migrations (1->2, 2->3, 3->4) automatically when
     * asked to go straight from version 1 to the current version, but that chaining is
     * not itself pinned by any of the three tests above -- each only proves its own
     * single step in isolation. This seeds a genuine version-1 database (the same shape
     * migrationOneToTwo... does) and migrates it straight to 4, passing every registered
     * migration at once, so a future change that breaks the chain (an incompatible
     * ordering, a missing registration) fails here even if each individual step still
     * passes on its own.
     */
    @Test
    fun migrationOneToFourAppliesEveryRegisteredMigrationInSequence() {
        helper.createDatabase(TEST_DB_1_4, 1).apply {
            execSQL(
                """
                INSERT INTO sessions
                    (id, type, startedAtMs, endedAtMs, startLevelPct, endLevelPct,
                     startCounterUah, endCounterUah, peakTempDeciC, avgMilliwatts, screenOnMs)
                VALUES
                    (1, 'CHARGE', 1000, 9000, 20, 80, 1000000, 3580000, 380, 9000, 500)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO samples
                    (id, timestampMs, levelPct, chargeCounterUah, currentUa, voltageMv,
                     tempDeciC, statusCode, pluggedCode, screenOn, sessionId)
                VALUES
                    (1, 4000, 55, 2000000, 500000, 3900, 300, 2, 2, 1, 1)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO capacity_estimates
                    (id, sessionId, measuredFullUah, deltaLevelPct, method, trustworthy)
                VALUES
                    (1, 1, 4300000, 60, 'COUNTER', 1)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_1_4,
            4,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        )

        val sessionCursor = migrated.query("SELECT * FROM sessions WHERE id = 1")
        assertTrue("the pre-migration session row must still be there", sessionCursor.moveToFirst())
        assertEquals("CHARGE", sessionCursor.getString(sessionCursor.getColumnIndexOrThrow("type")))
        assertEquals(1_000_000L, sessionCursor.getLong(sessionCursor.getColumnIndexOrThrow("startCounterUah")))
        assertTrue(
            "coulombUah did not exist at version 1; it must backfill to null, not 0",
            sessionCursor.isNull(sessionCursor.getColumnIndexOrThrow("coulombUah")),
        )
        sessionCursor.close()

        val sampleCursor = migrated.query("SELECT * FROM samples WHERE id = 1")
        assertTrue("the pre-migration sample row must still be there", sampleCursor.moveToFirst())
        assertEquals(4000L, sampleCursor.getLong(sampleCursor.getColumnIndexOrThrow("timestampMs")))
        assertEquals(500_000, sampleCursor.getInt(sampleCursor.getColumnIndexOrThrow("currentUa")))
        assertTrue(
            "currentRawUnits did not exist at version 1; it must backfill to null, not 0",
            sampleCursor.isNull(sampleCursor.getColumnIndexOrThrow("currentRawUnits")),
        )
        assertTrue(
            "currentScaleValidated did not exist at version 1; it must backfill to null, " +
                "not a fabricated true/false",
            sampleCursor.isNull(sampleCursor.getColumnIndexOrThrow("currentScaleValidated")),
        )
        sampleCursor.close()

        val estimateCursor = migrated.query("SELECT * FROM capacity_estimates WHERE id = 1")
        assertTrue("the pre-migration estimate row must still be there", estimateCursor.moveToFirst())
        assertEquals(
            4_300_000L,
            estimateCursor.getLong(estimateCursor.getColumnIndexOrThrow("measuredFullUah")),
        )
        estimateCursor.close()

        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-1-2-test.db"
        const val TEST_DB_2_3 = "migration-2-3-test.db"
        const val TEST_DB_3_4 = "migration-3-4-test.db"
        const val TEST_DB_1_4 = "migration-1-4-test.db"
    }
}

package com.mralaminahamed.batteryhealth.data.local

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

    private companion object {
        const val TEST_DB = "migration-1-2-test.db"
    }
}

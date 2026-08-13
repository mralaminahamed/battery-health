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
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BatteryDatabase::class.java,
    )

    @Test
    fun migrationOneToTwoPreservesExistingSessionRowsAndAddsANullableColumn() {
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
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        val cursor = migrated.query("SELECT * FROM sessions WHERE id = 1")
        assertTrue("the pre-migration row must still be there", cursor.moveToFirst())
        assertEquals("CHARGE", cursor.getString(cursor.getColumnIndexOrThrow("type")))
        assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("startedAtMs")))
        assertEquals(9000L, cursor.getLong(cursor.getColumnIndexOrThrow("endedAtMs")))
        assertEquals(20, cursor.getInt(cursor.getColumnIndexOrThrow("startLevelPct")))
        assertEquals(80, cursor.getInt(cursor.getColumnIndexOrThrow("endLevelPct")))
        assertEquals(1_000_000L, cursor.getLong(cursor.getColumnIndexOrThrow("startCounterUah")))
        assertEquals(3_580_000L, cursor.getLong(cursor.getColumnIndexOrThrow("endCounterUah")))
        assertEquals(380, cursor.getInt(cursor.getColumnIndexOrThrow("peakTempDeciC")))
        assertEquals(9000, cursor.getInt(cursor.getColumnIndexOrThrow("avgMilliwatts")))
        assertEquals(500L, cursor.getLong(cursor.getColumnIndexOrThrow("screenOnMs")))

        val coulombIndex = cursor.getColumnIndexOrThrow("coulombUah")
        // Undefaulted ADD COLUMN backfills existing rows with NULL, not 0: an unmeasured
        // coulomb value and a measured zero are different facts.
        assertTrue("a pre-migration row's new column must read null, not 0", cursor.isNull(coulombIndex))
        cursor.close()
        migrated.close()
    }

    private companion object {
        const val TEST_DB = "migration-1-2-test.db"
    }
}

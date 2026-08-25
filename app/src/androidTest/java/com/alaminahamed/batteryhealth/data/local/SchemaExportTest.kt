package com.alaminahamed.batteryhealth.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the schema is actually exported. Without this, the first real migration would
 * be written blind, and history is the one thing in this app that cannot be regenerated.
 */
@RunWith(AndroidJUnit4::class)
class SchemaExportTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BatteryDatabase::class.java,
    )

    @Test
    fun versionOneSchemaIsExportedAndOpens() {
        helper.createDatabase(TEST_DB, 1).close()
    }

    private companion object {
        const val TEST_DB = "schema-export-test.db"
    }
}

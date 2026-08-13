package com.mralaminahamed.batteryhealth.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the integrated-charge column a later task will populate by integrating current over
 * time. No fallbackToDestructiveMigration is configured anywhere in this app, so this is a
 * real migration rather than a rebuild: recorded history is the one thing here that cannot
 * be regenerated.
 *
 * The column is left nullable and without a DEFAULT clause on purpose: an unmeasured coulomb
 * value and a measured zero are different facts, and the estimator's own
 * `coulombUah?.takeIf { it > 0 }` depends on being able to tell them apart. SQLite's
 * ADD COLUMN without DEFAULT already backfills existing rows with NULL, so no separate
 * backfill step is needed.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN coulombUah INTEGER")
    }
}

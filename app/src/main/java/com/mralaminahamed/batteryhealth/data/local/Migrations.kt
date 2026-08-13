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

/**
 * Adds the untouched CURRENT_NOW register value, alongside the already-scaled `currentUa`,
 * so a completed session can cross-validate the device's real unit scale against the charge
 * counter (Task 15 -- see `CurrentScaleDetector.fromCounterAgreement`). Left nullable and
 * without a DEFAULT for the same reason `coulombUah` is in MIGRATION_1_2: SQLite's
 * ADD COLUMN without DEFAULT already backfills existing rows with NULL, and every row
 * recorded before this migration genuinely has no raw reading to backfill -- inventing one
 * would be exactly the kind of fabricated data this whole task exists to avoid.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN currentRawUnits INTEGER")
    }
}

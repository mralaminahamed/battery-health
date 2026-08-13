package com.mralaminahamed.batteryhealth.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SampleEntity::class, SessionEntity::class, CapacityEstimateEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class BatteryDatabase : RoomDatabase() {
    abstract fun samples(): SampleDao
    abstract fun sessions(): SessionDao
    abstract fun estimates(): EstimateDao
}

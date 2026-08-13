package com.mralaminahamed.batteryhealth.di

import android.content.Context
import androidx.room.Room
import com.mralaminahamed.batteryhealth.data.local.BatteryDatabase
import com.mralaminahamed.batteryhealth.data.local.EstimateDao
import com.mralaminahamed.batteryhealth.data.local.MIGRATION_1_2
import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * No fallbackToDestructiveMigration. Recorded history cannot be regenerated, so a
     * schema change must ship a real migration or fail loudly in development.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BatteryDatabase =
        Room.databaseBuilder(context, BatteryDatabase::class.java, "battery-health.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideSampleDao(db: BatteryDatabase): SampleDao = db.samples()

    @Provides fun provideSessionDao(db: BatteryDatabase): SessionDao = db.sessions()

    @Provides fun provideEstimateDao(db: BatteryDatabase): EstimateDao = db.estimates()
}

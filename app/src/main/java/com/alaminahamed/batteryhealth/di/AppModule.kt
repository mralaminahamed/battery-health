package com.alaminahamed.batteryhealth.di

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import com.alaminahamed.batteryhealth.data.framework.BatteryProperty
import com.alaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.alaminahamed.batteryhealth.data.framework.IntPropertyReader
import com.alaminahamed.batteryhealth.sampling.NowMs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context

    @Provides
    @Singleton
    fun provideBatteryManager(@ApplicationContext context: Context): BatteryManager =
        context.getSystemService(BatteryManager::class.java)

    @Provides
    @Singleton
    fun providePowerManager(@ApplicationContext context: Context): PowerManager =
        context.getSystemService(PowerManager::class.java)

    @Provides
    @Singleton
    fun provideCapabilities(batteryManager: BatteryManager): Set<BatteryProperty> =
        CapabilityProbe(
            reader = IntPropertyReader { batteryManager.getIntProperty(it) },
        ).probe()

    @Provides
    @Named("deviceModel")
    fun provideDeviceModel(): String = Build.MODEL ?: ""

    @Provides
    @Singleton
    fun provideNowMs(): NowMs = NowMs { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

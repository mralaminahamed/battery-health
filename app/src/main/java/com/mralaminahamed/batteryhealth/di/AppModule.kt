package com.mralaminahamed.batteryhealth.di

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.mralaminahamed.batteryhealth.data.framework.BatteryProperty
import com.mralaminahamed.batteryhealth.data.framework.CapabilityProbe
import com.mralaminahamed.batteryhealth.data.framework.IntPropertyReader
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
}

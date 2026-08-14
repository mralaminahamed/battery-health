package com.mralaminahamed.batteryhealth.di

import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.mralaminahamed.batteryhealth.data.privileged.ShizukuGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrivilegedModule {

    /**
     * `BatteryRepository` and `HealthViewModel` depend on the interface, not this
     * concrete type, so a test can substitute a fake without touching Shizuku's real
     * (global, process-wide) static API -- see `PrivilegedBatterySource`'s own doc.
     */
    @Provides
    @Singleton
    fun providePrivilegedBatterySource(gateway: ShizukuGateway): PrivilegedBatterySource = gateway
}

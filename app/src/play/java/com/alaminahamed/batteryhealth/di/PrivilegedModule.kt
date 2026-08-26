package com.alaminahamed.batteryhealth.di

import com.alaminahamed.batteryhealth.data.privileged.NoPrivilegedTier
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * The Play flavour's privileged wiring: there isn't any.
 *
 * `AdbShell`, `RootShell` and `AdbGateway` are never constructed here, so this build
 * contains no code path that opens a socket. See [NoPrivilegedTier] for why that trade is
 * worth making now and was not before.
 */
@Module
@InstallIn(SingletonComponent::class)
object PrivilegedModule {

    @Provides
    @Singleton
    fun providePrivilegedBatterySource(tier: NoPrivilegedTier): PrivilegedBatterySource = tier

    /**
     * False, and the UI reads it rather than inferring from an availability that would
     * simply never become Ready.
     *
     * Without it the unlock card would sit there offering `adb tcpip` forever, on a build
     * where running that command achieves precisely nothing -- the transport it would
     * enable is not compiled in. Telling a user to run a command that cannot work is worse
     * than saying nothing.
     */
    @Provides
    @Named("privilegedTierSupported")
    fun providePrivilegedTierSupported(): Boolean = false
}

package com.mralaminahamed.batteryhealth.di

import com.mralaminahamed.batteryhealth.data.privileged.AdbGateway
import com.mralaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.mralaminahamed.batteryhealth.data.privileged.RootShell
import com.mralaminahamed.batteryhealth.data.privileged.adb.AdbKeyPair
import com.mralaminahamed.batteryhealth.data.privileged.adb.AdbShell
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrivilegedModule {

    /**
     * `BatteryRepository` and the ViewModels depend on the interface, not this concrete
     * type, so a test can substitute a fake without touching either real transport --
     * see `PrivilegedBatterySource`'s own doc.
     */
    @Provides
    @Singleton
    fun providePrivilegedBatterySource(gateway: AdbGateway): PrivilegedBatterySource = gateway

    /** `su`-based; needs nothing to construct beyond its own no-arg constructor. */
    @Provides
    @Singleton
    fun provideRootShell(): RootShell = RootShell()

    /**
     * [AdbShell] deliberately has no `@Inject` constructor of its own (see its own doc):
     * the port it dials lives in [SettingsStore], a `Flow`, so reading it here needs
     * `runBlocking` -- the same pattern [BootReceiver][com.mralaminahamed.batteryhealth.sampling.BootReceiver]
     * already uses to read a `SettingsStore` flow from a non-suspend call site. This runs
     * once, at Hilt's singleton construction time (app start), not per-call, so the
     * blocking read of one DataStore preference is a one-time cost, not a hot-path one.
     * [AdbKeyPair.loadOrCreate] is likewise synchronous -- AndroidKeystore's own API is
     * blocking, not suspend.
     */
    @Provides
    @Singleton
    fun provideAdbShell(settingsStore: SettingsStore): AdbShell =
        AdbShell(port = runBlocking { settingsStore.adbPort.first() }, signer = AdbKeyPair.loadOrCreate())
}

package com.alaminahamed.batteryhealth.di

import com.alaminahamed.batteryhealth.data.privileged.AdbGateway
import com.alaminahamed.batteryhealth.data.privileged.PrivilegedBatterySource
import com.alaminahamed.batteryhealth.data.privileged.RootShell
import com.alaminahamed.batteryhealth.data.privileged.adb.AdbKeyPair
import com.alaminahamed.batteryhealth.data.privileged.adb.AdbShell
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PrivilegedModule {

    /**
     * True: this flavour compiles the real transports in. See the Play flavour's own
     * copy of this module for why that build reports false instead.
     */
    @Provides
    @Named("privilegedTierSupported")
    fun providePrivilegedTierSupported(): Boolean = true


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
     * the port it dials lives in [SettingsStore], a `Flow`. Wired up as a suspend provider
     * rather than a value read once here -- [AdbShell] calls it fresh on every reconnect,
     * so a port change written via `SettingsStore.setAdbPort` takes effect on the next
     * `refresh()` instead of silently doing nothing until the process dies. No
     * `runBlocking` needed: nothing here has to read the flow eagerly at Hilt's singleton
     * construction time any more. [AdbKeyPair.loadOrCreate] is still synchronous --
     * AndroidKeystore's own API is blocking, not suspend.
     */
    @Provides
    @Singleton
    fun provideAdbShell(settingsStore: SettingsStore): AdbShell =
        AdbShell(portProvider = { settingsStore.adbPort.first() }, signer = AdbKeyPair.loadOrCreate())
}

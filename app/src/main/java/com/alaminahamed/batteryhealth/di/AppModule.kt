package com.alaminahamed.batteryhealth.di

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkManager
import com.alaminahamed.batteryhealth.data.framework.BatteryProperty
import com.alaminahamed.batteryhealth.data.vendor.DeviceIdentity
import com.alaminahamed.batteryhealth.data.vendor.PowerProfileReader
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

    /**
     * The four `Build` fields that identify this phone, captured once.
     *
     * `Build.MODEL` alone is not enough, and that is an empirical finding rather than a
     * precaution: Google's own published device list shows the OnePlus 13 shipping as
     * `PJZ110`, `CPH2649`, `CPH2653` and `CPH2655` across regions while `Build.DEVICE`
     * stays `OP5D55L1`. Any table keyed on model alone loses most of that vendor.
     *
     * Each field is null-guarded. They are declared non-null in the SDK, but they are
     * ultimately system properties and a stripped or unusual build can leave one empty;
     * an empty string flows through to [Vendor.Unknown] and no vendor rule applies, which
     * is the correct outcome rather than a crash at injection time.
     */
    @Provides
    @Singleton
    fun provideDeviceIdentity(): DeviceIdentity = DeviceIdentity(
        manufacturer = Build.MANUFACTURER ?: "",
        brand = Build.BRAND ?: "",
        model = Build.MODEL ?: "",
        device = Build.DEVICE ?: "",
    )

    /**
     * The device's own declared battery capacity, read once at injection time.
     *
     * Read once rather than per query because it is a static resource of the platform
     * image: it cannot change while the app is running, and re-parsing an XML resource on
     * every capacity lookup would buy nothing. Null when this device does not offer it or
     * offered something implausible -- see [PowerProfileCapacity] for why that is an
     * ordinary outcome rather than an error.
     */
    @Provides
    @Singleton
    @Named("powerProfileCapacityMah")
    fun providePowerProfileCapacityMah(@ApplicationContext context: Context): Int? =
        PowerProfileReader(context).batteryCapacityMah()

    @Provides
    @Singleton
    fun provideNowMs(): NowMs = NowMs { System.currentTimeMillis() }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

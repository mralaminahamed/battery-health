package com.mralaminahamed.batteryhealth

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mralaminahamed.batteryhealth.sampling.SamplingScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BatteryHealthApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var samplingScheduler: SamplingScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        samplingScheduler.scheduleBaseline()
        // The charge recorder itself is started/stopped from SettingsStore.setRecorderEnabled
        // (a foreground call site) and restarted after reboot by BootReceiver -- there is
        // nothing to wire up here. ACTION_POWER_CONNECTED/DISCONNECTED are not on Android's
        // implicit-broadcast exception list, so no receiver for them is registered anywhere,
        // dynamically or otherwise; see ChargeRecorderService's class doc for the full history.
    }
}

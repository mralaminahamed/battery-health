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
        // The charge recorder itself is armed/disarmed from SettingsStore.setRecorderEnabled,
        // not from here: there is no broadcast receiver to register. ACTION_POWER_CONNECTED
        // and ACTION_POWER_DISCONNECTED are not on Android's implicit-broadcast exception
        // list -- that has been the rule since API 26 -- so PowerReceiver was removed in
        // favour of a WorkManager job constrained on setRequiresCharging(true); see
        // ChargeRecorderWorker and SamplingScheduler.
    }
}

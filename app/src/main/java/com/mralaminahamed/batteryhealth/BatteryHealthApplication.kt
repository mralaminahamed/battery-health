package com.mralaminahamed.batteryhealth

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mralaminahamed.batteryhealth.sampling.PowerReceiver
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
        registerPowerReceiver()
    }

    /**
     * The manifest-declared `PowerReceiver` (AndroidManifest.xml) is the documented,
     * zero-process entry point, but on current platform builds implicit-broadcast
     * background-execution limits can skip manifest receivers for
     * ACTION_POWER_CONNECTED/DISCONNECTED with "Background execution not allowed" even
     * while the app is in the foreground -- verified empirically on the Pixel_10 (API 37)
     * emulator via `dumpsys activity broadcasts history`. A context-registered receiver is
     * not subject to that restriction, so this is a required supplementary registration,
     * not a redundant one: without it, the recorder's trigger never fires on this
     * platform. Harmless if the manifest path also fires on some other OS/OEM build --
     * ChargeRecorderService's start/stop are idempotent, so a duplicate call is a no-op.
     */
    private fun registerPowerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, PowerReceiver(), filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

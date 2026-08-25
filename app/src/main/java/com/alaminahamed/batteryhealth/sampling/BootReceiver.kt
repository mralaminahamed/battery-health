package com.alaminahamed.batteryhealth.sampling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.alaminahamed.batteryhealth.data.settings.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Restarts the charge recorder after a reboot, if the setting is still on. A manifest
 * receiver is the right home for this one: unlike `ACTION_POWER_CONNECTED`, boot
 * completion *is* on Android's implicit-broadcast exception list, and starting a
 * foreground service in direct response to it is a long-standing, explicitly recognised
 * exemption from the background-start restriction -- unlike the charging-constrained
 * WorkManager job tried previously, this start context is one the platform actually
 * grants.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = runBlocking { settings.recorderEnabled.first() }
        if (enabled) ChargeRecorderService.start(context)
    }
}

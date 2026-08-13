package com.mralaminahamed.batteryhealth.sampling

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mralaminahamed.batteryhealth.data.settings.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The recorder is opt-in (Task 6): `recorderEnabled` defaults to false, and nothing here
 * may start the service unless the user has switched it on.
 */
@AndroidEntryPoint
class PowerReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsStore

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                val enabled = runBlocking { settings.recorderEnabled.first() }
                if (!enabled) return
                try {
                    ChargeRecorderService.start(context)
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    // API 34+ can refuse a background start. The baseline worker keeps
                    // running, so history survives; only this session's fine-grained
                    // samples are lost.
                    Log.w(TAG, "Charge recorder could not start in background", e)
                }
            }

            Intent.ACTION_POWER_DISCONNECTED -> ChargeRecorderService.stop(context)
        }
    }

    private companion object {
        const val TAG = "PowerReceiver"
    }
}

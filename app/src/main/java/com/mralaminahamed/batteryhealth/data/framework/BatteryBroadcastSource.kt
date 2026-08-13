package com.mralaminahamed.batteryhealth.data.framework

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACTION_BATTERY_CHANGED cannot be declared in a manifest, so this is the only route
 * to live battery state. Registering returns the sticky Intent immediately, so the
 * flow emits current state before any change occurs.
 */
@Singleton
class BatteryBroadcastSource @Inject constructor(
    private val context: Context,
) {
    fun broadcasts(): Flow<BatteryBroadcast> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { trySend(it.toBatteryBroadcast()) }
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { trySend(it.toBatteryBroadcast()) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate()

    private fun Intent.toBatteryBroadcast(): BatteryBroadcast = BatteryBroadcast.fromExtras(
        level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
        scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        status = getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
        plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        voltageMv = getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
        temperatureDeciC = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
        technology = getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
        present = getBooleanExtra(BatteryManager.EXTRA_PRESENT, false),
        cycleCount = getIntExtra(EXTRA_CYCLE_COUNT, 0),
    )

    private companion object {
        /** BatteryManager.EXTRA_CYCLE_COUNT, inlined because it is API 34+. */
        const val EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT"
    }
}

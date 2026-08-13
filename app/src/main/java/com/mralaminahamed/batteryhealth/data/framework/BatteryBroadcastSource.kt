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
    /**
     * Every intent this receiver observes, delivered in full -- never deliberately
     * coalesced into "just the latest" the way [broadcasts] is. `callbackFlow`'s
     * default channel capacity buffers emissions a collector hasn't caught up to yet;
     * only once that buffer is actually full does `trySend()` start failing, and the
     * intent it was called with -- the newest arrival, not anything already buffered
     * -- is the one silently dropped. That is a real limit, not an absolute
     * guarantee, but not a practical one here: exhausting the default buffer would
     * need on the order of tens of `ACTION_BATTERY_CHANGED` broadcasts arriving
     * faster than this flow's single collector can process a plug/unplug transition,
     * which battery-changed broadcasts are nowhere near frequent enough to do.
     *
     * Needed by anything that treats a transition itself as the payload, not just the
     * resulting state -- edge detection for plug/unplug being the motivating case. Two
     * events can legitimately land milliseconds apart (a cable wiggle produces
     * disconnect-then-reconnect, or the reverse, well inside the time a slow collector
     * takes to process the first one). [broadcasts] below is correct for every existing
     * reader, which only ever wants the newest state and would otherwise pile up stale
     * intermediate ones -- but conflation on that path would silently collapse exactly
     * the two-events-in-a-row case an edge detector exists to see, turning a real
     * disconnect into silence rather than a visible gap. A downstream `buffer()` cannot
     * undo an upstream `conflate()`, so this has to be a separate, earlier tap.
     */
    fun rawBroadcasts(): Flow<BatteryBroadcast> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let { trySend(it.toBatteryBroadcast()) }
            }
        }
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { trySend(it.toBatteryBroadcast()) }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** Conflated: for readers that only ever want the newest reading. */
    fun broadcasts(): Flow<BatteryBroadcast> = rawBroadcasts().conflate()

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

package com.mralaminahamed.batteryhealth.sampling

import android.os.PowerManager
import com.mralaminahamed.batteryhealth.data.framework.BatteryBroadcastSource
import com.mralaminahamed.batteryhealth.data.framework.BatteryManagerSource
import com.mralaminahamed.batteryhealth.data.local.SampleDao
import com.mralaminahamed.batteryhealth.data.local.SampleEntity
import com.mralaminahamed.batteryhealth.domain.ChargeState
import com.mralaminahamed.batteryhealth.domain.PlugType
import com.mralaminahamed.batteryhealth.domain.valueOrNull
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures exactly one row of battery state. Columns that the device cannot supply are
 * stored as NULL rather than 0, so a later query can tell "not measured" from "measured
 * as zero" — the estimator depends on that distinction.
 *
 * The row is skipped entirely when level is absent: `samples.levelPct` is a non-nullable
 * Int in the schema (Task 5), so there is no partial row to write in that case — not a
 * choice this class makes, but a consequence of what the table can store. In practice
 * ACTION_BATTERY_CHANGED always carries EXTRA_LEVEL/EXTRA_SCALE, so this path is a
 * defensive guard against a malformed broadcast, not a case expected to fire in the field.
 */
@Singleton
class SampleWriter @Inject constructor(
    private val broadcasts: BatteryBroadcastSource,
    private val properties: BatteryManagerSource,
    private val sampleDao: SampleDao,
    private val powerManager: PowerManager,
    private val nowMs: NowMs,
) {
    suspend fun writeOne(sessionId: Long? = null): Long? {
        val broadcast = broadcasts.broadcasts().first()
        val level = broadcast.levelPct ?: return null

        return sampleDao.insert(
            SampleEntity(
                timestampMs = nowMs.get(),
                levelPct = level,
                chargeCounterUah = properties.chargeCounterUah().valueOrNull(),
                currentUa = properties.currentUa().valueOrNull(),
                voltageMv = broadcast.voltageMv,
                tempDeciC = broadcast.temperatureDeciC,
                statusCode = broadcast.chargeState.toStatusCode(),
                pluggedCode = broadcast.plugType.toPluggedCode(),
                screenOn = powerManager.isInteractive,
                sessionId = sessionId,
            )
        )
    }

    private fun ChargeState.toStatusCode(): Int = when (this) {
        ChargeState.Unknown -> 1
        ChargeState.Charging -> 2
        ChargeState.Discharging -> 3
        ChargeState.NotCharging -> 4
        ChargeState.Full -> 5
    }

    private fun PlugType.toPluggedCode(): Int = when (this) {
        PlugType.None -> 0
        PlugType.Ac -> 1
        PlugType.Usb -> 2
        PlugType.Wireless -> 4
        PlugType.Dock -> 8
    }
}

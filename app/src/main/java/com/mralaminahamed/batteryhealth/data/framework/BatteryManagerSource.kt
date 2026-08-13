package com.mralaminahamed.batteryhealth.data.framework

import android.os.BatteryManager
import com.mralaminahamed.batteryhealth.domain.Reading
import com.mralaminahamed.batteryhealth.domain.Source
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point reads of BatteryManager's integer properties, gated by the capability set so
 * an unsupported property yields Unsupported rather than a sentinel dressed as data.
 */
@Singleton
class BatteryManagerSource @Inject constructor(
    private val batteryManager: BatteryManager,
    private val capabilities: Set<BatteryProperty>,
) {
    fun chargeCounterUah(): Reading<Long> = read(BatteryProperty.ChargeCounter) { it.toLong() }

    fun currentUa(): Reading<Int> = read(BatteryProperty.CurrentNow) { it }

    fun chargeTimeRemainingMs(): Reading<Long> {
        val remaining = batteryManager.computeChargeTimeRemaining()
        return if (remaining > 0) {
            Reading.Available(remaining, Source.Framework)
        } else {
            Reading.Unsupported
        }
    }

    private fun <T> read(property: BatteryProperty, transform: (Int) -> T): Reading<T> {
        if (property !in capabilities) return Reading.Unsupported
        val raw = batteryManager.getIntProperty(property.id)
        if (raw == Int.MIN_VALUE) return Reading.Unsupported
        return Reading.Available(transform(raw), Source.Framework)
    }
}

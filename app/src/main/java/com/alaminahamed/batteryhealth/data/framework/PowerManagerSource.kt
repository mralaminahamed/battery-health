package com.alaminahamed.batteryhealth.data.framework

import android.os.Build
import android.os.PowerManager
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.VisibleForTesting
import com.alaminahamed.batteryhealth.domain.Reading
import com.alaminahamed.batteryhealth.domain.Source
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery-adjacent readings `PowerManager` exposes and `BatteryManager` does not.
 *
 * Both are public SDK and neither needs a permission, so they are available on every
 * device with no setup at all. The discovery sweep found them; this is where they become
 * something the app shows rather than something it merely records.
 *
 * Thermal status earns its place in a battery app specifically: sustained heat is the
 * largest driver of capacity loss after time, and this is the only public signal for
 * throttling beyond the raw temperature already in the broadcast.
 */
@Singleton
class PowerManagerSource @Inject constructor(
    private val powerManager: PowerManager,
) {
    /**
     * Thermal throttling state, `THERMAL_STATUS_NONE` through `THERMAL_STATUS_SHUTDOWN`.
     *
     * API 29, guarded rather than called unconditionally -- minSdk is 26 and is not being
     * raised to make this check unnecessary. Below that the method does not exist on the
     * device and calling it throws `NoSuchMethodError` out of whichever coroutine touched
     * it, which is the same reasoning `BatteryManagerSource.chargeTimeRemainingMs`
     * already carries for its own gate.
     */
    fun thermalStatus(): Reading<Int> {
        if (!isThermalStatusSupported(Build.VERSION.SDK_INT)) return Reading.Unsupported
        val status = try {
            powerManager.currentThermalStatus
        } catch (t: Throwable) {
            return Reading.Unsupported
        }
        return if (status in PowerManager.THERMAL_STATUS_NONE..PowerManager.THERMAL_STATUS_SHUTDOWN) {
            Reading.Available(status, Source.Framework)
        } else {
            Reading.Unsupported
        }
    }

    /**
     * The platform's own estimate of time left on this charge, in milliseconds.
     *
     * API 31. Distinct from `computeChargeTimeRemaining`, which answers the opposite
     * question and is only meaningful while charging.
     *
     * A non-positive duration is refused rather than shown. The API returns null when it
     * has no estimate, and a zero or negative one would render as "0 minutes remaining" on
     * a phone that is working fine -- a sentinel presented as a prediction.
     */
    fun dischargePredictionMs(): Reading<Long> {
        if (!isDischargePredictionSupported(Build.VERSION.SDK_INT)) return Reading.Unsupported
        val remaining = try {
            powerManager.batteryDischargePrediction?.toMillis()
        } catch (t: Throwable) {
            return Reading.Unsupported
        }
        return if (remaining != null && remaining > 0L) {
            Reading.Available(remaining, Source.Framework)
        } else {
            Reading.Unsupported
        }
    }

    companion object {
        /** Pure so the API floors are provable on the JVM at levels this host cannot run. */
        @VisibleForTesting
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
        fun isThermalStatusSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.Q

        @VisibleForTesting
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
        fun isDischargePredictionSupported(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S
    }
}

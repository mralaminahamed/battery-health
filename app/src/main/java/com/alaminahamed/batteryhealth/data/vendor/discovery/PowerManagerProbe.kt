package com.alaminahamed.batteryhealth.data.vendor.discovery

/**
 * The battery-adjacent facts `PowerManager` exposes, which `BatteryManager` does not
 * carry at all.
 *
 * All of these are public SDK and none needs a permission, which is why they belong in a
 * sweep whose premise is "ask for everything reachable". They were missing from the first
 * pass: it probed `BatteryManager` and the broadcast thoroughly and simply did not look
 * here, so a device's thermal state and its own discharge prediction went unrecorded
 * despite being free to read.
 *
 * Each is gated on the API level that introduced it. Below that level the method does not
 * exist on the device and calling it throws `NoSuchMethodError` from whichever coroutine
 * touched it — this project's `chargeTimeRemainingMs` already carries the same guard for
 * the same reason, and minSdk stays at 26 rather than being raised to make the checks
 * unnecessary.
 */
object PowerManagerProbe {

    /** Every reading this channel can offer, with the API level each needs. */
    enum class Reading(val key: String, val minApi: Int) {
        /**
         * The platform's own estimate of time left on this charge, as a duration. Distinct
         * from `computeChargeTimeRemaining`, which answers the opposite question and is
         * only meaningful while charging.
         */
        DischargePrediction("batteryDischargePrediction", minApi = 31),

        /**
         * Whether the prediction above is personalised to this device's usage or is a
         * generic estimate. Worth recording alongside it: the same number means different
         * things depending on the answer, and presenting a generic estimate as a
         * personalised one would overstate what the device knows.
         */
        DischargePredictionPersonalized("batteryDischargePredictionPersonalized", minApi = 31),

        /**
         * Thermal throttling state, `THERMAL_STATUS_NONE` through `THERMAL_STATUS_SHUTDOWN`.
         * Directly relevant to a battery app: sustained heat is the single largest driver
         * of capacity loss, and this is the only public signal for it beyond the raw
         * temperature already in the broadcast.
         */
        ThermalStatus("currentThermalStatus", minApi = 29),

        /** Whether the system is in battery saver. */
        PowerSaveMode("powerSaveMode", minApi = 21),
    }

    /**
     * Turns raw accessor results into probe results.
     *
     * [read] is passed a [Reading] and returns its value as text, or null when the device
     * has nothing. It is expected to throw for a reading the platform cannot serve; the
     * classification of that is handled here rather than at each call site so all four
     * behave identically.
     *
     * [sdkInt] is passed in rather than read from `Build` so the API gating is provable on
     * the JVM at every level, including the ones this environment cannot run.
     *
     * A reading below its API floor is [ProbeOutcome.Absent], not [ProbeOutcome.Failed]:
     * the platform genuinely does not have it, which is an ordinary fact about an older
     * device rather than something going wrong. The accessor is never called in that case,
     * so no `NoSuchMethodError` is provoked in the first place.
     */
    fun resultsFrom(sdkInt: Int, read: (Reading) -> String?): List<ProbeResult> =
        Reading.entries.map { reading ->
            ProbeResult(
                channel = ProbeChannel.PowerManager,
                key = reading.key,
                outcome = outcomeFor(sdkInt, reading, read),
            )
        }

    private fun outcomeFor(
        sdkInt: Int,
        reading: Reading,
        read: (Reading) -> String?,
    ): ProbeOutcome {
        if (sdkInt < reading.minApi) return ProbeOutcome.Absent
        val raw = try {
            read(reading)
        } catch (e: SecurityException) {
            return ProbeOutcome.Denied
        } catch (t: Throwable) {
            val name = t::class.simpleName ?: "Throwable"
            val message = t.message?.takeIf { it.isNotBlank() }
            return ProbeOutcome.Failed(if (message == null) name else "$name: $message")
        }
        return raw?.takeIf { it.isNotBlank() }?.let { ProbeOutcome.Value(it) } ?: ProbeOutcome.Absent
    }
}

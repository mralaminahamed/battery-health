package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity

/**
 * How much charge left the battery over a window, from this app's own recorded samples.
 *
 * Deliberately reads `samples` rather than a discharge-session table: no such session is
 * ever written, and this does not need one -- `samples` already carry level, charge
 * counter and plug state on every row, so adding a discharge-session recorder would be a
 * second mechanism for data already present.
 *
 * Resolution is 15 minutes while unplugged -- `BaselineSampleWorker`'s period, since
 * `ChargeRecorderService`'s 5-second sampler only runs with the cable in. Ample for a
 * one-day window, and the reason this is an estimate's denominator rather than a headline
 * metric of its own.
 */
object WindowDischarge {

    /**
     * `null` means "not known", `0.0` means "measured, and nothing drained". They are
     * different facts and the caller must be able to tell them apart: the first is an
     * absence to render, the second is a real measurement that makes every app's share
     * zero.
     */
    fun mah(samples: List<SampleEntity>, designCapacityMah: Int?): Double? {
        val ordered = samples.sortedBy { it.timestampMs }
        if (ordered.size < 2) return null

        var counterUah = 0L
        var counterUsable = false
        var levelPct = 0
        var levelUsable = false

        for (index in 0 until ordered.lastIndex) {
            val current = ordered[index]
            val next = ordered[index + 1]

            // Excluded only when BOTH endpoints report the cable in -- the one case this
            // app can actually be sure was charging throughout. A single-endpoint check
            // (attributed to whichever sample is "the start") gets a transition interval
            // wrong in one direction or the other: checking only `current` drops a real
            // plug-out-to-unplugged interval's drain (its start sample still reads
            // plugged, even though the cable came out before `next` was taken), and
            // checking only `next` would symmetrically miscount an unplugged-to-plug-in
            // interval. Leaving a transition edge's two endpoints both eligible and
            // deciding it by the sign of the drop below -- a real fall counts, a rise
            // (the charger catching a still-draining battery) does not -- is what keeps
            // this from silently discarding an interval this app actually measured.
            if (current.pluggedCode != PLUGGED_NONE && next.pluggedCode != PLUGGED_NONE) continue

            val startCounter = current.chargeCounterUah
            val endCounter = next.chargeCounterUah
            if (startCounter != null && endCounter != null) {
                // A rising counter on an unplugged interval is a fuel-gauge reset or a
                // level correction, not negative drain. Dropped, not subtracted.
                val drop = startCounter - endCounter
                if (drop >= 0) {
                    counterUah += drop
                    counterUsable = true
                }
            }

            val levelDrop = current.levelPct - next.levelPct
            if (levelDrop >= 0) {
                levelPct += levelDrop
                levelUsable = true
            }
        }

        // The counter is a direct charge measurement; level x design capacity is a
        // reconstruction from a 1%-quantised integer against a table lookup. Prefer the
        // former whenever it exists, and say so rather than leaving the ordering to look
        // arbitrary.
        //
        // A counter total of exactly zero is the one case this preference is NOT allowed
        // to apply blindly. `counterUsable` only means "two counter readings existed and
        // neither interval's drop was negative" -- it does not mean the counter carries a
        // real signal. A gauge that is stuck (reports the same raw value every sample, as
        // this project has already documented a manufacturer doing for other fields)
        // satisfies exactly that condition and would otherwise make this function return
        // "measured, 0.00 mAh drained" while the level recorded in the very same samples
        // fell. That is a false measurement claim, not an honest zero: zero delta means
        // "no charge moved", not "this counter works". So a zero counter total is trusted
        // only when the level agrees (also flat, or simply unavailable to disagree with);
        // when the level shows a real drop instead, the counter is set aside for this
        // window and the level path below is used instead.
        if (counterUsable && (counterUah > 0L || !levelUsable || levelPct == 0)) {
            return counterUah / UAH_PER_MAH
        }
        if (!levelUsable) return null
        if (designCapacityMah == null || designCapacityMah <= 0) return null
        return levelPct * designCapacityMah / 100.0
    }

    private const val PLUGGED_NONE = 0
    private const val UAH_PER_MAH = 1_000.0
}

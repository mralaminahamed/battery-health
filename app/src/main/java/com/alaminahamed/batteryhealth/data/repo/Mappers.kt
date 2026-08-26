package com.alaminahamed.batteryhealth.data.repo

import com.alaminahamed.batteryhealth.data.local.SampleEntity
import com.alaminahamed.batteryhealth.data.local.SessionEntity
import com.alaminahamed.batteryhealth.domain.CapacityObservation
import kotlin.math.abs
import com.alaminahamed.batteryhealth.domain.ChargeSession
import com.alaminahamed.batteryhealth.domain.LevelPoint
import com.alaminahamed.batteryhealth.domain.SessionType

const val SESSION_TYPE_CHARGE = "CHARGE"
const val SESSION_TYPE_DISCHARGE = "DISCHARGE"

/** Returns null for sessions that are still open or carry an unrecognised type. */
fun SessionEntity.toDomain(): ChargeSession? {
    val endedAt = endedAtMs ?: return null
    val endLevel = endLevelPct ?: return null
    val sessionType = when (type) {
        SESSION_TYPE_CHARGE -> SessionType.Charge
        SESSION_TYPE_DISCHARGE -> SessionType.Discharge
        else -> return null
    }
    return ChargeSession(
        id = id,
        type = sessionType,
        startedAtMs = startedAtMs,
        endedAtMs = endedAt,
        startLevelPct = startLevelPct,
        endLevelPct = endLevel,
        startCounterUah = startCounterUah,
        endCounterUah = endCounterUah,
        peakTempDeciC = peakTempDeciC,
        avgMilliwatts = avgMilliwatts,
        screenOnMs = screenOnMs,
        coulombUah = coulombUah,
    )
}

/**
 * Turns one recorded session into a capacity observation, whichever direction it ran in.
 *
 * A discharge measures the same quantity as a charge: charge leaving the cell across a
 * known change in level says exactly as much about full capacity as charge entering it.
 * The estimator therefore takes both, and this is where the two directions are made
 * comparable -- as magnitudes, so nothing downstream has to know which way a session ran.
 *
 * ## The sign guard flips, it does not go away
 *
 * A charge whose counter *fell* means the fuel gauge reset mid-session, and treating that
 * delta as a measurement would corrupt the estimate. The equivalent fault on a discharge
 * is a counter that *rose* -- the phone was charged partway through, or the gauge reset
 * the other way. Taking an absolute value would have silently accepted both, which is the
 * failure this codebase keeps finding in itself: one rule applied to two cases that needed
 * different ones.
 */
fun ChargeSession.toObservation(): CapacityObservation = CapacityObservation(
    sessionId = id,
    // Magnitude: a discharge's own delta is negative, and every threshold downstream
    // (MIN_DELTA_LEVEL_PCT among them) is expressed as a positive size.
    deltaLevelPct = abs(deltaLevelPct),
    counterDeltaUah = run {
        val start = startCounterUah ?: return@run null
        val end = endCounterUah ?: return@run null
        when (type) {
            // Charge: the counter must have risen.
            SessionType.Charge -> (end - start).takeIf { it > 0 }
            // Discharge: it must have fallen. Reported as the magnitude it moved by.
            SessionType.Discharge -> (start - end).takeIf { it > 0 }
        }
    },
    // Independent of counterDeltaUah above: the estimator decides which column to trust
    // per observation, so this must not be suppressed just because a counter is present,
    // nor must it be filtered only when the counter is absent.
    // Nullable, not defaulted to zero: an unmeasured coulomb value and a measured zero
    // are different facts, and the estimator's own takeIf depends on that distinction.
    //
    // Direction-aware for the same reason the counter above is, and an earlier version of
    // this line was not: it took a blanket magnitude, which quietly accepted a *charge*
    // whose integrated current came out negative. That is current having flowed backwards
    // over a charge -- a fault, not a measurement -- and the existing test for it is what
    // caught the shortcut.
    coulombUah = run {
        val value = coulombUah ?: return@run null
        when (type) {
            SessionType.Charge -> value.takeIf { it > 0 }
            SessionType.Discharge -> value.takeIf { it < 0 }?.let(::abs)
        }
    },
)

fun SampleEntity.toLevelPoint(): LevelPoint = LevelPoint(timestampMs, levelPct)

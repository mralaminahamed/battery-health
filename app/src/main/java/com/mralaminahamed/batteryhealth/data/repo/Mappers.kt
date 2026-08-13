package com.mralaminahamed.batteryhealth.data.repo

import com.mralaminahamed.batteryhealth.data.local.SampleEntity
import com.mralaminahamed.batteryhealth.data.local.SessionEntity
import com.mralaminahamed.batteryhealth.domain.CapacityObservation
import com.mralaminahamed.batteryhealth.domain.ChargeSession
import com.mralaminahamed.batteryhealth.domain.LevelPoint
import com.mralaminahamed.batteryhealth.domain.SessionType

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

fun ChargeSession.toObservation(): CapacityObservation = CapacityObservation(
    sessionId = id,
    deltaLevelPct = deltaLevelPct,
    // A counter that decreased over a charge session means the fuel gauge reset;
    // treating that delta as a measurement would corrupt the estimate.
    counterDeltaUah = run {
        val start = startCounterUah ?: return@run null
        val end = endCounterUah ?: return@run null
        (end - start).takeIf { it > 0 }
    },
    // Independent of counterDeltaUah above: the estimator decides which column to trust
    // per observation, so this must not be suppressed just because a counter is present,
    // nor must it be filtered only when the counter is absent.
    // Nullable, not defaulted to zero: an unmeasured coulomb value and a measured zero
    // are different facts, and the estimator's own takeIf depends on that distinction.
    coulombUah = coulombUah?.takeIf { it > 0 },
)

fun SampleEntity.toLevelPoint(): LevelPoint = LevelPoint(timestampMs, levelPct)

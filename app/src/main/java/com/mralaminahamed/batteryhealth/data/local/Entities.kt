package com.mralaminahamed.batteryhealth.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "samples",
    indices = [Index("timestampMs"), Index("sessionId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val levelPct: Int,
    val chargeCounterUah: Long?,
    val currentUa: Int?,
    val voltageMv: Int?,
    val tempDeciC: Int?,
    val statusCode: Int,
    val pluggedCode: Int,
    val screenOn: Boolean,
    val sessionId: Long?,
    /**
     * The untouched CURRENT_NOW register value, before any unit-scale interpretation.
     * Never presented to the UI or fed into any arithmetic directly -- `currentUa` is the
     * one true-microamp figure this table makes claims through. This column exists only so
     * a completed session can later cross-validate the device's actual scale against the
     * charge counter (see CurrentScaleDetector.fromCounterAgreement): that check needs a
     * genuinely unscaled integral, and `currentUa` can already be a per-reading magnitude
     * guess by the time it is written, which would let a correct guess get mistaken for
     * confirmation of the wrong scale. Defaulted to null, unlike the other nullable columns
     * here, because most call sites (retention, mapping, unrelated fixtures) have no stake
     * in it.
     */
    val currentRawUnits: Int? = null,
    /**
     * Which rule produced [currentUa] on this row: `true` if a scale
     * `CurrentScaleDetector.fromCounterAgreement` actually confirmed against the charge
     * counter was used, `false` if only `CurrentScaleDetector.fromMagnitude`'s
     * per-reading guess was, `null` if [currentUa] itself is null (no scale was applied
     * at all, so provenance does not apply) -- see
     * `BatteryManagerSource.CurrentSample.currentScaleValidated`'s own doc for exactly
     * how each case arises. Exists so `SessionAggregator`'s coulomb integration -- the
     * one `HealthEstimator` falls back to precisely when the charge counter itself
     * cannot be trusted -- can refuse to build a "Measured" health figure out of a row
     * this app never actually earned. Without this column, a guessed `currentUa` and a
     * validated one are bit-for-bit identical once written, and that fallback has no
     * way to tell them apart. Defaulted to null, like `currentRawUnits`, because most
     * call sites (retention, mapping, unrelated fixtures) have no stake in it.
     */
    val currentScaleValidated: Boolean? = null,
)

/** `type` is "CHARGE" or "DISCHARGE"; a null `endedAtMs` marks the open session. */
@Entity(tableName = "sessions", indices = [Index("startedAtMs"), Index("endedAtMs")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val startLevelPct: Int,
    val endLevelPct: Int?,
    val startCounterUah: Long?,
    val endCounterUah: Long?,
    val peakTempDeciC: Int?,
    val avgMilliwatts: Int?,
    val screenOnMs: Long,
    /**
     * Integrated current over the session, in uAh, from SessionAggregator (Task 12).
     * Nullable and never defaulted: an unmeasured value and a measured zero are
     * different facts, and omitting this at a construction site must be a compile
     * error rather than a silent null.
     */
    val coulombUah: Long?,
)

/** `method` is "COUNTER" or "COULOMB". */
@Entity(
    tableName = "capacity_estimates",
    indices = [Index("sessionId")],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class CapacityEstimateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val measuredFullUah: Long,
    val deltaLevelPct: Int,
    val method: String,
    val trustworthy: Boolean,
)

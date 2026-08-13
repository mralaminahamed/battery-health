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
     * Integrated current over the session, in uAh. Added in schema version 2; a later
     * task populates it. Nullable and never defaulted: an unmeasured value and a
     * measured zero are different facts.
     */
    val coulombUah: Long? = null,
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

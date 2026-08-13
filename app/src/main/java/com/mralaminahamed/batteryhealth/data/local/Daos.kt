package com.mralaminahamed.batteryhealth.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {

    @Insert
    suspend fun insert(sample: SampleEntity): Long

    @Query("SELECT * FROM samples WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    suspend fun samplesSince(sinceMs: Long): List<SampleEntity>

    @Query("SELECT * FROM samples WHERE timestampMs >= :sinceMs ORDER BY timestampMs ASC")
    fun observeSamplesSince(sinceMs: Long): Flow<List<SampleEntity>>

    @Query("SELECT * FROM samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latest(): SampleEntity?

    @Query("SELECT * FROM samples WHERE sessionId = :sessionId ORDER BY timestampMs ASC")
    suspend fun samplesForSession(sessionId: Long): List<SampleEntity>

    @Query("UPDATE samples SET sessionId = :sessionId WHERE sessionId IS NULL AND timestampMs >= :fromMs")
    suspend fun attachToSession(sessionId: Long, fromMs: Long): Int

    @Query("DELETE FROM samples WHERE timestampMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun completedSessions(limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeCompletedSessions(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL AND type = :type ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun completedSessionsOfType(type: String, limit: Int): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL AND type = :type ORDER BY startedAtMs DESC LIMIT :limit")
    fun observeCompletedSessionsOfType(type: String, limit: Int): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE endedAtMs IS NOT NULL AND endedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int
}

@Dao
interface EstimateDao {

    @Insert
    suspend fun insert(estimate: CapacityEstimateEntity): Long

    @Query("SELECT * FROM capacity_estimates ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CapacityEstimateEntity>

    @Query("SELECT * FROM capacity_estimates ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CapacityEstimateEntity>>

    @Query("DELETE FROM capacity_estimates WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long): Int
}

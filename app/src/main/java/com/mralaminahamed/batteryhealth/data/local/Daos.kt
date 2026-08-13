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

    /**
     * Fetches the exact row just inserted, by the id `insert()` returned. Deliberately
     * not "the latest sample": a concurrent writer (the baseline worker) can insert a
     * newer row between this call's insert and any later query, so "latest" can silently
     * return someone else's sample instead of the one this caller just wrote.
     */
    @Query("SELECT * FROM samples WHERE id = :id")
    suspend fun byId(id: Long): SampleEntity?

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

    /**
     * The open session identified by `id`, specifically -- not "whichever session
     * happens to be open right now, if its id happens to match". Closing code must be
     * able to ask for the exact session it means, so a caller can never be misled into
     * closing a different session than the one it intended to.
     */
    @Query("SELECT * FROM sessions WHERE id = :id AND endedAtMs IS NULL")
    suspend fun openSessionById(id: Long): SessionEntity?

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

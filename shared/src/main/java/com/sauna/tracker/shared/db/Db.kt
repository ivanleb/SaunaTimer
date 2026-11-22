package com.sauna.tracker.shared.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.sauna.tracker.shared.HrSample
import com.sauna.tracker.shared.Session
import com.sauna.tracker.shared.SessionStats
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    /** While recording this is a heartbeat, updated every few seconds. */
    val durationMs: Long,
    val timeLimitMin: Int,
    val hrLimit: Int,
    val maxBpm: Int = 0,
    val avgBpm: Int = 0,
    val timeAboveLimitMs: Long = 0,
    val inProgress: Boolean = false,
    val interrupted: Boolean = false,
    val synced: Boolean = false,
)

@Entity(
    tableName = "samples",
    primaryKeys = ["sessionId", "t"],
    indices = [Index("sessionId")],
)
data class SampleEntity(val sessionId: String, val t: Long, val bpm: Int)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE inProgress = 0 ORDER BY startedAt DESC")
    fun observeFinished(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY t")
    fun observeSamples(id: String): Flow<List<SampleEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: String): SessionEntity?

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY t")
    suspend fun getSamples(id: String): List<SampleEntity>

    @Query("SELECT * FROM sessions WHERE inProgress = 1")
    suspend fun inProgress(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE inProgress = 0 AND synced = 0")
    suspend fun unsynced(): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE inProgress = 0 AND synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("UPDATE sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: String, durationMs: Long)

    @Query("UPDATE sessions SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSessionRow(id: String)

    @Query("DELETE FROM samples WHERE sessionId = :id")
    suspend fun deleteSamples(id: String)

    @Transaction
    suspend fun delete(id: String) {
        deleteSamples(id)
        deleteSessionRow(id)
    }

    /** Append the newest readings and the elapsed-time heartbeat in one commit. */
    @Transaction
    suspend fun appendProgress(id: String, durationMs: Long, samples: List<SampleEntity>) {
        if (samples.isNotEmpty()) insertSamples(samples)
        updateDuration(id, durationMs)
    }

    @Transaction
    suspend fun replaceSession(session: SessionEntity, samples: List<SampleEntity>) {
        deleteSamples(session.id)
        upsertSession(session)
        insertSamples(samples)
    }
}

@Database(entities = [SessionEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class SaunaDb : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: SaunaDb? = null

        fun get(context: Context): SaunaDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, SaunaDb::class.java, "sauna.db")
                .build().also { instance = it }
        }
    }
}

fun SampleEntity.toSample() = HrSample(t, bpm)

fun SessionEntity.toSession(samples: List<SampleEntity>) = Session(
    id = id,
    startedAt = startedAt,
    durationMs = durationMs,
    timeLimitMin = timeLimitMin,
    hrLimit = hrLimit,
    samples = samples.map { it.toSample() },
    interrupted = interrupted,
)

fun Session.toEntity(synced: Boolean) = SessionEntity(
    id = id,
    startedAt = startedAt,
    durationMs = durationMs,
    timeLimitMin = timeLimitMin,
    hrLimit = hrLimit,
    maxBpm = maxBpm,
    avgBpm = avgBpm,
    timeAboveLimitMs = timeAboveLimitMs,
    inProgress = false,
    interrupted = interrupted,
    synced = synced,
)

fun Session.sampleEntities() = samples.map { SampleEntity(id, it.t, it.bpm) }

suspend fun SessionDao.loadSession(id: String): Session? =
    getSession(id)?.toSession(getSamples(id))

/**
 * Close a session: compute stats from the stored samples.
 * For an interrupted session the duration is whatever was saved last (heartbeat or last sample).
 */
suspend fun SessionDao.finish(id: String, durationMs: Long?, interrupted: Boolean): Session? {
    val row = getSession(id) ?: return null
    val samples = getSamples(id).map { it.toSample() }
    val duration = maxOf(durationMs ?: row.durationMs, samples.lastOrNull()?.t ?: 0L)
    val finished = row.copy(
        durationMs = duration,
        maxBpm = SessionStats.max(samples),
        avgBpm = SessionStats.avg(samples),
        timeAboveLimitMs = SessionStats.timeAtOrAbove(samples, row.hrLimit, duration),
        inProgress = false,
        interrupted = interrupted,
        synced = false,
    )
    upsertSession(finished)
    return finished.toSession(getSamples(id))
}

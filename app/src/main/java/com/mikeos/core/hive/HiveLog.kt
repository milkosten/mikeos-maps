package com.mikeos.core.hive

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mikeos.core.agent.SoulDatabase
import kotlinx.coroutines.flow.Flow

/** Which way a hive message went, from this agent's point of view. */
enum class HiveDirection { SENT, RECV }

/**
 * A persisted record of ONE hive message this agent sent or received. The Agent
 * Inspector reads these newest-first to show the user the agent's conversations with
 * its siblings.
 *
 * @property id        auto row id.
 * @property ts        epoch millis the message was sent/received.
 * @property direction SENT (we sent it) or RECV (a sibling sent it to us).
 * @property peer      the other agent — recipient for SENT, sender for RECV.
 * @property type      message type (e.g. `agent.question`, `place.arrived`).
 * @property body      the message body.
 * @property read      RECV messages start unread; marked read when drained into a beat.
 *   SENT messages are always stored read.
 */
@Entity(tableName = "hive_log")
data class HiveLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "direction") val direction: HiveDirection,
    @ColumnInfo(name = "peer") val peer: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "read") val read: Boolean = true,
)

/** DAO for the persistent hive message log. */
@Dao
interface HiveLogDao {
    @Insert
    suspend fun insert(entry: HiveLogEntry): Long

    /** Newest-first, capped at [limit], as a live Flow for the inspector UI. */
    @Query("SELECT * FROM hive_log ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HiveLogEntry>>

    /** Mark a set of received rows as read once they've been drained into a beat. */
    @Query("UPDATE hive_log SET read = 1 WHERE id IN (:ids)")
    suspend fun markRead(ids: List<Long>)
}

/**
 * Thin store over [HiveLogDao]. The [HiveSocket] records every SENT and RECV message
 * here; the Agent Inspector reads [recent].
 *
 * Best-effort throughout — logging must never break a send or a beat.
 */
class HiveLog(context: Context) {
    private val dao: HiveLogDao = SoulDatabase.get(context).hiveLogDao()

    /** Record a message we just sent to [peer]. Stored read. Returns the new row id. */
    suspend fun recordSent(peer: String, type: String, body: String): Long =
        runCatching {
            dao.insert(
                HiveLogEntry(
                    direction = HiveDirection.SENT,
                    peer = peer,
                    type = type,
                    body = body,
                    read = true,
                )
            )
        }.getOrDefault(-1L)

    /** Record a message we just received from [peer]. Stored unread. Returns the row id. */
    suspend fun recordRecv(peer: String, type: String, body: String): Long =
        runCatching {
            dao.insert(
                HiveLogEntry(
                    direction = HiveDirection.RECV,
                    peer = peer,
                    type = type,
                    body = body,
                    read = false,
                )
            )
        }.getOrDefault(-1L)

    /** Mark received rows read once they've been drained into a beat. */
    suspend fun markRead(ids: List<Long>) {
        if (ids.isEmpty()) return
        runCatching { dao.markRead(ids) }
    }

    /** The hive log newest-first, capped at [limit], as a live Flow for the UI. */
    fun recent(limit: Int = 200): Flow<List<HiveLogEntry>> = dao.recent(limit)
}

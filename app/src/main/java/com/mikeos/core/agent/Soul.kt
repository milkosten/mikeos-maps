package com.mikeos.core.agent

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.mikeos.core.hive.HiveDirection
import com.mikeos.core.hive.HiveLogDao
import com.mikeos.core.hive.HiveLogEntry

/**
 * MIKEAGENT.md §1a — the SOUL: who the agent *is*, persisted and loaded every beat.
 *
 * A per-app record supplied once by the app. It is NOT rebuilt each beat; it gives
 * the agent continuity and intent between beats.
 *
 * @property agentName first-person name of the agent (e.g. "Guide").
 * @property appName   the app it lives inside (e.g. "MikeGuide").
 * @property persona   first-person identity + voice — the system-prompt character.
 * @property goals     standing intentions the agent always cares about.
 */
data class Soul(
    val agentName: String,
    val appName: String,
    val persona: String,
    val goals: List<String>,
)

/**
 * A single long-term memory note. `remember()` writes these; `recall()` reads them.
 *
 * @property ts   epoch millis the note was kept.
 * @property text the note itself.
 * @property tags optional space/comma-separated keywords to aid keyword recall.
 */
@Entity(tableName = "memory")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "tags") val tags: String = "",
)

/**
 * DAO for the long-term memory store. Recall is keyword-based (LIKE over text+tags)
 * ordered by recency — a pragmatic stand-in for semantic recall on-device.
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: Memory): Long

    /** Everything, newest first. */
    @Query("SELECT * FROM memory ORDER BY ts DESC")
    suspend fun all(): List<Memory>

    /**
     * Most relevant memories for [query], newest first. When [query] is blank this
     * degrades to "most recent [limit]". Matching is a case-insensitive LIKE over
     * both the note text and its tags.
     */
    @Query(
        """
        SELECT * FROM memory
        WHERE (:query = '' OR text LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY ts DESC
        LIMIT :limit
        """
    )
    suspend fun recentRelevant(query: String, limit: Int): List<Memory>
}

/** Room type converters shared by [SoulDatabase]. */
class SoulConverters {
    @TypeConverter
    fun directionToString(d: HiveDirection): String = d.name

    @TypeConverter
    fun stringToDirection(s: String): HiveDirection =
        runCatching { HiveDirection.valueOf(s) }.getOrDefault(HiveDirection.RECV)
}

/**
 * Room database backing the soul's long-term memory AND the persistent hive log.
 *
 * v2 adds the `hive_log` table. Schema migration is destructive (fallback) — the log
 * and memory are best-effort caches, not a system of record, so a wipe on upgrade is
 * acceptable and keeps the runtime dependency-light.
 */
@Database(entities = [Memory::class, HiveLogEntry::class], version = 2, exportSchema = false)
@TypeConverters(SoulConverters::class)
abstract class SoulDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun hiveLogDao(): HiveLogDao

    companion object {
        @Volatile
        private var INSTANCE: SoulDatabase? = null

        /** Process-wide singleton; the db file is namespaced per app package. */
        fun get(context: Context): SoulDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SoulDatabase::class.java,
                    "mikeos_soul.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

/**
 * Holds the [Soul] (persona + goals, supplied by the app) and mediates access to
 * the Room-backed long-term memory. The persona/goals live in memory (they are
 * code the app owns); the notes live in Room.
 *
 * Best-effort throughout: memory failures never break a beat.
 */
class SoulStore(
    context: Context,
    val soul: Soul,
) {
    private val dao: MemoryDao = SoulDatabase.get(context).memoryDao()

    /** Keep a note long-term (the `remember` skill routes here). */
    suspend fun remember(text: String, tags: String = ""): Long =
        runCatching { dao.insert(Memory(text = text, tags = tags)) }.getOrDefault(-1L)

    /** Look something up (the `recall` skill routes here). */
    suspend fun recall(query: String, limit: Int = 8): List<Memory> =
        runCatching { dao.recentRelevant(query, limit) }.getOrDefault(emptyList())

    /** Most relevant memories rendered for the SYSTEM-CONTEXT {memory} slot. */
    suspend fun relevantMemoryBlock(query: String, limit: Int = 8): String {
        val notes = recall(query, limit)
        if (notes.isEmpty()) return "(nothing kept yet)"
        return notes.joinToString("\n") { "- ${it.text}" }
    }

    /** The standing goals rendered for the SYSTEM-CONTEXT {goals} slot. */
    fun goalsBlock(): String =
        if (soul.goals.isEmpty()) "(no standing goals)"
        else soul.goals.joinToString("\n") { "- $it" }
}

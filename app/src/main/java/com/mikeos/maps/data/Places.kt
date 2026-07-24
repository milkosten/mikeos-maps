package com.mikeos.maps.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device cache of places Mike has navigated to — so destinations are findable **offline** and
 * tolerant of **misspellings** (fuzzy match), instead of relying only on the online geocoder.
 * Every time he actually routes somewhere, [PlacesRepo.save] records it here (with coords).
 */
@Entity(tableName = "places")
data class SavedPlace(
    @PrimaryKey val label: String,   // full display name (unique key)
    val shortName: String,           // first comma-part — the city/place, for fuzzy matching
    val lat: Double,
    val lon: Double,
    val lastUsedMs: Long,
    val useCount: Int,
)

@Dao
interface PlacesDao {
    @Query("SELECT * FROM places ORDER BY lastUsedMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 300): List<SavedPlace>

    @Query("SELECT * FROM places WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): SavedPlace?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: SavedPlace)
}

@Database(entities = [SavedPlace::class], version = 1, exportSchema = false)
abstract class PlacesDb : RoomDatabase() {
    abstract fun dao(): PlacesDao

    companion object {
        @Volatile private var instance: PlacesDb? = null
        fun get(context: Context): PlacesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, PlacesDb::class.java, "mikemaps-places.db",
            ).build().also { instance = it }
        }
    }
}

/** The offline places cache: save on navigate, fuzzy-search on type. */
object PlacesRepo {

    /** Record (or bump) a place Mike navigated to. */
    suspend fun save(context: Context, label: String, lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        val dao = PlacesDb.get(context).dao()
        val short = label.substringBefore(",").trim().ifBlank { label }
        val existing = dao.byLabel(label)
        dao.upsert(
            SavedPlace(
                label = label,
                shortName = short,
                lat = lat,
                lon = lon,
                lastUsedMs = System.currentTimeMillis(),
                useCount = (existing?.useCount ?: 0) + 1,
            ),
        )
    }

    /**
     * Fuzzy/offline search of cached places. Matches on prefix / substring, and on edit-distance
     * (misspelled by 1–2 letters) against the short name or any label token. Ranked best-first.
     */
    suspend fun search(context: Context, query: String, limit: Int = 6): List<SavedPlace> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase()
        if (q.length < 2) return@withContext emptyList()
        val all = PlacesDb.get(context).dao().recent(300)
        val thr = if (q.length >= 6) 2 else 1
        all.mapNotNull { p ->
            val short = p.shortName.lowercase()
            val label = p.label.lowercase()
            val score = when {
                short.startsWith(q) -> 0
                short.contains(q) || label.contains(q) -> 1
                editDistance(q, short) <= thr -> 2
                label.split(SPLIT).any { it.length >= 3 && editDistance(q, it) <= thr } -> 3
                else -> -1
            }
            if (score < 0) null else p to score
        }
            .sortedWith(compareBy({ it.second }, { -it.first.useCount }, { -it.first.lastUsedMs }))
            .map { it.first }
            .take(limit)
    }

    private val SPLIT = Regex("[ ,]+")

    /** Levenshtein edit distance (iterative, two-row). */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}

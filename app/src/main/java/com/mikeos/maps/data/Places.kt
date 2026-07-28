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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val favorite: Boolean = false,   // Mike ⭐-saved it (a home / work / favorite)
    val kind: String? = null,        // "home" | "work" | "favorite" (null when not a favorite)
)

@Dao
interface PlacesDao {
    @Query("SELECT * FROM places ORDER BY lastUsedMs DESC LIMIT :limit")
    suspend fun recent(limit: Int = 300): List<SavedPlace>

    @Query("SELECT * FROM places WHERE favorite = 1 ORDER BY lastUsedMs DESC")
    suspend fun favorites(): List<SavedPlace>

    @Query("SELECT * FROM places WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): SavedPlace?

    @Query("SELECT * FROM places")
    suspend fun all(): List<SavedPlace>

    @Query("DELETE FROM places WHERE label = :label")
    suspend fun deleteByLabel(label: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: SavedPlace)
}

// v1 → v2: add the favorite flag + kind (non-destructive so the offline cache survives the update).
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE places ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE places ADD COLUMN kind TEXT")
    }
}

@Database(entities = [SavedPlace::class], version = 2, exportSchema = false)
abstract class PlacesDb : RoomDatabase() {
    abstract fun dao(): PlacesDao

    companion object {
        @Volatile private var instance: PlacesDb? = null
        fun get(context: Context): PlacesDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, PlacesDb::class.java, "mikemaps-places.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

/** The offline places cache: save on navigate, fuzzy-search on type. */
object PlacesRepo {

    /**
     * Human place label — drops the region + country tail ("…, Provence-Alpes-Côte d'Azur, France")
     * that's just noise when you're finding a place nearby. A full address keeps its house/street/city;
     * only the trailing two administrative parts are removed.
     */
    fun cleanLabel(label: String): String {
        val parts = label.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return label.trim()
        val kept = if (parts.size >= 4) parts.dropLast(2) else parts   // drop region + country
        return kept.joinToString(", ")
    }

    /** Record (or bump) a place Mike navigated to. Preserves the ⭐ favorite flag if already set. */
    suspend fun save(context: Context, labelRaw: String, lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        val dao = PlacesDb.get(context).dao()
        val label = cleanLabel(labelRaw)
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
                favorite = existing?.favorite ?: false,   // don't clobber a saved favorite on re-navigate
                kind = existing?.kind,
            ),
        )
    }

    /** Mike's ⭐-saved places (homes / work / favorites), newest-first. */
    suspend fun favorites(context: Context): List<SavedPlace> = withContext(Dispatchers.IO) {
        PlacesDb.get(context).dao().favorites()
    }

    /**
     * One-time (idempotent) cleanup: rewrite existing rows whose label still carries the region/country
     * tail into the clean "…, City" form, so old saved/history places match the new results and the
     * route-preview panel stops showing "…, Provence-Alpes-Côte d'Azur, France". Merges on collision
     * (keeps the favorite flag + higher use count). Safe to run on every start.
     */
    suspend fun migrateLabels(context: Context) = withContext(Dispatchers.IO) {
        val dao = PlacesDb.get(context).dao()
        for (p in dao.all()) {
            val clean = cleanLabel(p.label)
            if (clean == p.label || clean.isBlank()) continue
            val existing = dao.byLabel(clean)   // a clean-labelled row may already exist → merge
            dao.deleteByLabel(p.label)
            dao.upsert(
                p.copy(
                    label = clean,
                    shortName = clean.substringBefore(",").trim().ifBlank { clean },
                    favorite = p.favorite || (existing?.favorite ?: false),
                    kind = p.kind ?: existing?.kind,
                    useCount = maxOf(p.useCount, existing?.useCount ?: 0),
                    lastUsedMs = maxOf(p.lastUsedMs, existing?.lastUsedMs ?: 0L),
                ),
            )
        }
    }

    /** Is this exact place currently a favorite? */
    suspend fun isFavorite(context: Context, label: String): Boolean = withContext(Dispatchers.IO) {
        PlacesDb.get(context).dao().byLabel(label)?.favorite == true
    }

    /**
     * Set or clear the ⭐ favorite flag for a place (upserting it into the cache if new). Returns the
     * resulting row. [kind] defaults to "favorite" when saving.
     */
    suspend fun setFavorite(
        context: Context, labelRaw: String, lat: Double, lon: Double,
        favorite: Boolean, kind: String? = null,
    ): SavedPlace = withContext(Dispatchers.IO) {
        val dao = PlacesDb.get(context).dao()
        val label = cleanLabel(labelRaw)
        val short = label.substringBefore(",").trim().ifBlank { label }
        val existing = dao.byLabel(label)
        val row = SavedPlace(
            label = label,
            shortName = short,
            lat = if (existing != null && lat == 0.0) existing.lat else lat,
            lon = if (existing != null && lon == 0.0) existing.lon else lon,
            lastUsedMs = existing?.lastUsedMs ?: System.currentTimeMillis(),
            useCount = existing?.useCount ?: 0,
            favorite = favorite,
            kind = if (favorite) (kind ?: "favorite") else null,
        )
        dao.upsert(row)
        row
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

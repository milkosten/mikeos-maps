package com.mikeos.maps.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Map label text size — an **accessibility** setting for the business-name labels on the map. The
 * default (NORMAL) is Google-Maps-tight; LARGE and HUGE scale the on-map POI labels (and their emoji
 * chips a little) up so they're readable at arm's length / for older eyes. Persisted; default NORMAL.
 *
 * Exposes [scale] — the multiplier the map applies to the ambient-overlay label text size.
 */
object TextSize {
    enum class Level(val scale: Float, val label: String) {
        NORMAL(1.0f, "Normal"),
        LARGE(1.35f, "Large"),
        HUGE(1.7f, "Extra large"),
    }

    private const val PREFS = "textsize"
    private const val KEY_LEVEL = "level"

    private val _level = MutableStateFlow(Level.NORMAL)
    val level: StateFlow<Level> = _level.asStateFlow()

    /** The current text-size multiplier for on-map labels. */
    val scale: Float get() = _level.value.scale

    fun init(context: Context) {
        _level.value = runCatching { Level.valueOf(prefs(context).getString(KEY_LEVEL, Level.NORMAL.name)!!) }
            .getOrDefault(Level.NORMAL)
    }

    fun setLevel(context: Context, l: Level) {
        prefs(context).edit().putString(KEY_LEVEL, l.name).apply()
        _level.value = l
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

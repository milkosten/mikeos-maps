package com.mikeos.maps.ui

import android.content.Context
import com.mikeos.maps.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Map appearance. The basemap serves two styles — dark (`/style.json`) and light
 * (`/style-light.json`) — and this picks which one the map shows:
 *  • **AUTO**  — driven by the phone's ambient-light sensor ([LightSensor]): light in a bright car,
 *    dark at night / in tunnels. Hysteresis keeps it from flickering.
 *  • **LIGHT** — always sun mode.  • **DARK** — always dark.
 *
 * Exposes [styleUrl] (the URL the map should currently load). Persisted; default AUTO.
 */
object MapTheme {
    enum class Mode { AUTO, LIGHT, DARK }

    private const val PREFS = "maptheme"
    private const val KEY_MODE = "mode"

    private val darkUrl = "${BuildConfig.BASEMAP_URL}/style.json"
    private val lightUrl = "${BuildConfig.BASEMAP_URL}/style-light.json"

    // Ambient-light thresholds (lux) with a WIDE hysteresis band so it doesn't flip on small changes:
    // go LIGHT above ~2500 lux (daylight through the windshield), back to DARK below ~600 (dusk/tunnel/
    // indoors). Reference: night ≈ 0–50 lux, daytime driving ≈ thousands–tens of thousands.
    private const val LUX_TO_LIGHT = 2500f
    private const val LUX_TO_DARK = 600f
    private const val MIN_FLIP_MS = 4000L   // never flip more than once per few seconds

    private val _mode = MutableStateFlow(Mode.AUTO)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    @Volatile private var isDark = true      // resolved dark/light (only consulted in AUTO)
    private var lastFlipAt = 0L

    private val _styleUrl = MutableStateFlow(darkUrl)
    val styleUrl: StateFlow<String> = _styleUrl.asStateFlow()

    fun init(context: Context) {
        _mode.value = runCatching { Mode.valueOf(prefs(context).getString(KEY_MODE, Mode.AUTO.name)!!) }
            .getOrDefault(Mode.AUTO)
        recompute()
    }

    fun setMode(context: Context, m: Mode) {
        prefs(context).edit().putString(KEY_MODE, m.name).apply()
        _mode.value = m
        recompute()
    }

    /** Fed by the ambient-light sensor. Only affects AUTO mode. */
    fun onLux(lux: Float) {
        if (_mode.value != Mode.AUTO) return
        val want = when {
            lux >= LUX_TO_LIGHT -> false     // bright → light map
            lux <= LUX_TO_DARK -> true       // dim → dark map
            else -> isDark                   // hysteresis band → hold
        }
        if (want != isDark) {
            val now = System.currentTimeMillis()
            if (now - lastFlipAt < MIN_FLIP_MS) return
            lastFlipAt = now
            isDark = want
            recompute()
        }
    }

    private fun recompute() {
        _styleUrl.value = when (_mode.value) {
            Mode.LIGHT -> lightUrl
            Mode.DARK -> darkUrl
            Mode.AUTO -> if (isDark) darkUrl else lightUrl
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

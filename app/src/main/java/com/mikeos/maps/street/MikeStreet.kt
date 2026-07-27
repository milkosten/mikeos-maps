package com.mikeos.maps.street

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MikeStreet (P1) — the "Give data to Mike Ecosystem" opt-in + live capture state.
 *
 * When ON and the phone is moving (dashboard-mounted, nav in the foreground), [StreetCapture] grabs a
 * frame/second and writes it beside a `.gps.json` locally (no upload yet — that's P3, the self-hosted
 * `mikeos-street` lake on the media box). See docs/mikestreet.md.
 */
object MikeStreet {

    private const val PREFS = "mikestreet"
    private const val KEY_ENABLED = "enabled"

    /** The persisted opt-in. */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Live: a frame is being captured right now (drives the small REC indicator). */
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    /** Frames written this session so far (for the indicator / debugging). */
    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    fun init(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        if (!on) { _capturing.value = false }
    }

    internal fun setCapturing(on: Boolean) { _capturing.value = on }
    internal fun bumpFrames() { _frameCount.value = _frameCount.value + 1 }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

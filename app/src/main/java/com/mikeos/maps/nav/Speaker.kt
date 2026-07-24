package com.mikeos.maps.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * On-device voice prompts for turn-by-turn (free, offline `android.speech.tts.TextToSpeech` — the
 * same engine MikeStoryteller uses; no cloud TTS). Announces each maneuver at most once per phase
 * (far → near → now) so it doesn't nag. Best-effort: silent if TTS isn't ready.
 */
object Speaker {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var lastKey: String? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) runCatching { tts?.language = Locale.getDefault() }
        }
    }

    /** Announce a maneuver once per (step, phase). Called on every guidance update; deduped. */
    fun announce(g: Guidance) {
        val t = tts ?: return
        if (!ready) return
        val phase = when {
            g.kind == ManeuverKind.ARRIVE -> "arrive"
            g.distanceM < 45 -> "now"
            g.distanceM < 250 -> "near"
            g.distanceM < 750 -> "far"
            else -> return  // too far to announce yet
        }
        val key = "${g.stepIndex}:$phase"
        if (key == lastKey) return
        lastKey = key
        val text = if (phase == "now" || phase == "arrive") g.instruction else g.spoken()
        runCatching { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, key) }
    }

    /** Reset the dedup so the next route announces fresh. */
    fun reset() {
        lastKey = null
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}

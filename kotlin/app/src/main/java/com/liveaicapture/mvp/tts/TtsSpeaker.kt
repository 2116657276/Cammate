package com.liveaicapture.mvp.tts

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var ready = false
    private var lastText = ""
    private var lastSpeakAt = 0L

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.SIMPLIFIED_CHINESE
            }
        }
    }

    fun speak(text: String, enabled: Boolean, cooldownMs: Long = 2500L) {
        if (!enabled || !ready) return
        val normalized = text.trim()
        if (normalized.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastSpeakAt
        if (normalized == lastText && elapsed < cooldownMs) {
            return
        }
        // New sentence still keeps a tiny interval to avoid chattering.
        if (normalized != lastText && elapsed < 500L) return

        lastText = normalized
        lastSpeakAt = now
        tts?.speak(normalized, TextToSpeech.QUEUE_FLUSH, null, "live_ai_tip")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}

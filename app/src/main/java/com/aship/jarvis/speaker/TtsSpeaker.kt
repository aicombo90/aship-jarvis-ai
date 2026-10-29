package com.aship.jarvis.speaker

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks Jarvis replies out loud using Android TextToSpeech.
 * Prefers Hindi (hi-IN); falls back to English when Hindi voice data
 * is not available on the phone.
 */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureLanguage()
                ready = true
                // Speak anything that was requested while TTS was warming up.
                pendingText?.let { speak(it) }
                pendingText = null
            }
        }
    }

    private fun configureLanguage() {
        val engine = tts ?: return
        val hindiResult = engine.setLanguage(Locale("hi", "IN"))
        if (hindiResult == TextToSpeech.LANG_MISSING_DATA ||
            hindiResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.US)
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null || !ready) {
            pendingText = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    companion object {
        private const val UTTERANCE_ID = "jarvis_utterance"
    }
}

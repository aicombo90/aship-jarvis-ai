package com.aship.jarvis.speaker

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    /**
     * Optional callback fired when an utterance finishes (done, stopped
     * or errored). Used by the Phase 2 wake service so it only opens the
     * mic after Jarvis has stopped talking. Invoked on a TTS thread —
     * listeners must hand work off to their own thread. MainActivity
     * leaves this null and is unaffected.
     */
    @Volatile
    var onUtteranceFinished: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureLanguage()
                setupUtteranceListener()
                ready = true
                // Speak anything that was requested while TTS was warming up.
                pendingText?.let { speak(it) }
                pendingText = null
            }
        }
    }

    private fun setupUtteranceListener() {
        val engine = tts ?: return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // Nothing to do: only completion matters.
            }

            override fun onDone(utteranceId: String?) {
                notifyFinished(utteranceId)
            }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) {
                notifyFinished(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                notifyFinished(utteranceId)
            }

            private fun notifyFinished(utteranceId: String?) {
                if (utteranceId == UTTERANCE_ID) {
                    onUtteranceFinished?.invoke()
                }
            }
        })
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

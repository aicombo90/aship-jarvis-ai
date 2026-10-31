package com.aship.jarvis.speaker

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Speaks Jarvis replies out loud using Android TextToSpeech.
 *
 * v33: awaaz ab jawab ki script ke hisaab se badalti hai —
 * Bangla (Bengali script) jawab par bn-IN, Devanagari par
 * hi-IN, Latin par en-US. Jo bhasha phone par uplabdh hi
 * na ho, us par girna hi-IN, phir en-US. Pehle (v32 tak)
 * awaaz hamesha hi-IN par atki thi, isliye Bangla jawab
 * bhi Hindi awaaz me padhe jate the.
 */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    /** Is pal engine par lagi bhasha (tag), jaise "bn-IN". */
    private var currentLangTag: String = TAG_HINDI

    /** Bhasha uplabdh hai ya nahi — ek baar ki jaanch ka sangrah. */
    private val langAvailability = HashMap<String, Boolean>()

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
        applyLanguage(engine, TAG_HINDI)
    }

    /** [tag] wali bhasha is phone par hai ya nahi (sangrah ke saath). */
    private fun isLanguageAvailable(engine: TextToSpeech, tag: String): Boolean {
        langAvailability[tag]?.let { return it }
        val available = try {
            val result = engine.isLanguageAvailable(Locale.forLanguageTag(tag))
            result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } catch (t: Throwable) {
            false
        }
        langAvailability[tag] = available
        return available
    }

    /**
     * Engine ki bhasha [tag] par lagao — woh na mile to
     * hi-IN, phir en-US par giro. Asal me lagi bhasha
     * [currentLangTag] me rehti hai.
     */
    private fun applyLanguage(engine: TextToSpeech, tag: String) {
        val chain = listOf(tag, TAG_HINDI, TAG_ENGLISH).distinct()
        for (candidate in chain) {
            if (!isLanguageAvailable(engine, candidate)) continue
            val result = try {
                engine.setLanguage(Locale.forLanguageTag(candidate))
            } catch (t: Throwable) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                currentLangTag = candidate
                return
            }
            langAvailability[candidate] = false
        }
        // Kuch bhi na laga: engine ki apni default chalegi.
    }

    /** Jawab ke aksharon se uski bhasha pehchano (Unicode block ke hisaab se). */
    private fun languageTagFor(text: String): String {
        var bengali = 0
        var devanagari = 0
        for (ch in text) {
            when (ch) {
                in '\u0900'..'\u097F' -> devanagari++
                in '\u0980'..'\u09FF' -> bengali++
            }
        }
        return when {
            bengali > 0 && bengali >= devanagari -> TAG_BENGALI
            devanagari > 0 -> TAG_HINDI
            else -> TAG_ENGLISH
        }
    }

    fun speak(text: String) {
        val engine = tts
        if (engine == null || !ready) {
            pendingText = text
            return
        }
        // v33: har jawab se pehle usi ki bhasha ki awaaz lagao.
        val wanted = languageTagFor(text)
        if (wanted != currentLangTag) {
            applyLanguage(engine, wanted)
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
        private const val TAG_BENGALI = "bn-IN"
        private const val TAG_HINDI = "hi-IN"
        private const val TAG_ENGLISH = "en-US"
    }
}

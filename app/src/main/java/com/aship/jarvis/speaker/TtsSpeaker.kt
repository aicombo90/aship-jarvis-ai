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
 *
 * v93: nayi bhashaen — Tamil/Telugu/Kannada/Malayalam/
 * Gujarati/Gurmukhi/Odia ki lipi bhi pehchani jati hai
 * (script gin kar); Latin-lipi wali bhashaen (Spanish,
 * French...) script se pehchani nahi ja sakti, isliye
 * bulane wala [speak] ko saaf [languageTag] de sakta
 * hai (MayaLanguages.ttsTagFor)। Voice na mile to
 * hi-IN/en-US par giro — aur [onLanguageFallback] se
 * bulane wale ko sachha batao (ek hi bhasha par ek
 * hi baar — har bol par toast nahi)।
 */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null
    private var pendingLanguageTag: String? = null

    /** Is pal engine par lagi bhasha (tag), jaise "bn-IN". */
    private var currentLangTag: String = TAG_HINDI

    /** Bhasha uplabdh hai ya nahi — ek baar ki jaanch ka sangrah. */
    private val langAvailability = HashMap<String, Boolean>()

    /**
     * v93: [applyLanguage] ko jab asal me lagi bhasha
     * mangi hui se alag padi ho (phone par woh voice
     * hi nahi) to bulane wale ko sachha batao:
     * (mangi hui tag, lagi hui tag)। Ek hi bhasha par
     * ek hi baar — har bol par nahi। MainActivity is
     * par toast dikhata hai, wake service diag likhta
     * hai। TTS thread par aa sakta hai — bulane wala
     * apne thread par le jaye।
     */
    var onLanguageFallback: ((requestedTag: String, appliedTag: String) -> Unit)? = null

    /** Aakhiri bhasha jiske fallback ki khabar de di gayi ho (dobara toast nahi)। */
    private var lastFallbackNotifiedFor: String? = null

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
                pendingText?.let { speak(it, pendingLanguageTag) }
                pendingText = null
                pendingLanguageTag = null
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
     * [currentLangTag] me rehti hai. [notifyFallback]
     * chalu ho aur giro to [onLanguageFallback] se
     * khabar do (shuru wali configureLanguage se nahi —
     * woh default lagana hai, girna nahi)।
     */
    private fun applyLanguage(
        engine: TextToSpeech,
        tag: String,
        notifyFallback: Boolean = false
    ) {
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
                if (candidate == tag) {
                    lastFallbackNotifiedFor = null
                } else if (notifyFallback && lastFallbackNotifiedFor != tag) {
                    lastFallbackNotifiedFor = tag
                    onLanguageFallback?.invoke(tag, candidate)
                }
                return
            }
            langAvailability[candidate] = false
        }
        // Kuch bhi na laga: engine ki apni default chalegi.
    }

    /** Is pal engine par asal me lagi bhasha (tag) — fallback jaanchne ke liye. */
    val appliedLanguageTag: String
        get() = currentLangTag

    /**
     * v93: [requestedTag] bolne par asal me kaun si
     * bhasha lagegi — bina engine chhede (picker wali
     * imaandaar chetaavni ke liye)। Engine abhi taiyaar
     * hi na ho to mangi hui hi maano।
     */
    fun resolveSpokenTag(requestedTag: String): String {
        val engine = tts ?: return requestedTag
        if (!ready) return requestedTag
        val chain = listOf(requestedTag, TAG_HINDI, TAG_ENGLISH).distinct()
        for (candidate in chain) {
            if (isLanguageAvailable(engine, candidate)) return candidate
        }
        return requestedTag
    }

    /** Jawab ke aksharon se uski bhasha pehchano (Unicode block ke hisaab se). */
    private fun languageTagFor(text: String): String {
        var bengali = 0
        var devanagari = 0
        var tamil = 0
        var telugu = 0
        var kannada = 0
        var malayalam = 0
        var gujarati = 0
        var gurmukhi = 0
        var odia = 0
        for (ch in text) {
            when (ch) {
                in '\u0900'..'\u097F' -> devanagari++
                in '\u0980'..'\u09FF' -> bengali++
                // v93: baaki badi Bharatiya lipiyan — Urdu/
                // Arabic lipi jaan-boojh kar NAHI (Urdu aur
                // Arabic ek hi lipi likhte hain — wahan
                // saaf tag hi faisla karega, andaza nahi)।
                in '\u0A00'..'\u0A7F' -> gurmukhi++
                in '\u0A80'..'\u0AFF' -> gujarati++
                in '\u0B00'..'\u0B7F' -> odia++
                in '\u0B80'..'\u0BFF' -> tamil++
                in '\u0C00'..'\u0C7F' -> telugu++
                in '\u0C80'..'\u0CFF' -> kannada++
                in '\u0D00'..'\u0D7F' -> malayalam++
            }
        }
        // Sabse zyada akshar wali lipi jeettegi; barabari
        // par Bangla, phir Devanagari (v33 wala purana
        // kram) — baaki lipiyan uske baad।
        val counts = listOf(
            TAG_BENGALI to bengali,
            TAG_HINDI to devanagari,
            TAG_TAMIL to tamil,
            TAG_TELUGU to telugu,
            TAG_KANNADA to kannada,
            TAG_MALAYALAM to malayalam,
            TAG_GUJARATI to gujarati,
            TAG_PUNJABI to gurmukhi,
            TAG_ODIA to odia
        )
        val best = counts.maxByOrNull { it.second }
        return if (best != null && best.second > 0) best.first else TAG_ENGLISH
    }

    fun speak(text: String) {
        speak(text, null)
    }

    /**
     * v93: [languageTag] mile (nayi bhasha ka saaf tag —
     * MayaLanguages.ttsTagFor) to wahi awaaz pehle
     * aazmao; na mile to purana script wala raasta।
     */
    fun speak(text: String, languageTag: String?) {
        val engine = tts
        if (engine == null || !ready) {
            pendingText = text
            pendingLanguageTag = languageTag
            return
        }
        // v33: har jawab se pehle usi ki bhasha ki awaaz lagao.
        val wanted = languageTag?.trim().takeIf { !it.isNullOrEmpty() }
            ?: languageTagFor(text)
        if (wanted != currentLangTag) {
            applyLanguage(engine, wanted, notifyFallback = true)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * v36: is pal engine sach me kuch bol raha hai kya —
     * background service ka khud-ki-awaaz wala jaal
     * (self-hearing guard) isi se chalta hai: Maya apni hi
     * boli ("চালু করে দিয়েছি") ko hukm samajh kar phir se
     * kaam na kar de.
     */
    val isSpeakingNow: Boolean
        get() = try {
            tts?.isSpeaking == true
        } catch (t: Throwable) {
            false
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
        private const val TAG_TAMIL = "ta-IN"
        private const val TAG_TELUGU = "te-IN"
        private const val TAG_KANNADA = "kn-IN"
        private const val TAG_MALAYALAM = "ml-IN"
        private const val TAG_GUJARATI = "gu-IN"
        private const val TAG_PUNJABI = "pa-IN"
        private const val TAG_ODIA = "or-IN"
    }
}

package com.aship.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Speech input path A: Android's built-in SpeechRecognizer.
 * Works with zero downloads. Asks for Hindi (hi-IN); with
 * [preferOffline] set, devices that carry an offline Hindi pack use it,
 * otherwise the system may use its online recognizer.
 *
 * Must be created and used on the main thread (SpeechRecognizer rule).
 */
class SystemSpeechEngine(
    context: Context,
    private val preferOffline: Boolean
) : SpeechEngine {

    override var listener: SpeechEngineListener? = null
    override val displayName: String = "System speech (Hindi)"

    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    // v35: MainActivity ke bhasha cascade ke liye — set ho to
    // AppPrefs wali pasand ki jagah ye bhasha chalegi (cascade
    // haarne wali bhasha badal-badal kar phir aazmata hai).
    var languageOverride: String? = null

    /** Is koshish me asal me lagi bhasha — jeetne wali bhasha ki report ke liye. */
    var currentLanguage: String = ""
        private set

    /** Aakhiri recognizer error ka code (SpeechRecognizer.ERROR_*); koi error na ho to -1. */
    var lastErrorCode: Int = -1
        private set

    /**
     * Aakhiri error sunna shuru hote hi, kuch sune bina aaya tha
     * ya nahi — OriginOS par recognizer service kharab/missing
     * hone ki nishaani; MainActivity is par Vosk par girta hai.
     */
    var lastErrorWasImmediate: Boolean = false
        private set

    private var attemptStartedAtMs: Long = 0L
    private var heardAnythingThisAttempt: Boolean = false

    override fun startListening() {
        if (listening) return
        lastErrorCode = -1
        lastErrorWasImmediate = false
        heardAnythingThisAttempt = false
        attemptStartedAtMs = System.currentTimeMillis()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            // v35: uplabdh hi nahi — code ke saath batao taaki
            // MainActivity is session ke liye Vosk par gir sake.
            lastErrorCode = SpeechRecognizer.ERROR_CLIENT
            lastErrorWasImmediate = true
            listener?.onError("Is phone par speech recognition available nahi hai.")
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // v33: bhasha user ki pasand se (AppPrefs) — default
            // bn-IN, kyunki malik Bangla bolte hain; pehle ye
            // hi-IN par atka tha, isliye Bangla baat Hindi
            // aksharon me badal jati thi aur Maya galat samajhti thi.
            // v35: bhasha cascade ka override ho to woh,
            // warna user ki pasand (AppPrefs).
            val language = languageOverride
                ?: com.aship.jarvis.prefs.AppPrefs(appContext).listenLanguage
            currentLanguage = language
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            // Give the speaker time: without these, many devices end the
            // session almost immediately, before the user can talk.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3_000L)
        }
        try {
            recognizer?.startListening(intent)
            listening = true
            listener?.onListeningStateChanged(true)
        } catch (e: Exception) {
            // v35: start hi fail — service wala fail maano,
            // MainActivity Vosk par girega.
            lastErrorCode = SpeechRecognizer.ERROR_CLIENT
            lastErrorWasImmediate = true
            listener?.onError("Mic start nahi ho paya: ${e.message}")
        }
    }

    override fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            // Already stopped / never started: nothing to do.
        }
        if (listening) {
            listening = false
            listener?.onListeningStateChanged(false)
        }
    }

    /**
     * v51 (item 6c): band + shuru EK hi main-thread saans
     * me. Purana raasta stop ko alag post se bhejta tha
     * aur start dusre post se — dono ke beech recognizer
     * aadha-band haalat me naya start paakar
     * ERROR_RECOGNIZER_BUSY deta tha, jisse bip chakra
     * (beep cycles) bante the. Yahan purana recognizer
     * destroy karke taaza banta hai: destroy par koi
     * error callback nahi aata, isliye jhooti error
     * wali ghanti bhi nahi bajti. Sirf main thread se
     * bulao (engine waisi hi shart rakhta hai)।
     */
    fun restartListening() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Destroy fail ho to bhi nayi koshish rukti nahi.
        }
        recognizer = null
        listening = false
        startListening()
    }

    override fun destroy() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ignore: tearing down anyway.
        }
        recognizer = null
        listening = false
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            listening = false
            listener?.onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            listening = false
            // v35: code + turant-fail ki haalat yaad rakho —
            // MainActivity inhi se cascade / Vosk-fallback
            // ka faisla leta hai.
            lastErrorCode = error
            lastErrorWasImmediate = !heardAnythingThisAttempt &&
                (System.currentTimeMillis() - attemptStartedAtMs) <
                IMMEDIATE_ERROR_WINDOW_MS
            listener?.onListeningStateChanged(false)
            listener?.onError("Sunne me dikkat aayi (error $error). Phir se try kijiye.")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                heardAnythingThisAttempt = true
                listener?.onFinalResult(text)
            } else {
                // v35: khaali result = kuch samajh nahi aaya.
                // Pehle ye chup-chaap khatm ho jata tha (dead
                // end); ab NO_MATCH error bhejta hai taaki
                // MainActivity bhasha cascade chala sake.
                listening = false
                lastErrorCode = SpeechRecognizer.ERROR_NO_MATCH
                lastErrorWasImmediate = false
                listener?.onListeningStateChanged(false)
                listener?.onError("Kuch sunai nahi diya — phir se boliye.")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                heardAnythingThisAttempt = true
                listener?.onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        /** v35: isse jaldi aaya error "service turant fail" mana jata hai. */
        private const val IMMEDIATE_ERROR_WINDOW_MS = 2_500L
    }
}

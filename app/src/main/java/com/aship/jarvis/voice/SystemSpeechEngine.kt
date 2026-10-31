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

    override fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
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
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                com.aship.jarvis.prefs.AppPrefs(appContext).listenLanguage
            )
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
            listener?.onListeningStateChanged(false)
            listener?.onError("Sunne me dikkat aayi (error $error). Phir se try kijiye.")
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                listener?.onFinalResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                listener?.onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

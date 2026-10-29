package com.aship.jarvis.voice

/** Callbacks every speech engine reports through, whatever its technology. */
interface SpeechEngineListener {
    fun onListeningStateChanged(listening: Boolean)
    fun onPartialResult(text: String)
    fun onFinalResult(text: String)
    fun onError(message: String)
}

/**
 * A way for Jarvis to listen. Phase 1 has two implementations:
 * the Android system recognizer and the fully-offline Vosk engine.
 * MainActivity only ever talks to this interface.
 */
interface SpeechEngine {
    var listener: SpeechEngineListener?

    /** Human-readable name shown in the status line. */
    val displayName: String

    fun startListening()
    fun stopListening()
    fun destroy()
}

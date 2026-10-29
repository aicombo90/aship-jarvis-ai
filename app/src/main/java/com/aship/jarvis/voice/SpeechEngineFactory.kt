package com.aship.jarvis.voice

import android.content.Context
import android.speech.SpeechRecognizer
import com.aship.jarvis.prefs.JarvisMode

/**
 * Chooses which speech engine Jarvis listens with. This factory is
 * only for the foreground (tap-to-speak) engine — the background
 * wake service builds its own Vosk recognizer and never comes here.
 *
 * - ONLINE / AUTO: the Android system recognizer first. Live testing
 *   on the owner's phone showed it understands his Bengali-accented
 *   Hindi far better than the small Vosk model does. Vosk is used
 *   only when the system recognizer is unavailable on the device —
 *   and, via MainActivity's start-error fallback, when it fails to
 *   start.
 * - OFFLINE: Vosk when the Hindi model is downloaded, so listening
 *   works with no network at all. If creating the Vosk engine throws
 *   anything (missing class, native load failure, ...), we quietly
 *   fall back to the system recognizer.
 */
object SpeechEngineFactory {

    fun create(context: Context, mode: JarvisMode, modelManager: ModelManager): SpeechEngine {
        return when (mode) {
            JarvisMode.ONLINE, JarvisMode.AUTO -> {
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    SystemSpeechEngine(context, preferOffline = false)
                } else {
                    createVoskOrSystem(context, modelManager)
                }
            }
            JarvisMode.OFFLINE -> createVoskOrSystem(context, modelManager)
        }
    }

    /** Vosk (Hindi) when its model is ready, else the system recognizer. */
    fun createVoskOrSystem(context: Context, modelManager: ModelManager): SpeechEngine {
        if (modelManager.isModelReady(ModelLanguage.HINDI)) {
            try {
                return VoskSpeechEngine(context, modelManager, ModelLanguage.HINDI)
            } catch (t: Throwable) {
                // Vosk ban hi nahi paya — system engine par gir jao.
            }
        }
        // NOTE: preferOffline must stay false here. With no
        // offline speech pack on the phone, forcing it makes
        // the system recognizer stop instantly.
        return SystemSpeechEngine(context, preferOffline = false)
    }
}

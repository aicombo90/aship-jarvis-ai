package com.aship.jarvis.voice

import android.content.Context
import com.aship.jarvis.prefs.JarvisMode

/**
 * Chooses which speech engine Jarvis listens with.
 *
 * - ONLINE: system recognizer without the offline preference (online AI
 *   conversation itself arrives in Phase 3; commands stay the same).
 * - OFFLINE / AUTO: Vosk when the Hindi model is downloaded, so listening
 *   works with no network at all. If creating the Vosk engine throws
 *   anything (missing class, native load failure, ...), we quietly fall
 *   back to the system recognizer with the offline preference set.
 */
object SpeechEngineFactory {

    fun create(context: Context, mode: JarvisMode, modelManager: ModelManager): SpeechEngine {
        return when (mode) {
            JarvisMode.ONLINE -> SystemSpeechEngine(context, preferOffline = false)
            JarvisMode.OFFLINE, JarvisMode.AUTO -> {
                if (modelManager.isModelReady(ModelLanguage.HINDI)) {
                    try {
                        VoskSpeechEngine(context, modelManager, ModelLanguage.HINDI)
                    } catch (t: Throwable) {
                        SystemSpeechEngine(context, preferOffline = false)
                    }
                } else {
                    // NOTE: preferOffline must stay false here. With no
                    // offline speech pack on the phone, forcing it makes
                    // the system recognizer stop instantly.
                    SystemSpeechEngine(context, preferOffline = false)
                }
            }
        }
    }
}

package com.aship.jarvis.prefs

import android.content.Context

/** Operating mode selected by the user on the main screen. */
enum class JarvisMode {
    OFFLINE,
    ONLINE,
    AUTO
}

/** Tiny SharedPreferences wrapper for Jarvis settings. */
class AppPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: JarvisMode
        get() {
            val saved = prefs.getString(KEY_MODE, JarvisMode.AUTO.name) ?: JarvisMode.AUTO.name
            return try {
                JarvisMode.valueOf(saved)
            } catch (e: IllegalArgumentException) {
                JarvisMode.AUTO
            }
        }
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    /**
     * v33: foreground (system) recognizer kis bhasha me sune —
     * "bn-IN" (default, malik Bangla bolte hain), "hi-IN" ya
     * "en-IN". App screen par button se badla jata hai.
     * Background wake sunna Vosk (Hindi model) par hi rehta
     * hai — Bangla ka offline model hai hi nahi.
     */
    var listenLanguage: String
        get() = prefs.getString(KEY_LISTEN_LANGUAGE, "bn-IN") ?: "bn-IN"
        set(value) {
            prefs.edit().putString(KEY_LISTEN_LANGUAGE, value).apply()
        }

    /** Phase 2: how many times the wake word has woken Jarvis up. */
    var wakeHitCount: Int
        get() = prefs.getInt(KEY_WAKE_HITS, 0)
        set(value) {
            prefs.edit().putInt(KEY_WAKE_HITS, value).apply()
        }

    /**
     * v1.3 mic sharing: true while JarvisWakeService is alive. The
     * service sets it on start and clears it in onDestroy/teardown;
     * MainActivity reads it so it only sends pause/resume intents
     * when the service actually exists (starting the service just to
     * pause it would be wrong).
     */
    var wakeServiceRunning: Boolean
        get() = prefs.getBoolean(KEY_WAKE_SERVICE_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WAKE_SERVICE_RUNNING, value).apply()
        }

    /** v1.7 diagnostics: latest non-blank final text heard by the wake recognizer. */
    var lastHeardText: String
        get() = prefs.getString(KEY_LAST_HEARD_TEXT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_HEARD_TEXT, value).apply()
        }

    /** v1.7 diagnostics: longest partial heard since the latest final result. */
    var lastHeardPartialText: String
        get() = prefs.getString(KEY_LAST_HEARD_PARTIAL_TEXT, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_HEARD_PARTIAL_TEXT, value).apply()
        }

    /** v1.7 diagnostics: when the wake recognizer last stored a final result. */
    var lastHeardAtMs: Long
        get() = prefs.getLong(KEY_LAST_HEARD_AT_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_HEARD_AT_MS, value).apply()
        }

    /** v1.7 diagnostics: when the wake recognizer last stored a partial result. */
    var lastHeardPartialAtMs: Long
        get() = prefs.getLong(KEY_LAST_HEARD_PARTIAL_AT_MS, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_HEARD_PARTIAL_AT_MS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_MODE = "mode"
        private const val KEY_LISTEN_LANGUAGE = "listen_language"
        private const val KEY_WAKE_HITS = "wake_hits"
        private const val KEY_WAKE_SERVICE_RUNNING = "wake_service_running"
        private const val KEY_LAST_HEARD_TEXT = "last_heard_text"
        private const val KEY_LAST_HEARD_PARTIAL_TEXT = "last_heard_partial_text"
        private const val KEY_LAST_HEARD_AT_MS = "last_heard_at_ms"
        private const val KEY_LAST_HEARD_PARTIAL_AT_MS = "last_heard_partial_at_ms"
    }
}

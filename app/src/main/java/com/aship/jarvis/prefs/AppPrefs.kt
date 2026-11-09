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

    /**
     * v75: full shutdown ke liye turant (sync) likhai —
     * apply() wala aam setter killProcess se pehle disk
     * tak nahi pahunchta, isliye yahan commit() hi sahi
     * hai (chhoti likhai, ek baar wali baat)।
     */
    fun markWakeServiceStoppedSync() {
        prefs.edit().putBoolean(KEY_WAKE_SERVICE_RUNNING, false).commit()
    }

    /**
     * v80: "শাটডাউন" ke baad ka jhanda — BOOT par aur app
     * khulne par wake service khud chalu hone se pehle ye
     * dekha jata hai। App khulte hi saaf hota hai (dobara
     * kholna hi dobara chalu karna hai); v75 wala
     * full-shutdown is jhande ko sync likhta hai, taaki
     * kill se pehle disk tak pahunch jaye। Sirf is jhande
     * se faisla hota hai — wakeServiceRunning se nahi
     * (woh service kabhi chali hi na ho to bhi false
     * hota hai)।
     */
    var wakeStoppedByShutdown: Boolean
        get() = prefs.getBoolean(KEY_WAKE_STOPPED_BY_SHUTDOWN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WAKE_STOPPED_BY_SHUTDOWN, value).apply()
        }

    fun markWakeStoppedByShutdownSync() {
        prefs.edit().putBoolean(KEY_WAKE_STOPPED_BY_SHUTDOWN, true).commit()
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

    /**
     * v51 (item 5): saamne wali screen par aakhiri baar
     * bandhi shaksiyat ka naam ("MAYA" / "TARA"), ya
     * abhi tak koi na bandhi ho to null. Saamne wali
     * bhasha-kataar (CascadeOrder) isi se shuru hoti
     * hai — Maya se baat chal rahi ho to pehla kaan
     * hi-IN ka khulega, Bangla ka nahi.
     */
    var lastForegroundPersonaName: String?
        get() = prefs.getString(KEY_LAST_FG_PERSONA, null)
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_LAST_FG_PERSONA).apply()
            } else {
                prefs.edit().putString(KEY_LAST_FG_PERSONA, value).apply()
            }
        }

    /**
     * v51 (item 8): "offline kaan pehle" wala vaikalpik
     * badlav (toggle). Default BAND (false) — purana
     * bartao hi aam hai. Chalu ho to command session
     * system recognizer se pehle Vosk/Zipformer jaise
     * offline kaan aazmata hai: bip kam, sunna thoda
     * kamzor.
     */
    var offlineEarsFirst: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_EARS_FIRST, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OFFLINE_EARS_FIRST, value).apply()
        }

    /**
     * v51 (item 4): Hindi offline pack wali salaah-line
     * malik ne khud band ki hai kya. Ek baar band to
     * phir nahi dikhti (pack safal hone par woh
     * jarvis_diag ke "offline_system_ok" se khud
     * chhup jati hai).
     */
    var offlinePackHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_PACK_HINT_DISMISSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OFFLINE_PACK_HINT_DISMISSED, value).apply()
        }

    /**
     * v63: READ_PHONE_STATE ek baar maanga gaya ya nahi —
     * ek baar mana karne par baar-baar dialog dikhakar
     * tang nahi karna; jaanch tab vision/isChecked se।
     */
    var phonePermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_PHONE_PERMISSION_ASKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PHONE_PERMISSION_ASKED, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_MODE = "mode"
        private const val KEY_LISTEN_LANGUAGE = "listen_language"
        private const val KEY_WAKE_HITS = "wake_hits"
        private const val KEY_WAKE_SERVICE_RUNNING = "wake_service_running"
        private const val KEY_WAKE_STOPPED_BY_SHUTDOWN = "wake_stopped_by_shutdown"
        private const val KEY_LAST_HEARD_TEXT = "last_heard_text"
        private const val KEY_LAST_HEARD_PARTIAL_TEXT = "last_heard_partial_text"
        private const val KEY_LAST_HEARD_AT_MS = "last_heard_at_ms"
        private const val KEY_LAST_HEARD_PARTIAL_AT_MS = "last_heard_partial_at_ms"
        private const val KEY_LAST_FG_PERSONA = "last_fg_persona"
        private const val KEY_OFFLINE_EARS_FIRST = "offline_ears_first"
        private const val KEY_OFFLINE_PACK_HINT_DISMISSED = "offline_pack_hint_dismissed"
        private const val KEY_PHONE_PERMISSION_ASKED = "phone_permission_asked"
    }
}

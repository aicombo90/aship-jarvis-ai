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

    companion object {
        private const val PREFS_NAME = "jarvis_prefs"
        private const val KEY_MODE = "mode"
    }
}

package com.aship.jarvis.commands

import android.content.Context

/**
 * v102: FULL VOICE HANDS-FREE OS MODE — mode ka jhanda + 30 s
 * wali sunne ki khidki (prefs "jarvis_handsfree")।
 *
 * Mode ON ho to wake service har command ke baad bina wake
 * word ke chup-chaap agli khidki kholta hai (promptSessionMore
 * wala "আর কিছু?" nahi bolta); 30 s khamoshi ke baad khidki
 * band — phir wake word chahiye। Khidki har suni hui baat
 * (wake / command) se taaza hoti hai — [noteHeard] wahi
 * ghadi hai। Mode khud kabhi koi kaam nahi karta, sirf
 * sunne ka tareeka badalta hai; har kaam ke liye phir bhi
 * user ki boli hui haan/hukm chahiye।
 */
object HandsFreeMode {
    private const val PREFS = "jarvis_handsfree"
    private const val KEY_ON = "on"
    private const val KEY_LAST_HEARD_MS = "last_heard_ms"

    /** Wake word ke baad / har command ke baad bina wake word ke sunne ki khidki। */
    const val WINDOW_MS = 30_000L

    fun isOn(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ON, false)
        } catch (t: Throwable) {
            false
        }
    }

    fun setOn(context: Context, on: Boolean) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ON, on).apply()
            if (on) noteHeard(context)
        } catch (t: Throwable) {
            // Jhanda na likh paye to mode wahi rehta hai jo tha.
        }
    }

    /** Koi baat suni gayi (wake / command) — khidki yahin se 30 s। */
    fun noteHeard(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_HEARD_MS, System.currentTimeMillis()).apply()
        } catch (t: Throwable) {
            // Ghadi na likh paye to khidki band hi mani jayegi.
        }
    }

    fun lastHeardMs(context: Context): Long {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_HEARD_MS, 0L)
        } catch (t: Throwable) {
            0L
        }
    }

    /** Aakhiri suni baat se 30 s se zyada khamoshi — khidki band। Kabhi suna hi na ho to khuli mano। */
    fun windowExpired(context: Context): Boolean {
        val last = lastHeardMs(context)
        if (last <= 0L) return false
        return System.currentTimeMillis() - last > WINDOW_MS
    }
}

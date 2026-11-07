package com.aship.jarvis.commands

import android.content.Context
import org.json.JSONArray

/**
 * v53: COMMAND HISTORY — aakhiri 20 hukm phone par hi
 * (SharedPreferences "jarvis_history", poora offline —
 * koi network nahi, koi nayi permission nahi)।
 *
 * Main screen par aakhiri 5 tappable list me dikhte
 * hain — tap karte hi wohi hukm phir chalega। Ek hi
 * hukm dobara aaye to upar aa jata hai, duplicate nahi
 * banta। Koi gadbad ho to history chup-chaap khaali —
 * asal kaam kabhi nahi rukta।
 */
object CommandHistory {

    /** Kul kitne hukm yaad rakhe jayein। */
    const val MAX_SIZE = 20

    /** Screen par ek saath kitne dikhein। */
    const val SHOWN = 5

    /** Naya hukm sabse upar jodo (dobara aaye to purana hata kar)। */
    fun record(context: Context, text: String) {
        try {
            val clean = text.trim()
            if (clean.isEmpty()) return
            val list = list(context).toMutableList()
            list.remove(clean)
            list.add(0, clean)
            save(context, list.take(MAX_SIZE))
        } catch (t: Throwable) {
            // History kabhi asal kaam na roke.
        }
    }

    /** Naye se purane ki kataar (khaali ho to khaali list)। */
    fun list(context: Context): List<String> {
        return try {
            val raw = prefs(context).getString(KEY_HISTORY, null)
                ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Poori history saaf karo। */
    fun clear(context: Context) {
        try {
            prefs(context).edit().remove(KEY_HISTORY).apply()
        } catch (t: Throwable) {
            // Saaf na ho to bhi kaam chalta rahe.
        }
    }

    private fun save(context: Context, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "jarvis_history"
    private const val KEY_HISTORY = "history_json"
}

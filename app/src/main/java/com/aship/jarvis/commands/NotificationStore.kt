package com.aship.jarvis.commands

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v98: notification se aaye messageon ki chhoti godam —
 * SharedPreferences me (koi network nahi)। Sirf taaze
 * 10 message rehte hain (purane khud mit jate hain)।
 * [JarvisNotificationListener] likhta hai, [NotificationCommands]
 * aur CommandEngine padhte hain।
 *
 * Godam me sirf hisaab rehta hai (bhejne wala + text +
 * samay) — jawab bhejne wala asal action zinda
 * notification me hota hai (live map, listener dekho);
 * notification hata di jaye to godam se bhi mit jati hai।
 */
object NotificationStore {

    /** Ek pakda hua message — jawab diya to [replied] true। */
    data class StoredMessage(
        val key: String,
        val pkg: String,
        val sender: String,
        val text: String,
        val time: Long,
        val hasReply: Boolean,
        val replied: Boolean = false
    )

    private const val PREFS_NAME = "jarvis_notifications"
    private const val KEY_MESSAGES = "messages_json"
    private const val KEY_LAST_READ = "last_read_key"
    private const val MAX_MESSAGES = 10

    /** Naya message jodo (wahi key ho to badlo) — taaze 10 hi rakho। */
    fun add(context: Context, msg: StoredMessage) {
        val all = loadAll(context).toMutableList()
        all.removeAll { it.key == msg.key }
        all.add(msg)
        all.sortByDescending { it.time }
        saveAll(context, all.take(MAX_MESSAGES))
    }

    /** Bina jawab wale — taaze pehle। */
    fun listUnread(context: Context): List<StoredMessage> =
        loadAll(context).filter { !it.replied }.sortedByDescending { it.time }

    fun findByKey(context: Context, key: String): StoredMessage? =
        loadAll(context).firstOrNull { it.key == key }

    /** Naam se dhoondho — bhejne wale me naam ho ya naam me bhejne wala (chhota bada ek)। */
    fun findBySender(context: Context, name: String): StoredMessage? {
        val needle = name.lowercase(java.util.Locale.ROOT).trim()
        if (needle.length < 2) return null
        return loadAll(context)
            .filter { !it.replied }
            .sortedByDescending { it.time }
            .firstOrNull { msg ->
                val sender = msg.sender.lowercase(java.util.Locale.ROOT)
                sender.contains(needle) || needle.contains(sender)
            }
    }

    /** Jawab de diya — dobara "naya" me nahi dikhega। */
    fun markReplied(context: Context, key: String) {
        val all = loadAll(context).map {
            if (it.key == key) it.copy(replied = true) else it
        }
        saveAll(context, all)
    }

    fun remove(context: Context, key: String) {
        val all = loadAll(context).filter { it.key != key }
        saveAll(context, all)
    }

    /** Maya ne abhi-abhi kise padhkar sunaya — "উত্তর দাও" usi ko jayega। */
    fun lastReadKey(context: Context): String? =
        prefs(context).getString(KEY_LAST_READ, null)

    fun setLastReadKey(context: Context, key: String) {
        try {
            prefs(context).edit().putString(KEY_LAST_READ, key).apply()
        } catch (t: Throwable) {
            // Yaad na rahe to agli baar taaza wala hi nishana hoga।
        }
    }

    fun loadAll(context: Context): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        try {
            val raw = prefs(context).getString(KEY_MESSAGES, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key", "")
                val sender = obj.optString("sender", "")
                val text = obj.optString("text", "")
                val time = obj.optLong("time", 0L)
                if (key.isEmpty() || text.isEmpty() || time <= 0L) continue
                out.add(
                    StoredMessage(
                        key = key,
                        pkg = obj.optString("pkg", ""),
                        sender = sender.ifEmpty { obj.optString("pkg", "") },
                        text = text,
                        time = time,
                        hasReply = obj.optBoolean("hasReply", false),
                        replied = obj.optBoolean("replied", false)
                    )
                )
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    private fun saveAll(context: Context, messages: List<StoredMessage>) {
        try {
            val array = JSONArray()
            for (m in messages) {
                val obj = JSONObject()
                obj.put("key", m.key)
                obj.put("pkg", m.pkg)
                obj.put("sender", m.sender)
                obj.put("text", m.text)
                obj.put("time", m.time)
                obj.put("hasReply", m.hasReply)
                obj.put("replied", m.replied)
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_MESSAGES, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agli baar phir koshish hogi।
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

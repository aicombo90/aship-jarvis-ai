package com.aship.jarvis.commands

import android.content.Context
import android.provider.ContactsContract
import com.aship.jarvis.actions.PhoneActions
import org.json.JSONArray
import org.json.JSONObject

/**
 * v56: START BUTTON — ফোনটাকে চিনে নেওয়া।
 *
 * START চাপলে জার্ভিস ফোনের পুরো মানচিত্র শেখে আর
 * এখানেই (ফোনের ভেতরে, SharedPreferences-এ) মনে
 * রাখে — কোথাও পাঠানো হয় না, কোনো নেটওয়ার্ক নেই:
 *  - ইনস্টল করা অ্যাপ (নাম + package),
 *  - কুইক-সেটিংসের টাইল (নাম + স্ক্রিনে জায়গা),
 *  - কন্টাক্ট (নাম + নম্বর — শুধু অনুমতি মিললে)।
 *
 * সব পড়া/লেখা exception-safe: কিছু না মিললে খালি
 * list, কখনো crash নয়। BrainRules wala hi andaaz।
 */
object PhoneLearner {

    /** Ek app — naam + package (khulne ke liye kaafi)। */
    data class LearnedApp(val label: String, val pkg: String)

    /** Ek tile — naam + স্ক্রিনে জায়গা (0..1000, ভগ্নাংশ)। */
    data class LearnedTile(
        val label: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /** Ek contact — naam + number। */
    data class LearnedContact(val name: String, val number: String)

    /** Teenon ki ginati ek saath। */
    data class LearnedCounts(val apps: Int, val tiles: Int, val contacts: Int)

    private const val MAX_APPS = 300
    private const val MAX_TILES = 80
    private const val MAX_CONTACTS = 500

    // ------------------------------------------------------------------
    // Scan (padhna)
    // ------------------------------------------------------------------

    /** Launcher apps — [PhoneActions] wala hi raasta। */
    fun scanApps(context: Context): List<LearnedApp> {
        return try {
            PhoneActions.listLaunchableApps(context)
                .take(MAX_APPS)
                .map { LearnedApp(it.label, it.packageName) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Saare contacts — naam + number, naam ke hisaab se। */
    fun scanContacts(context: Context): List<LearnedContact> {
        val out = ArrayList<LearnedContact>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = context.contentResolver.query(
                uri, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            ) ?: return emptyList()
            cursor.use {
                val nameIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numIdx = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val seen = HashSet<String>()
                while (it.moveToNext() && out.size < MAX_CONTACTS) {
                    val name = if (nameIdx >= 0) {
                        it.getString(nameIdx)?.trim() ?: ""
                    } else {
                        ""
                    }
                    val number = if (numIdx >= 0) {
                        it.getString(numIdx)?.trim() ?: ""
                    } else {
                        ""
                    }
                    if (name.isEmpty() || number.isEmpty()) continue
                    val digits = number.filter { ch -> ch.isDigit() }
                    if (digits.length < 7 || !seen.add(digits)) continue
                    out.add(LearnedContact(name, number))
                }
            }
        } catch (t: Throwable) {
            // Contacts na mile to khaali — asal kaam chalta rahe.
            return emptyList()
        }
        return out
    }

    // ------------------------------------------------------------------
    // Save / read (godam)
    // ------------------------------------------------------------------

    fun saveApps(context: Context, apps: List<LearnedApp>) {
        try {
            val array = JSONArray()
            for (app in apps) {
                val obj = JSONObject()
                obj.put("label", app.label)
                obj.put("pkg", app.pkg)
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_APPS, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agle seekhne par phir koshish hogi.
        }
    }

    fun saveTiles(context: Context, tiles: List<LearnedTile>) {
        try {
            val array = JSONArray()
            for (tile in tiles) {
                val obj = JSONObject()
                obj.put("label", tile.label)
                obj.put("l", tile.left)
                obj.put("t", tile.top)
                obj.put("r", tile.right)
                obj.put("b", tile.bottom)
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_TILES, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agle seekhne par phir koshish hogi.
        }
    }

    fun saveContacts(context: Context, contacts: List<LearnedContact>) {
        try {
            val array = JSONArray()
            for (contact in contacts) {
                val obj = JSONObject()
                obj.put("name", contact.name)
                obj.put("number", contact.number)
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_CONTACTS, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agle seekhne par phir koshish hogi.
        }
    }

    fun saveLearnedAt(context: Context, atMillis: Long) {
        try {
            prefs(context).edit().putLong(KEY_LEARNED_AT, atMillis).apply()
        } catch (t: Throwable) {
            // Samay na bache to ginati hi kaafi hai.
        }
    }

    fun readApps(context: Context): List<LearnedApp> {
        val out = ArrayList<LearnedApp>()
        try {
            val raw = prefs(context).getString(KEY_APPS, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val label = obj.optString("label", "")
                val pkg = obj.optString("pkg", "")
                if (label.isNotEmpty() && pkg.isNotEmpty()) {
                    out.add(LearnedApp(label, pkg))
                }
            }
        } catch (t: Throwable) {
            // Kharab godam khaali hi samjho.
            return emptyList()
        }
        return out
    }

    fun readTiles(context: Context): List<LearnedTile> {
        val out = ArrayList<LearnedTile>()
        try {
            val raw = prefs(context).getString(KEY_TILES, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val label = obj.optString("label", "")
                if (label.isEmpty()) continue
                out.add(
                    LearnedTile(
                        label,
                        obj.optInt("l", 0),
                        obj.optInt("t", 0),
                        obj.optInt("r", 0),
                        obj.optInt("b", 0)
                    )
                )
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    fun readContacts(context: Context): List<LearnedContact> {
        val out = ArrayList<LearnedContact>()
        try {
            val raw = prefs(context).getString(KEY_CONTACTS, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name", "")
                val number = obj.optString("number", "")
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    out.add(LearnedContact(name, number))
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    /** Kab seekha tha (millis) — 0 = kabhi nahi। */
    fun learnedAt(context: Context): Long {
        return try {
            prefs(context).getLong(KEY_LEARNED_AT, 0L)
        } catch (t: Throwable) {
            0L
        }
    }

    /** Phone chena hua hai ya nahi (kam se kam apps mile hon)। */
    fun isLearned(context: Context): Boolean {
        return try {
            learnedAt(context) > 0L && readApps(context).isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    /** Teenon ki taaza ginati। */
    fun counts(context: Context): LearnedCounts {
        return try {
            LearnedCounts(
                readApps(context).size,
                readTiles(context).size,
                readContacts(context).size
            )
        } catch (t: Throwable) {
            LearnedCounts(0, 0, 0)
        }
    }

    /** "12টা অ্যাপ, 8টা বাটন, 30টা কন্টাক্ট" jaisi ek line। */
    fun summaryLine(context: Context): String {
        val c = counts(context)
        return "${c.apps}টা অ্যাপ, ${c.tiles}টা বাটন, ${c.contacts}টা কন্টাক্ট"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "jarvis_phone_map"
    private const val KEY_APPS = "apps_json"
    private const val KEY_TILES = "tiles_json"
    private const val KEY_CONTACTS = "contacts_json"
    private const val KEY_LEARNED_AT = "learned_at"
}

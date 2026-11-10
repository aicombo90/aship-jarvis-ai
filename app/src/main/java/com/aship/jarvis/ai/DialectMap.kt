package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * v97: LEHJA / DIALECT MAP — user ke bol-chaal
 * wale roop (jo recognizer aksar sunta hai) se
 * asal hukm tak ka chhota shabd-kosh।
 *
 * Malik Bangla apne ilaake (North 24 Parganas /
 * Baduria) wali boli me bolte hain — Maya aksar
 * unki Bangla galat sunti hai (2026-11-09 ki
 * shikayat: Hindi me jawab, Bangla galat-suni)।
 * Ye map usi ka ilaaj hai, naye model ke bina:
 *
 *  - SHIKHANA: "'X' শুনলে 'Y' বুঝবে" bolkar
 *    batao — X suna to Y samjho (DialectCommands
 *    dekho)। Map SharedPreferences
 *    "jarvis_dialect_map" me JSON bankar rehta
 *    hai, bilkul [TaughtCommands] jaisa।
 *  - MILAN: pehle bandhi chaabi se barabar,
 *    phir [banglaSkeleton] se (swar/ant wale
 *    halke farq — খুলো/খোলো, চালা/চালাও,
 *    বন্দ/বন্ধ — bhi mil jayein)। Lambe vakya
 *    ke beech ka shabd milan NAHI hai — poori
 *    baat (bhare shabd hatakar) hi milegi,
 *    taaki aam baat galti se koi aur hukm na
 *    ban jaye।
 *  - SUDHAAR: "না, আমি বলেছি 'X'" bolne par
 *    pichli suni hui baat se X tak ka jod
 *    khud bandh jata hai (DialectCommands)।
 *
 * Ye sirf shabd-ka-shabd anuvaad hai — naya
 * speech model NAHI, koi bada download nahi।
 * Recognizer ne jo shabd diya hi nahi, use ye
 * theek nahi kar sakta; wahan imaandaar
 * "শুনতে পাইনি" hi milega।
 */
object DialectMap {

    /** Ek bandha hua bol-chaal roop → asal hukm। */
    data class Mapping(
        val spoken: String,
        val command: String
    )

    /** Is samay bandhe saare jod (purane pehle, naye aakhir me)। */
    fun readAll(context: Context): List<Mapping> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MAPPINGS, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Mapping>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val spoken = obj.optString("spoken").trim()
                val command = obj.optString("command").trim()
                if (spoken.isEmpty() || command.isEmpty()) continue
                out.add(Mapping(spoken, command))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Bandhe jodon ki ginati (mukhya screen ki report ke liye)। */
    fun count(context: Context): Int = readAll(context).size

    /** Naya/badla hua jod bandho (wahi bol-chaal dobara sikhaya jaye to hukm badal jata hai)। */
    fun save(context: Context, spoken: String, command: String) {
        val cleanSpoken = spoken.trim().take(MAX_SPOKEN_CHARS)
        val cleanCommand = command.trim().take(MAX_COMMAND_CHARS)
        if (cleanSpoken.isEmpty() || cleanCommand.isEmpty()) return
        val key = dialectKey(cleanSpoken)
        val all = readAll(context).filterNot {
            dialectKey(it.spoken) == key
        }.toMutableList()
        all.add(Mapping(cleanSpoken, cleanCommand))
        writeAll(context, all)
    }

    /** Bol-chaal se jod mitao। Mita ho to true। */
    fun delete(context: Context, spoken: String): Boolean {
        val key = dialectKey(spoken)
        val all = readAll(context)
        val kept = all.filterNot { dialectKey(it.spoken) == key }
        if (kept.size == all.size) return false
        writeAll(context, kept)
        return true
    }

    /** Baat me kisi bandhe bol-chaal ka zikr saaf ho to woh jod — "ভুলে যাও <shabd>" ke liye। */
    fun findMentioned(context: Context, rawText: String): Mapping? {
        val key = dialectKey(rawText)
        if (key.isEmpty()) return null
        return readAll(context).firstOrNull {
            val spokenKey = dialectKey(it.spoken)
            spokenKey.length >= 2 && key.contains(spokenKey)
        }
    }

    // ---------------------------------------------------------------
    // Milan
    // ---------------------------------------------------------------

    /**
     * Milan ke liye bandhi chaabi — chhote akshar,
     * shunya-chaudai wale nishan (ZWNJ/ZWJ) hataye
     * hue, viram-chinh khaali jagah me badle hue।
     * [TaughtCommands.triggerKey] wahi niyam।
     */
    fun dialectKey(raw: String): String {
        var t = raw.lowercase(Locale.ROOT)
        t = t.replace("\u200C", "").replace("\u200D", "")
        t = t.replace(Regex("[।.,!?,;:—\\-\"'‘’“”()]+"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Bhare shabd (একটু/তো/মায়া...) hatakar chaabi — akele bole hue roop ki pehchan ke liye। */
    private fun strippedKey(raw: String): String {
        val key = dialectKey(raw)
        if (key.isEmpty()) return key
        return key.split(" ")
            .filter { it !in DIALECT_FILLER_TOKENS }
            .joinToString(" ")
            .trim()
    }

    /**
     * Suni hui baat kisi bandhe bol-chaal se mile
     * to uska asal hukm; warna null। Pehle bandhi
     * chaabi se barabar, phir [banglaSkeleton] se
     * (lehja wale swar/ant farq bhi mil jayein)।
     * Kuch na mile to null — aam raasta chalega।
     */
    fun translate(context: Context, rawText: String): String? {
        val heard = strippedKey(rawText)
        if (heard.isEmpty()) return null
        val all = readAll(context)
        if (all.isEmpty()) return null
        all.firstOrNull { strippedKey(it.spoken) == heard }?.let {
            return it.command
        }
        val heardSkeleton = banglaSkeleton(heard)
        all.firstOrNull { banglaSkeleton(strippedKey(it.spoken)) == heardSkeleton }?.let {
            return it.command
        }
        return null
    }

    // ---------------------------------------------------------------
    // Bangla skeleton — lehja wale halke farq ek karo
    // ---------------------------------------------------------------

    /**
     * Bangla-mukhi skeleton — suna text aur pakke
     * shabd dono par ek hi bandha roop lagta hai,
     * taaki lehja ke halke farq milan na todein:
     * swar-chinh hatao (া/ি/ু/ো sab ek), bade/
     * chhote swar ek karo (আ→অ, ঈ→ই, ঊ→উ, ঐ→এ,
     * ঔ→ও), halant + chandrabindu hatao, aur
     * aam lehja-badal (ধ→দ, ভ→ব, শ/ষ→স, ণ→ন,
     * য/য়→জ, ড়→ড, ঢ়→ঢ, ৎ→ত) ek karo। Buniyaad
     * me [CommandEngine.scrollSkeleton] jaisa hi
     * vichaar hai (ZWNJ/ZWJ mukt + Bangla
     * swar-jod) — sirf Bangla ke liye, Devanagari
     * par [CommandEngine.hindiSkeleton] alag se
     * hai। Vyanjan ka farq (ট/ত jaise) isme
     * NAHI — woh galat-suna shabd hai, lehja
     * nahi; use user khud sikhakar theek kare
     * (dialect map) ya fuzzy sudhaar pakde।
     */
    fun banglaSkeleton(input: String): String {
        var t = input.lowercase(Locale.ROOT)
        // Bangla swar-jod pehle ek karo (scrollSkeleton wala hi niyam)।
        t = t.replace("\u09C7\u09BE", "\u09CB")
            .replace("\u09C7\u09D7", "\u09CC")
        t = t.replace("\u200C", "").replace("\u200D", "")
        t = t.replace("\u09CD", "") // halant / virama
        t = t.replace("\u0981", "") // chandrabindu
        // Swar-chinh (matra) hatao — sab swar ek।
        t = t.replace("\u09BE", "") // া
        t = t.replace("\u09BF", "") // ি
        t = t.replace("\u09C0", "") // ী
        t = t.replace("\u09C1", "") // ু
        t = t.replace("\u09C2", "") // ূ
        t = t.replace("\u09C3", "") // ৃ
        t = t.replace("\u09C7", "") // ে
        t = t.replace("\u09C8", "") // ৈ
        t = t.replace("\u09CB", "") // ো
        t = t.replace("\u09CC", "") // ৌ
        // Swatantra swar ek karo।
        t = t.replace('\u0986', '\u0985') // আ -> অ
        t = t.replace('\u0988', '\u0987') // ঈ -> ই
        t = t.replace('\u098A', '\u0989') // ঊ -> উ
        t = t.replace('\u0990', '\u098F') // ঐ -> এ
        t = t.replace('\u0994', '\u0993') // ঔ -> ও
        // Aam lehja-badal (vyanjan) ek karo।
        t = t.replace('\u09A7', '\u09A6') // ধ -> দ
        t = t.replace('\u09AD', '\u09AC') // ভ -> ব
        t = t.replace('\u09B6', '\u09B8') // শ -> স
        t = t.replace('\u09B7', '\u09B8') // ষ -> স
        t = t.replace('\u09A3', '\u09A8') // ণ -> ন
        t = t.replace('\u09AF', '\u099C') // য -> জ
        t = t.replace('\u09DF', '\u099C') // য় -> জ
        t = t.replace('\u09DC', '\u09A1') // ড় -> ড
        t = t.replace('\u09DD', '\u09A2') // ঢ় -> ঢ
        t = t.replace('\u09CE', '\u09A4') // ৎ -> ত
        return t
    }

    // ---------------------------------------------------------------
    // Godam
    // ---------------------------------------------------------------

    private fun writeAll(context: Context, mappings: List<Mapping>) {
        try {
            val capped = mappings
                .map {
                    it.copy(
                        spoken = it.spoken.trim().take(MAX_SPOKEN_CHARS),
                        command = it.command.trim().take(MAX_COMMAND_CHARS)
                    )
                }
                .filter { it.spoken.isNotEmpty() && it.command.isNotEmpty() }
                .takeLast(MAX_MAPPINGS)
            val array = JSONArray()
            for (mapping in capped) {
                array.put(
                    JSONObject()
                        .put("spoken", mapping.spoken)
                        .put("command", mapping.command)
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MAPPINGS, array.toString())
                .apply()
        } catch (t: Throwable) {
            // Godam bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    private const val PREFS_NAME = "jarvis_dialect_map"
    private const val KEY_MAPPINGS = "mappings"

    /** Bandhe jodon ki adhiktam ginati — aage sabse purana hat-ta hai। */
    private const val MAX_MAPPINGS = 30

    /** Ek bol-chaal roop ki adhiktam lambai (aksharon me)। */
    private const val MAX_SPOKEN_CHARS = 60

    /** Ek asal hukm ki adhiktam lambai (aksharon me)। */
    private const val MAX_COMMAND_CHARS = 120

    /** Milan ke samay hatne wale bhare shabd (pukaar-naam samet)। */
    private val DIALECT_FILLER_TOKENS = setOf(
        "একটু", "তো", "শুধু", "please", "zara", "just", "একটুখানি",
        "মায়া", "তারা", "maya", "tara"
    )
}

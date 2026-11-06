package com.aship.jarvis.ai

import android.content.Context
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.PlayIntent
import org.json.JSONArray
import org.json.JSONObject

/**
 * v52: KAAM KE NIYAM (work rules) — user ke sikhaye
 * hue niyam, sirf phone par bandhe hue।
 *
 * User ka hukm (2026-11-06): "Jarvis ke andar ek
 * brain add karo — use sikhaye hue kaam uske yaad
 * rahein aur kaam ke NIYAM bhi yaad rahein, taaki
 * baad me woh unhi niyamon se kaam kare, bina dobara
 * bataye।"
 *
 * Pehle se jo tha, usse ye ALAG hai:
 *  - [MayaMemory] (v31) sirf TATHYA bandhta hai —
 *    woh online mastishk ko jawab dene ke liye
 *    milte hain, koi kaam NAHI chalate।
 *  - [LearnedRoutines] (v49) screen-kaam ki CHAAL
 *    bandhta hai — online mastishk ke screen_task
 *    ke liye playbook banta hai।
 *  - [TaughtCommands] (v50) AKELE shabd → pakka
 *    kaam bandhta hai ("লাইট" = torch) — shart
 *    wala niyam isme nahi banta।
 *  - Ye file SHART WALA NIYAM bandhta hai:
 *    "যখন <X> বলি তখন <Y> করবে" — baad me X jaisa
 *    hukm aate hi Y seedha OFFLINE chalta hai,
 *    user ko dobara batane ki zaroorat nahi।
 *    Shart ke bina wala niyam ("সবসময় বাংলায়
 *    উত্তর দেবে") bhi bandhta hai — woh sirf
 *    mastishk ki hidayat me jata hai (online),
 *    offline usse koi kaam nahi chalta।
 *
 * Niyam do roop me lagta hai:
 *  1. OFFLINE (asli kaam): [applyMatchingRule] —
 *     mezbaan (saamne wali screen + background
 *     wake service) har aam hukm par, pakke
 *     raaste se pehle, isse bulate hain। Shart
 *     mile to uska kaam CHUP-CHAAP chalta hai
 *     (koi alag boli nahi — TTS QUEUE_FLUSH hai,
 *     isliye beech me bolna asal jawab kaat deta;
 *     kaam ka asar dikhta hai: light/data/scroll),
 *     phir asal hukm apne aam raaste se chalta
 *     hai। Screenshot wala niyam saamne wali
 *     screen par NAHI chalta (main thread par
 *     capture ka intezaar atak jata hai — wahi
 *     v40 wala niyam) — woh sirf background
 *     service me chalta hai।
 *  2. ONLINE (mastishk): [instructionBlock] —
 *     [AgentBrain] ki hidayat me bandhe niyam
 *     jate hain, taaki online mastishk bhi unhe
 *     mane — khaas taur par pasand wale niyam,
 *     jo offline kaam nahi chalate।
 *
 * Shart ka MILAN (fuzzy, bilkul [CommandEngine]
 * ki near-miss shaili): shart ke ASLI shabd
 * (kriya/bhare shabd hatakar — "খুলতে বলি"
 * jaisi framing nahi, "ইউটিউব" jaisa matlab
 * wala shabd) hukm me milne chahiye — har
 * shabd barabar ho, ek dusre me samaaya ho, ya
 * edit-distance se bahut kareeb ho (lambai ke
 * hisaab se 1-3 akshar ka farq, wahi seema jo
 * fuzzyCorrect lagata hai)। Isiliye "ইউটিউব
 * খুলতে বলি" wala niyam "ইউটিউব খোলো" par bhi
 * lagta hai — kriya ka roop badalna milan nahi
 * todta। "বন্ধ/চালু/on/off" jaise disha wale
 * shabd hataye NAHI jate — "মোবাইল ডেটা বন্ধ"
 * wala niyam "ডেটা চালু করো" par kabhi nahi
 * lagega।
 *
 * Kaam ki SEEMA [TaughtCommands] wali hi hai:
 * shart ke baad wala kaam sirf wahi bandhta hai
 * jo [TaughtCommands.resolveAction] pehchanta
 * hai (torch, scroll, screenshot, mobile data,
 * app kholna)। Na pehchana jaye to niyam
 * bandhta hi NAHI — bulane wala imaandaar
 * manaahi bolega, aadha-adhura niyam kabhi nahi।
 *
 * Godam: SharedPreferences "jarvis_brain_rules"
 * me "rules" naam par JSON array — har entry:
 * {when, action, text, created, used}। Seemayein:
 * zyada se zyada [MAX_RULES] niyam (aage sabse
 * purana hat-ta hai)। Padhna-likhna poori tarah
 * exception-safe — godam bigde to niyam chup
 * rehta hai, asal hukm kabhi nahi rukta।
 */
object BrainRules {

    /** Ek bandha hua niyam। */
    data class Rule(
        val whenText: String,
        val thenAction: String?,
        val text: String,
        val createdAt: Long,
        val used: Int
    )

    // ---------------------------------------------------------------
    // Godam
    // ---------------------------------------------------------------

    /** Is samay bandhe saare niyam (purane pehle, naye aakhir me)। */
    fun readAll(context: Context): List<Rule> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_RULES, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Rule>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val text = obj.optString("text").trim()
                if (text.isEmpty()) continue
                val actionRaw = obj.optString("action").trim()
                val action = if (actionRaw.isEmpty()) null else actionRaw
                out.add(
                    Rule(
                        whenText = obj.optString("when").trim(),
                        thenAction = action,
                        text = text,
                        createdAt = obj.optLong("created", 0L),
                        used = obj.optInt("used", 0)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Poori suchi wapas bandho — ginati ki seema yahin lagti hai। */
    private fun writeAll(context: Context, rules: List<Rule>) {
        try {
            val capped = rules
                .map {
                    it.copy(
                        whenText = it.whenText.trim()
                            .take(MAX_WHEN_CHARS),
                        text = it.text.trim().take(MAX_TEXT_CHARS)
                    )
                }
                .filter { it.text.isNotEmpty() }
                .takeLast(MAX_RULES)
            val array = JSONArray()
            for (rule in capped) {
                val obj = JSONObject()
                    .put("when", rule.whenText)
                    .put("text", rule.text)
                    .put("created", rule.createdAt)
                    .put("used", rule.used)
                if (rule.thenAction != null) {
                    obj.put("action", rule.thenAction)
                }
                array.put(obj)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, array.toString())
                .apply()
        } catch (t: Throwable) {
            // Godam bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    /** Bandhe niyamon ki ginati (mukhya screen ki report ke liye)। */
    fun count(context: Context): Int = readAll(context).size

    /** Naya/badla hua niyam bandho (wahi shart dobara sikhayi jaye to niyam badal jata hai)। */
    fun save(
        context: Context,
        whenText: String,
        thenAction: String?,
        text: String
    ) {
        val cleanText = text.trim().take(MAX_TEXT_CHARS)
        if (cleanText.isEmpty()) return
        val rule = Rule(
            whenText = whenText.trim().take(MAX_WHEN_CHARS),
            thenAction = thenAction?.trim()?.takeIf { it.isNotEmpty() },
            text = cleanText,
            createdAt = System.currentTimeMillis(),
            used = 0
        )
        val key = dedupeKey(rule)
        val all = readAll(context).toMutableList()
        val existing = all.indexOfFirst { dedupeKey(it) == key }
        if (existing >= 0) {
            // Dobara bandha gaya wahi niyam: purani
            // "used" ginati bachi rahe।
            all[existing] = rule.copy(used = all[existing].used)
        } else {
            all.add(rule)
        }
        writeAll(context, all)
    }

    /** Niyam mitao। Mita ho to true, warna false। */
    fun delete(context: Context, rule: Rule): Boolean {
        val key = dedupeKey(rule)
        val all = readAll(context)
        val kept = all.filterNot { dedupeKey(it) == key }
        if (kept.size == all.size) return false
        writeAll(context, kept)
        return true
    }

    /** Saare niyam mitao। Kuch mita ho to true। */
    fun clearAll(context: Context): Boolean {
        if (readAll(context).isEmpty()) return false
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_RULES)
                .apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Ek niyam ka istemal ginati me jodo (asal me laga tabhi)। */
    private fun incrementUsed(context: Context, whenText: String) {
        val all = readAll(context).map { rule ->
            if (rule.whenText == whenText) {
                rule.copy(used = rule.used + 1)
            } else {
                rule
            }
        }
        writeAll(context, all)
    }

    /** Dedupe ki chaabi — shart ho to wahi, warna poora niyam-text। */
    private fun dedupeKey(rule: Rule): String =
        TaughtCommands.triggerKey(
            rule.whenText.ifBlank { rule.text }
        )

    // ---------------------------------------------------------------
    // Milan
    // ---------------------------------------------------------------

    /**
     * Shart ke ASLI shabd — [TaughtCommands.triggerKey]
     * wali chaabi se, framing wali kriya/bhare shabd
     * hatakar। "ইউটিউব খুলতে বলি" → ["ইউটিউব"];
     * "মোবাইল ডেটা বন্ধ করতে বলি" → ["মোবাইল",
     * "ডেটা", "বন্ধ"] (disha bachi rehti hai)।
     */
    fun keywordsOf(phrase: String): List<String> {
        val key = TaughtCommands.triggerKey(phrase)
        if (key.isEmpty()) return emptyList()
        return key.split(" ")
            .filter { it.isNotEmpty() && it !in RULE_STOP_WORDS }
            .distinct()
    }

    /**
     * Is hukm par koi bandha niyam lagta ho to woh
     * (sabse naya pehle — dobara sikhaya gaya niyam
     * purane ko dabata hai)। Shart ke saare asli
     * shabd hukm me fuzzy-milan se milne chahiye;
     * shart me asli shabd bache hi na hon to poori
     * shart ka substring-milan dekha jata hai।
     */
    fun matchForCommand(context: Context, rawText: String): Rule? {
        val cmdKey = TaughtCommands.triggerKey(rawText)
        if (cmdKey.isEmpty()) return null
        val cmdWords = cmdKey.split(" ").filter { it.isNotEmpty() }
        for (rule in readAll(context).asReversed()) {
            val keywords = keywordsOf(rule.whenText)
            if (keywords.isEmpty()) {
                val whenKey = TaughtCommands.triggerKey(rule.whenText)
                if (whenKey.isNotEmpty() &&
                    (cmdKey.contains(whenKey) || whenKey.contains(cmdKey))
                ) {
                    return rule
                }
                continue
            }
            val allPresent = keywords.all { keyword ->
                cmdWords.any { wordMatches(keyword, it) }
            }
            if (allPresent) return rule
        }
        return null
    }

    /** Baat me kisi bandhe niyam ka zikr saaf ho to woh — "… ভুলে যাও" ke liye। */
    fun findMentioned(context: Context, rawText: String): Rule? =
        matchForCommand(context, rawText)

    /**
     * Do shabdon ka fuzzy milan — barabar, ek
     * dusre me samaaya (kam se kam 3 akshar wala
     * tukda), ya edit-distance se kareeb
     * (wahi seema jo [CommandEngine] ki fuzzy
     * sudhaar lagati hai: lambi shabd par 3,
     * beech wale par 2, chhote par 1)।
     */
    private fun wordMatches(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 3 && b.contains(a)) return true
        if (b.length >= 3 && a.contains(b)) return true
        val allowed = when {
            a.length >= 7 || b.length >= 7 -> 3
            a.length >= 5 || b.length >= 5 -> 2
            else -> 1
        }
        return levenshtein(a, b) in 1..allowed
    }

    /** Classic edit distance (chars = UTF-16 units; heuristic ke liye kaafi)। */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    // ---------------------------------------------------------------
    // Kaam chalana (offline)
    // ---------------------------------------------------------------

    /**
     * Hukm par lagne wala niyam ho to uska kaam
     * chalao — chup-chaap (koi boli nahi, upar
     * dekho) — aur laga hua niyam lautao, warna
     * null। Pasand wala niyam (koi kaam nahi)
     * kabhi nahi chalta — woh sirf mastishk ki
     * hidayat me rehta hai। Screenshot wala kaam
     * [allowScreenshot] false ho to nahi chalta
     * (saamne wali screen wala niyam)।
     */
    fun applyMatchingRule(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        allowScreenshot: Boolean
    ): Rule? {
        return try {
            val rule = matchForCommand(context, rawText) ?: return null
            val action = rule.thenAction ?: return null
            if (action == TaughtCommands.ACTION_SCREENSHOT &&
                !allowScreenshot
            ) {
                return null
            }
            TaughtCommands.execute(
                context, engine,
                TaughtCommands.Mapping(rule.whenText, action), lang
            )
            incrementUsed(context, rule.whenText)
            rule
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Mastishk ki hidayat ke liye block
    // ---------------------------------------------------------------

    /**
     * AgentBrain ke instruction ke liye compact
     * block: bandhe niyam, naye aakhir me (zyada
     * lamba ho to sabse purane chhodkar aakhiri
     * ~1500 akshar wale rakhe jate hain — naye
     * niyam kabhi nahi kat-te)। Godam khaali ho
     * to null।
     */
    fun instructionBlock(context: Context): String? {
        val rules = readAll(context)
        if (rules.isEmpty()) return null
        val picked = mutableListOf<Rule>()
        var length = HEADER.length + 8
        for (rule in rules.asReversed()) {
            if (picked.size >= MAX_BLOCK_RULES) break
            val lineLength = ruleLine(rule).length + 8
            if (picked.isNotEmpty() &&
                length + lineLength > MAX_BLOCK_CHARS
            ) {
                break
            }
            picked.add(rule)
            length += lineLength
        }
        picked.reverse()
        val body = picked.mapIndexed { index, rule ->
            "${index + 1}) ${ruleLine(rule)}"
        }.joinToString("\n")
        return "$HEADER\n$body"
    }

    /** Ek niyam ki instruction wali line — shart wala niyam kaam ke saath, pasand wala jyon ka tyon। */
    private fun ruleLine(rule: Rule): String {
        val action = rule.thenAction
        return if (action != null) {
            "When the user says \"${rule.whenText}\", first do this: " +
                TaughtCommands.describe(action, PlayIntent.SpokenLang.ENGLISH) +
                ". Then carry out the user's request as normal."
        } else {
            rule.text
        }
    }

    private const val HEADER =
        "WORK RULES — rules the user personally taught you " +
            "(stored only on this phone). Follow them every " +
            "time they apply, WITHOUT being told again:"

    private const val PREFS_NAME = "jarvis_brain_rules"
    private const val KEY_RULES = "rules"

    /** Bandhe niyamon ki adhiktam ginati — aage sabse purana hat-ta hai। */
    private const val MAX_RULES = 30

    /** Ek shart ki adhiktam lambai (aksharon me)। */
    private const val MAX_WHEN_CHARS = 80

    /** Ek niyam-text ki adhiktam lambai (aksharon me)। */
    private const val MAX_TEXT_CHARS = 200

    /** Instruction block me zyada se zyada itne niyam। */
    private const val MAX_BLOCK_RULES = 10

    /** Instruction block ki lagbhag adhiktam lambai (aksharon me)। */
    private const val MAX_BLOCK_CHARS = 1500

    /**
     * Shart ke shabdon me se hatne wale framing
     * wale shabd — kriya ka roop ("খুলতে/খোলো"),
     * bolne/batane wale shabd ("বলি/বললে"), bhare
     * shabd aur sarvanaam। Disha wale shabd
     * ("বন্ধ/চালু/on/off") is suchi me NAHI hain —
     * woh milan me bachte hain।
     */
    private val RULE_STOP_WORDS = setOf(
        "খোলো", "খোল", "খুলে", "খুলতে", "খুলো",
        "করো", "কর", "করবে", "করে",
        "বলি", "বললে", "বলো", "বল", "বলবে", "বলতে",
        "দাও", "দে", "দেবে", "দিও",
        "চাই", "চাইলে",
        "তুমি", "তুই", "আপনি",
        "তো", "একটু", "না", "নাও", "নে",
        "এটা", "ওটা", "এই", "ওই",
        "যখন", "তখন", "যদি",
        "আমি", "আমাকে", "আমার",
        "তারপর",
        "खोलो", "खोल",
        "करो", "कर",
        "कहो", "बोलो",
        "दो", "चाहिए",
        "तुम", "आप",
        "तो", "जब", "अगर",
        "मुझे", "मेरा",
        "open", "do", "say", "tell", "please",
        "when", "then", "if",
        "you", "me", "my", "the", "a", "to"
    )
}

package com.aship.jarvis.ai

import android.content.Context
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.CommandResult
import com.aship.jarvis.commands.PlayIntent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * v50: SIKHAYE HUE SHABD-KAAM (teachable
 * word→action mappings) — user ka apna chhota
 * hukm-shabd-kosh।
 *
 * User bolkar/likhkar sikhata hai:
 * "শিখে নাও: 'লাইট' বললে টর্চ জ্বালাবে" —
 * bandha shabd (trigger) aur bandha kaam
 * (action) SharedPreferences "jarvis_taught"
 * me JSON bankar rehte hain। Baad me woh
 * shabd akele bole/likhe jane par kaam
 * seedha offline chalta hai — mastishk ka
 * chakkar hi nahi (bandha milan, bandha
 * kaam)।
 *
 * Kaam SIKHATE samay [resolveAction] se
 * bandhe auzaaron me badla jata hai: torch
 * on/off, scroll up/down, screenshot, ya
 * "cmd:<text>" (CommandEngine.handle se
 * chalne wala pakka hukm, jaise app kholna /
 * mobile data)। Jo kaam inme na badle woh
 * seekha hi NAHI jata — bulane wala imaandaar
 * manaahi bolega, aadha-adhura bandhan kabhi
 * nahi।
 *
 * Pehle se sikha hua (bandha) milan: akela
 * "লাইট" = torch ON (user ka pakka hukm) —
 * godam pehli baar bante hi ye bandh jata
 * hai; user chahe to "ভুলে যাও" se mita bhi
 * sakta hai, aur naya "লাইট" sikhakar badal
 * bhi sakta hai।
 *
 * Seemayein: zyada se zyada [MAX_MAPPINGS]
 * bandhan (aage sabse purana hat-ta hai)।
 * Padhna-likhna poori tarah exception-safe —
 * godam bigde to ye raasta chup rehta hai,
 * asal hukm kabhi nahi rukta।
 */
object TaughtCommands {

    /** Ek bandha hua shabd→kaam jod। */
    data class Mapping(
        val trigger: String,
        val action: String
    )

    /** Chalaye gaye bandhe kaam ka natija + kaun sa kaam chala (mezbaan session wale faisle isse leta hai)। */
    data class Execution(
        val result: CommandResult,
        val actionId: String
    )

    const val ACTION_TORCH_ON = "torch_on"
    const val ACTION_TORCH_OFF = "torch_off"
    const val ACTION_SCROLL_DOWN = "scroll_down"
    const val ACTION_SCROLL_UP = "scroll_up"
    const val ACTION_SCREENSHOT = "screenshot"

    /** "cmd:<text>" — handle() se chalne wala pakka hukm (app kholna, data on/off, aadi)। */
    private const val ACTION_CMD_PREFIX = "cmd:"

    // ---------------------------------------------------------------
    // Godam
    // ---------------------------------------------------------------

    /** Is samay bandhe saare jod (purane pehle, naye aakhir me); pehli baar me bandha "লাইট" jod bankar aata hai। */
    fun readAll(context: Context): List<Mapping> {
        ensureSeeded(context)
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MAPPINGS, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Mapping>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val trigger = obj.optString("trigger").trim()
                val action = obj.optString("action").trim()
                if (trigger.isEmpty() || action.isEmpty()) continue
                out.add(Mapping(trigger, action))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Poori suchi wapas bandho — ginati ki seema yahin lagti hai। */
    private fun writeAll(context: Context, mappings: List<Mapping>) {
        try {
            val capped = mappings
                .map {
                    it.copy(
                        trigger = it.trigger.trim().take(MAX_TRIGGER_CHARS),
                        action = it.action.trim()
                    )
                }
                .filter { it.trigger.isNotEmpty() && it.action.isNotEmpty() }
                .takeLast(MAX_MAPPINGS)
            val array = JSONArray()
            for (mapping in capped) {
                array.put(
                    JSONObject()
                        .put("trigger", mapping.trigger)
                        .put("action", mapping.action)
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

    /** Bandhe jodon ki ginati (mukhya screen ki report ke liye)। */
    fun count(context: Context): Int = readAll(context).size

    /**
     * Pehli baar godam bane to bandha
     * (pre-taught) jod bandho — akela "লাইট"
     * bolne par torch ON (user ka pakka
     * hukm)। Nishan (seed flag) bandh jaye to
     * dobara kabhi nahi bandhta — user mita
     * de to mita hi rehta hai।
     */
    private fun ensureSeeded(context: Context) {
        try {
            val prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            if (prefs.getBoolean(KEY_SEEDED, false)) return
            val defaults = DEFAULT_TRIGGERS.map {
                Mapping(it, ACTION_TORCH_ON)
            }
            val array = JSONArray()
            for (mapping in defaults) {
                array.put(
                    JSONObject()
                        .put("trigger", mapping.trigger)
                        .put("action", mapping.action)
                )
            }
            prefs.edit()
                .putString(KEY_MAPPINGS, array.toString())
                .putBoolean(KEY_SEEDED, true)
                .apply()
        } catch (t: Throwable) {
            // Beej bonus hai — na bandhe to bhi aam raasta chalega.
        }
    }

    /** Naya/badla hua jod bandho (wahi shabd dobara sikhaaya jaye to kaam badal jata hai)। */
    fun save(context: Context, trigger: String, action: String) {
        val cleanTrigger = trigger.trim().take(MAX_TRIGGER_CHARS)
        if (cleanTrigger.isEmpty() || action.isBlank()) return
        val key = triggerKey(cleanTrigger)
        val all = readAll(context).filterNot {
            triggerKey(it.trigger) == key
        }.toMutableList()
        all.add(Mapping(cleanTrigger, action))
        writeAll(context, all)
    }

    /** Shabd se jod mitao। Mita ho to woh jod, warna null। */
    fun delete(context: Context, mapping: Mapping): Boolean {
        val key = triggerKey(mapping.trigger)
        val all = readAll(context)
        val kept = all.filterNot { triggerKey(it.trigger) == key }
        if (kept.size == all.size) return false
        writeAll(context, kept)
        return true
    }

    // ---------------------------------------------------------------
    // Milan
    // ---------------------------------------------------------------

    /**
     * Milan ke liye shabd ki bandhi chaabi —
     * chhote akshar, shunya-chaudai wale nishan
     * (ZWNJ/ZWJ) hataye hue, viram-chinh khaali
     * jagah me badle hue। Dono taraf yahi chaabi
     * lagti hai, isliye recognizer ke halke
     * likhavat-farq milan nahi todte।
     */
    fun triggerKey(raw: String): String {
        var t = raw.lowercase(Locale.ROOT)
        t = t.replace("\u200C", "").replace("\u200D", "")
        t = t.replace(Regex("[।.,!?,;:—\\-\"'‘’“”()]+"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Bhare shabd (একটু/তো/please...) hatakar chaabi — akele bole hue shabd ki pehchan ke liye। */
    private fun strippedKey(raw: String): String {
        val key = triggerKey(raw)
        if (key.isEmpty()) return key
        return key.split(" ")
            .filter { it !in TRIGGER_FILLER_TOKENS }
            .joinToString(" ")
            .trim()
    }

    /**
     * Ye baat kisi bandhe shabd ka AKELE bola
     * hua roop hai kya — poori baat (bhare
     * shabd hatakar) bandhe shabd ke barabar
     * ho tabhi। Lambe vakya ke beech me aaya
     * shabd milan NAHI hai — aam baat galti se
     * kaam nahi banegi।
     */
    fun matchTrigger(context: Context, rawText: String): Mapping? {
        val heard = strippedKey(rawText)
        if (heard.isEmpty()) return null
        return readAll(context).firstOrNull {
            strippedKey(it.trigger) == heard
        }
    }

    /** Baat me kisi bandhe shabd ka zikr saaf ho to woh jod — "ভুলে যাও <shabd>" ke liye। */
    fun findMentioned(context: Context, rawText: String): Mapping? {
        val key = triggerKey(rawText)
        if (key.isEmpty()) return null
        return readAll(context).firstOrNull {
            val triggerKey = triggerKey(it.trigger)
            triggerKey.length >= 2 && key.contains(triggerKey)
        }
    }

    // ---------------------------------------------------------------
    // Sikhate samay kaam ki pehchan
    // ---------------------------------------------------------------

    /**
     * Sikhaye gaye kaam ke text ko bandhe
     * kaam-id me badlo; na badle to null
     * (bulane wala kuch bandhega hi nahi aur
     * imaandaari se mana karega)। Pehchan
     * wahi bandhi mashinari hai jo asal hukm
     * chalati hai — scroll ke liye
     * [CommandEngine.classifyScrollDirection],
     * screenshot ke liye
     * [CommandEngine.isScreenshotCommand],
     * aur baaki ke liye yahin ke bandhe niyam।
     */
    fun resolveAction(context: Context, actionText: String): String? {
        val raw = actionText.trim()
        if (raw.isEmpty()) return null
        val engine = CommandEngine(context)
        // 1. Scroll — disha saaf mile tabhi।
        engine.classifyScrollDirection(raw)?.let { down ->
            return if (down) ACTION_SCROLL_DOWN else ACTION_SCROLL_UP
        }
        // 2. Screenshot — pakka screenshot hukm ho tabhi।
        if (engine.isScreenshotCommand(raw)) return ACTION_SCREENSHOT
        val lower = raw.lowercase(Locale.ROOT)
        // 3. Torch / light — OFF wala shabd ho to OFF,
        // warna ON (bandha purana niyam)।
        val lightMention = lower.contains("torch") ||
            lower.contains("flashlight") || lower.contains("flash light") ||
            lower.contains("টর্চ") || lower.contains("লাইট") ||
            lower.contains("टॉर्च") || lower.contains("लाइट")
        if (lightMention) {
            return if (hasOffMark(lower)) ACTION_TORCH_OFF else ACTION_TORCH_ON
        }
        // 4. Mobile data — disha saaf ho to bandha
        // canonical hukm; disha na mile to toggle
        // wala aam hukm bhi chalega (handle wahi
        // karta hai), isliye wahi bandho।
        val dataMention = lower.contains("mobile data") ||
            lower.contains("মোবাইল ডাটা") || lower.contains("মোবাইল ডেটা") ||
            lower.contains("मोबाइल डेटा") || lower.contains("data")
        if (dataMention) {
            return when {
                hasOffMark(lower) -> ACTION_CMD_PREFIX + "mobile data off"
                hasOnMark(lower) -> ACTION_CMD_PREFIX + "mobile data on"
                else -> ACTION_CMD_PREFIX + "mobile data"
            }
        }
        // 5. App kholna — kholne ki kriya + koi naam
        // bacha ho to handle() wala pakka raasta।
        val normalized = triggerKey(raw)
        val openVerb = OPEN_VERB_MARKERS.any { normalized.contains(it) }
        if (openVerb) {
            val rest = OPEN_VERB_MARKERS.fold(normalized) { acc, verb ->
                acc.replace(verb, " ")
            }.replace(Regex("\\s+"), " ").trim()
            if (rest.isNotEmpty()) return ACTION_CMD_PREFIX + raw
        }
        return null
    }

    /** Bandhe kaam-id ki boli-jaane-wali chhoti pehchan — sikhane/list wale jawabon ke liye। */
    fun describe(action: String, lang: PlayIntent.SpokenLang): String =
        when (action) {
            ACTION_TORCH_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "টর্চ জ্বলবে"
                PlayIntent.SpokenLang.HINDI -> "टॉर्च जलेगी"
                PlayIntent.SpokenLang.ENGLISH -> "the torch turns on"
            }
            ACTION_TORCH_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "টর্চ বন্ধ হবে"
                PlayIntent.SpokenLang.HINDI -> "टॉर्च बंद होगी"
                PlayIntent.SpokenLang.ENGLISH -> "the torch turns off"
            }
            ACTION_SCROLL_DOWN -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "নিচে স্ক্রল হবে"
                PlayIntent.SpokenLang.HINDI -> "नीचे स्क्रॉल होगा"
                PlayIntent.SpokenLang.ENGLISH -> "it scrolls down"
            }
            ACTION_SCROLL_UP -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "উপরে স্ক্রল হবে"
                PlayIntent.SpokenLang.HINDI -> "ऊपर स्क्रॉल होगा"
                PlayIntent.SpokenLang.ENGLISH -> "it scrolls up"
            }
            ACTION_SCREENSHOT -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "স্ক্রিনশট নেওয়া হবে"
                PlayIntent.SpokenLang.HINDI -> "स्क्रीनशॉट लिया जाएगा"
                PlayIntent.SpokenLang.ENGLISH -> "a screenshot is taken"
            }
            else -> when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${action.removePrefix(ACTION_CMD_PREFIX)}' কাজটা হবে"
                PlayIntent.SpokenLang.HINDI ->
                    "'${action.removePrefix(ACTION_CMD_PREFIX)}' वाला काम होगा"
                PlayIntent.SpokenLang.ENGLISH ->
                    "the command '${action.removePrefix(ACTION_CMD_PREFIX)}' runs"
            }
        }

    /** OFF ke bandhe sanket — sikhaye gaye kaam ki disha pehchanne ke liye। */
    private fun hasOffMark(lower: String): Boolean =
        OFF_MARKERS.any { lower.contains(it) }

    /** ON ke bandhe sanket। */
    private fun hasOnMark(lower: String): Boolean =
        ON_MARKERS.any { lower.contains(it) }

    private val OFF_MARKERS = listOf(
        "বন্ধ", "বন্দ", "অফ", "off", "बंद", "बन्द", "ऑफ", "নিভিয়ে", "নেভাও"
    )

    private val ON_MARKERS = listOf(
        "অন", "on", "চালু", "জ্বালাও", "জ্বালা", "জ্বলবে",
        "चालू", "जलाओ", "जला", "ऑन"
    )

    /** App kholne ki kriya ke sanket (chaabi-roop — chhote akshar, latin/bangla)। */
    private val OPEN_VERB_MARKERS = listOf(
        "kholo", "khol", "open", "খোলো", "খোল", "খুল"
    )

    // ---------------------------------------------------------------
    // Kaam chalana
    // ---------------------------------------------------------------

    /**
     * Bandha kaam chalao — [engine] mezbaan ka
     * apna (background/foreground wala bandobast
     * usi ka rehta hai)। Jawaab hukm ki bhasha
     * [lang] me। Kaam na chale to jhoothi
     * safalta kabhi nahi — sachha nakam jawab।
     */
    fun execute(
        context: Context,
        engine: CommandEngine,
        mapping: Mapping,
        lang: PlayIntent.SpokenLang
    ): Execution {
        val action = mapping.action
        val result = when {
            action == ACTION_TORCH_ON -> torchResult(context, true, lang)
            action == ACTION_TORCH_OFF -> torchResult(context, false, lang)
            action == ACTION_SCROLL_DOWN ->
                engine.deterministicScrollCommand(SCROLL_DOWN_CANONICAL)
                    ?: CommandResult(scrollFailText(lang), true)
            action == ACTION_SCROLL_UP ->
                engine.deterministicScrollCommand(SCROLL_UP_CANONICAL)
                    ?: CommandResult(scrollFailText(lang), true)
            action == ACTION_SCREENSHOT ->
                engine.runScreenshotCommand(mapping.trigger)
            action.startsWith(ACTION_CMD_PREFIX) -> {
                val inner = engine.handle(
                    action.removePrefix(ACTION_CMD_PREFIX)
                )
                if (inner.handled) inner else CommandResult(
                    cmdFailText(lang), true
                )
            }
            else -> CommandResult(cmdFailText(lang), true)
        }
        return Execution(result, action)
    }

    private fun torchResult(
        context: Context,
        on: Boolean,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val ok = PhoneActions.setTorch(context, on)
        return if (ok) {
            CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        if (on) "লাইট জ্বালিয়ে দিয়েছি।" else "লাইট বন্ধ করে দিয়েছি।"
                    PlayIntent.SpokenLang.HINDI ->
                        if (on) "लाइट जला दी।" else "लाइट बंद कर दी।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        if (on) "Light on." else "Light off."
                },
                true
            )
        } else {
            CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "এই ফোনে লাইট নিয়ন্ত্রণ হলো না।"
                    PlayIntent.SpokenLang.HINDI ->
                        "इस फ़ोन पर लाइट कंट्रोल नहीं हो पाया।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Couldn't control the light on this phone."
                },
                true
            )
        }
    }

    private fun scrollFailText(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "স্ক্রল করতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI -> "स्क्रॉल नहीं हो पाया।"
            PlayIntent.SpokenLang.ENGLISH -> "Couldn't scroll."
        }

    private fun cmdFailText(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "শেখানো কাজটা করতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "सिखाया हुआ काम नहीं हो पाया।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't do the taught command."
        }

    private const val PREFS_NAME = "jarvis_taught"
    private const val KEY_MAPPINGS = "mappings"
    private const val KEY_SEEDED = "seeded_v50"

    /** Bandhe jodon ki adhiktam ginati — aage sabse purana hat-ta hai। */
    private const val MAX_MAPPINGS = 30

    /** Ek bandhe shabd ki adhiktam lambai (aksharon me)। */
    private const val MAX_TRIGGER_CHARS = 60

    /** Bandhe scroll hukmon ke canonical roop — engine ki apni pehchan inpar pakki hai। */
    private const val SCROLL_DOWN_CANONICAL = "স্ক্রল ডাউন"
    private const val SCROLL_UP_CANONICAL = "স্ক্রল আপ"

    /** Pehle se sikha hua jod: akele ye shabd = torch ON (user ka pakka hukm)। */
    private val DEFAULT_TRIGGERS = listOf(
        "লাইট", "লাইট জ্বালাও", "লাইট জ্বালো",
        "light", "लाइट", "लाइट जलाओ"
    )

    /** Milan ke samay hatne wale bhare shabd। */
    private val TRIGGER_FILLER_TOKENS = setOf(
        "একটু", "তো", "শুধু", "please", "zara", "just", "একটুখানি"
    )
}

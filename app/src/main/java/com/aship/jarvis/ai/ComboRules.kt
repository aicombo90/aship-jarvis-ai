package com.aship.jarvis.ai

import android.content.Context
import android.os.SystemClock
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.CommandEngine
import com.aship.jarvis.commands.PlayIntent
import org.json.JSONArray
import org.json.JSONObject

/**
 * v69: COMBO (ek bol par kai kaam) — user ke
 * sikhaye hue combo, sirf phone par bandhe hue।
 *
 * User ko 2026-11-07 ko bataya gaya tha: Maya
 * khud se naya feature nahi bana sakti, par
 * purane kaamon ko JODKAR naya combo bana
 * sakti hai ("গুড নাইট" = ডেটা বন্ধ + ফোন
 * লক)। Ye wahi hai — [BrainRules] wale niyam
 * se ALAG:
 *  - Niyam ("যখন X বলি তখন Y করবে") ek kaam
 *    CHUP-CHAAP asal hukm se PEHLE chalata hai;
 *    asal hukm phir bhi apne raaste se chalta hai।
 *  - Combo ("গুড নাইট") khud ASAL hukm hai:
 *    bolte hi uske 2-5 kaam ek ke baad ek
 *    chalte hain, aur ek chhota hisaab bola
 *    jata hai (kaun sa hua, kaun sa nahi)।
 *    Combo bina bole kabhi nahi chalta।
 *
 * Har dhap wahi bandhe auzaar hai jo
 * [TaughtCommands.resolveAction] pehchanta hai
 * (torch, scroll, screenshot, mobile data,
 * app kholna) + naya LOCK dhap (ফোন লক)।
 * Call/message wale dhap combo me NAHI hain —
 * woh aadha-adhura reh jayenge (dialer khulna,
 * bhejna) isliye sikhate samay hi mana kar
 * diya jata hai। Data wale dhap me disha
 * (চালু/বন্ধ) pakki honi chahiye — bina disha
 * ka toggle combo me nahi bandhta।
 *
 * Chalana (offline, [runCombo]): dhap ek ke
 * baad ek, beech me chhota thahrav; koi dhap
 * asafal ho to imaandaari se aage badho (chup
 * rehkar chhodo nahi, jhoothi safalta kabhi
 * nahi); lock wala dhap safal hote hi combo
 * ruk jata hai (phone lock — aage ke dhap
 * chal hi nahi sakte, hisaab me wahi likha
 * jata hai)। Screenshot wala dhap saamne wali
 * screen par nahi chalta ([allowScreenshot]
 * false ho to chhoda jata hai, baaki dhap
 * chalte hain) — wahi v40/v52 wala niyam।
 *
 * Godam: SharedPreferences "jarvis_combos" me
 * "combos" naam par JSON array — har entry:
 * {trigger, steps:[action-id...], text,
 * created, used}। Seemayein: zyada se zyada
 * [MAX_COMBOS] combo, har combo me zyada se
 * zyada [MAX_STEPS] dhap। Padhna-likhna poori
 * tarah exception-safe — godam bigde to combo
 * chup rehta hai, asal hukm kabhi nahi rukta।
 */
object ComboRules {

    /** Ek bandha hua combo। */
    data class Combo(
        val trigger: String,
        val steps: List<String>,
        val text: String,
        val createdAt: Long,
        val used: Int
    )

    /** Ek dhap ka natija। */
    data class StepRun(
        val action: String,
        val ok: Boolean,
        val skippedScreenshot: Boolean = false
    )

    /** Poore combo ki daud ka hisaab। */
    data class RunReport(
        val steps: List<StepRun>,
        val stoppedByLock: Boolean
    )

    /** Ek dhap ka andaruni natija। */
    private data class StepOutcome(
        val ok: Boolean,
        val stoppedByLock: Boolean = false,
        val skippedScreenshot: Boolean = false
    )

    /** Naya kaam-id: ফোন লক (v59 wala raasta, seedha Boolean)। */
    const val ACTION_LOCK = "lock"

    // ---------------------------------------------------------------
    // Godam
    // ---------------------------------------------------------------

    /** Is samay bandhe saare combo (purane pehle, naye aakhir me)। */
    fun readAll(context: Context): List<Combo> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_COMBOS, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Combo>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val trigger = obj.optString("trigger").trim()
                val text = obj.optString("text").trim()
                if (trigger.isEmpty() || text.isEmpty()) continue
                val stepsArray = obj.optJSONArray("steps") ?: continue
                val steps = mutableListOf<String>()
                for (j in 0 until stepsArray.length()) {
                    val step = stepsArray.optString(j).trim()
                    if (step.isNotEmpty()) steps.add(step)
                }
                if (steps.size < 2) continue
                out.add(
                    Combo(
                        trigger = trigger,
                        steps = steps,
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
    private fun writeAll(context: Context, combos: List<Combo>) {
        try {
            val capped = combos
                .map {
                    it.copy(
                        trigger = it.trigger.trim().take(MAX_TRIGGER_CHARS),
                        text = it.text.trim().take(MAX_TEXT_CHARS)
                    )
                }
                .filter {
                    it.trigger.isNotEmpty() && it.text.isNotEmpty() &&
                        it.steps.size in 2..MAX_STEPS
                }
                .takeLast(MAX_COMBOS)
            val array = JSONArray()
            for (combo in capped) {
                val stepsArray = JSONArray()
                for (step in combo.steps) stepsArray.put(step)
                array.put(
                    JSONObject()
                        .put("trigger", combo.trigger)
                        .put("steps", stepsArray)
                        .put("text", combo.text)
                        .put("created", combo.createdAt)
                        .put("used", combo.used)
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_COMBOS, array.toString())
                .apply()
        } catch (t: Throwable) {
            // Godam bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    /** Bandhe combon ki ginati (mukhya screen ki report ke liye)। */
    fun count(context: Context): Int = readAll(context).size

    /** Naya/badla hua combo bandho (wahi bol dobara sikhaya jaye to combo badal jata hai)। */
    fun save(
        context: Context,
        trigger: String,
        steps: List<String>,
        text: String
    ) {
        val cleanTrigger = trigger.trim().take(MAX_TRIGGER_CHARS)
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }
        val cleanText = text.trim().take(MAX_TEXT_CHARS)
        if (cleanTrigger.isEmpty() || cleanText.isEmpty()) return
        if (cleanSteps.size !in 2..MAX_STEPS) return
        val combo = Combo(
            trigger = cleanTrigger,
            steps = cleanSteps,
            text = cleanText,
            createdAt = System.currentTimeMillis(),
            used = 0
        )
        val key = dedupeKey(combo)
        val all = readAll(context).toMutableList()
        val existing = all.indexOfFirst { dedupeKey(it) == key }
        if (existing >= 0) {
            // Dobara bandha gaya wahi combo: purani
            // "used" ginati bachi rahe।
            all[existing] = combo.copy(used = all[existing].used)
        } else {
            all.add(combo)
        }
        writeAll(context, all)
    }

    /** Combo mitao। Mita ho to true, warna false। */
    fun delete(context: Context, combo: Combo): Boolean {
        val key = dedupeKey(combo)
        val all = readAll(context)
        val kept = all.filterNot { dedupeKey(it) == key }
        if (kept.size == all.size) return false
        writeAll(context, kept)
        return true
    }

    /** Saare combo mitao। Kuch mita ho to true। */
    fun clearAll(context: Context): Boolean {
        if (readAll(context).isEmpty()) return false
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_COMBOS)
                .apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Ek combo ka istemal ginati me jodo (asal me chala tabhi)। */
    private fun incrementUsed(context: Context, trigger: String) {
        try {
            val all = readAll(context).map { combo ->
                if (combo.trigger == trigger) {
                    combo.copy(used = combo.used + 1)
                } else {
                    combo
                }
            }
            writeAll(context, all)
        } catch (t: Throwable) {
            // Ginati bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    /** Dedupe ki chaabi — bol ki bandhi chaabi। */
    private fun dedupeKey(combo: Combo): String =
        triggerKey(combo.trigger)

    // ---------------------------------------------------------------
    // Milan
    // ---------------------------------------------------------------

    /**
     * Milan ke liye bol ki bandhi chaabi —
     * [TaughtCommands.triggerKey] wali hi shaili,
     * upar se bhare shabd hataye hue। "গুড নাইট"
     * aur "গুড নাইট তো" ek hi bol hain।
     */
    fun triggerKey(raw: String): String {
        val key = TaughtCommands.triggerKey(raw)
        if (key.isEmpty()) return key
        return key.split(" ")
            .filter { it !in TRIGGER_FILLER_TOKENS }
            .joinToString(" ")
            .trim()
    }

    /**
     * Ye baat kisi bandhe combo ka AKELE bola hua
     * roop hai kya — poori baat (bhare shabd
     * hatakar) bandhe bol ke barabar ho tabhi।
     * Lambe vakya ke beech me aaya bol milan
     * NAHI hai — aam baat galti se combo nahi
     * banegi।
     */
    fun matchTrigger(context: Context, rawText: String): Combo? {
        val heard = triggerKey(rawText)
        if (heard.isEmpty()) return null
        return readAll(context).firstOrNull {
            triggerKey(it.trigger) == heard
        }
    }

    /** Baat me kisi bandhe combo ka zikr saaf ho to woh — "… কম্বোটা চালাও / ভুলে যাও" ke liye। */
    fun findMentioned(context: Context, rawText: String): Combo? {
        val key = TaughtCommands.triggerKey(rawText)
        if (key.isEmpty()) return null
        return readAll(context).firstOrNull {
            val trigger = TaughtCommands.triggerKey(it.trigger)
            trigger.length >= 2 && key.contains(trigger)
        }
    }

    // ---------------------------------------------------------------
    // Dhapon ki boli
    // ---------------------------------------------------------------

    /** Ek dhap ki boli-jaane-wali pehchan (suchi wale jawabon ke liye — bhavishya kaal)। */
    fun describeStep(action: String, lang: PlayIntent.SpokenLang): String {
        return when (action) {
            ACTION_LOCK -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ফোন লক হবে"
                PlayIntent.SpokenLang.HINDI -> "फ़ोन लॉक होगा"
                PlayIntent.SpokenLang.ENGLISH -> "the phone locks"
            }
            CMD_DATA_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা চালু হবে"
                PlayIntent.SpokenLang.HINDI -> "डेटा चालू होगा"
                PlayIntent.SpokenLang.ENGLISH -> "mobile data turns on"
            }
            CMD_DATA_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা বন্ধ হবে"
                PlayIntent.SpokenLang.HINDI -> "डेटा बंद होगा"
                PlayIntent.SpokenLang.ENGLISH -> "mobile data turns off"
            }
            else -> if (action.startsWith(CMD_PREFIX)) {
                val name = appNameOf(action.removePrefix(CMD_PREFIX))
                if (name.isEmpty()) {
                    TaughtCommands.describe(action, lang)
                } else {
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI -> "'$name' খুলবে"
                        PlayIntent.SpokenLang.HINDI -> "'$name' खुलेगा"
                        PlayIntent.SpokenLang.ENGLISH -> "'$name' opens"
                    }
                }
            } else {
                TaughtCommands.describe(action, lang)
            }
        }
    }

    /** Ek dhap HO gaya — hisaab wali chhoti line (bhoot kaal)। */
    fun stepDoneText(action: String, lang: PlayIntent.SpokenLang): String {
        return when (action) {
            TaughtCommands.ACTION_TORCH_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "লাইট জ্বললো"
                PlayIntent.SpokenLang.HINDI -> "लाइट जल गई"
                PlayIntent.SpokenLang.ENGLISH -> "Light on"
            }
            TaughtCommands.ACTION_TORCH_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "লাইট বন্ধ হলো"
                PlayIntent.SpokenLang.HINDI -> "लाइट बंद हो गई"
                PlayIntent.SpokenLang.ENGLISH -> "Light off"
            }
            TaughtCommands.ACTION_SCROLL_DOWN -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "নিচে স্ক্রল হলো"
                PlayIntent.SpokenLang.HINDI -> "नीचे स्क्रॉल हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Scrolled down"
            }
            TaughtCommands.ACTION_SCROLL_UP -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "উপরে স্ক্রল হলো"
                PlayIntent.SpokenLang.HINDI -> "ऊपर स्क्रॉल हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Scrolled up"
            }
            TaughtCommands.ACTION_SCREENSHOT -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "স্ক্রিনশট নেওয়া হলো"
                PlayIntent.SpokenLang.HINDI -> "स्क्रीनशॉट लिया गया"
                PlayIntent.SpokenLang.ENGLISH -> "Screenshot taken"
            }
            ACTION_LOCK -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ফোন লক হলো"
                PlayIntent.SpokenLang.HINDI -> "फ़ोन लॉक हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Phone locked"
            }
            CMD_DATA_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা চালু হলো"
                PlayIntent.SpokenLang.HINDI -> "डेटा चालू हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Data on"
            }
            CMD_DATA_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা বন্ধ হলো"
                PlayIntent.SpokenLang.HINDI -> "डेटा बंद हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Data off"
            }
            else -> if (action.startsWith(CMD_PREFIX)) {
                val name = appNameOf(action.removePrefix(CMD_PREFIX))
                if (name.isEmpty()) {
                    TaughtCommands.describe(action, lang)
                } else {
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI -> "'$name' খুললো"
                        PlayIntent.SpokenLang.HINDI -> "'$name' खुल गया"
                        PlayIntent.SpokenLang.ENGLISH -> "'$name' opened"
                    }
                }
            } else {
                TaughtCommands.describe(action, lang)
            }
        }
    }

    /** Ek dhap NAHI hua — hisaab wali chhoti line। */
    fun stepFailText(action: String, lang: PlayIntent.SpokenLang): String {
        return when (action) {
            TaughtCommands.ACTION_TORCH_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "লাইট জ্বললো না"
                PlayIntent.SpokenLang.HINDI -> "लाइट नहीं जली"
                PlayIntent.SpokenLang.ENGLISH -> "Light didn't turn on"
            }
            TaughtCommands.ACTION_TORCH_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "লাইট বন্ধ হলো না"
                PlayIntent.SpokenLang.HINDI -> "लाइट बंद नहीं हुई"
                PlayIntent.SpokenLang.ENGLISH -> "Light didn't turn off"
            }
            TaughtCommands.ACTION_SCROLL_DOWN,
            TaughtCommands.ACTION_SCROLL_UP -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "স্ক্রল হলো না"
                PlayIntent.SpokenLang.HINDI -> "स्क्रॉल नहीं हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Didn't scroll"
            }
            TaughtCommands.ACTION_SCREENSHOT -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "স্ক্রিনশট নেওয়া হলো না"
                PlayIntent.SpokenLang.HINDI -> "स्क्रीनशॉट नहीं लिया गया"
                PlayIntent.SpokenLang.ENGLISH -> "Screenshot not taken"
            }
            ACTION_LOCK -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ফোন লক হলো না"
                PlayIntent.SpokenLang.HINDI -> "फ़ोन लॉक नहीं हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Phone didn't lock"
            }
            CMD_DATA_ON -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা চালু হলো না"
                PlayIntent.SpokenLang.HINDI -> "डेटा चालू नहीं हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Data didn't turn on"
            }
            CMD_DATA_OFF -> when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "ডেটা বন্ধ হলো না"
                PlayIntent.SpokenLang.HINDI -> "डेटा बंद नहीं हुआ"
                PlayIntent.SpokenLang.ENGLISH -> "Data didn't turn off"
            }
            else -> if (action.startsWith(CMD_PREFIX)) {
                val name = appNameOf(action.removePrefix(CMD_PREFIX))
                if (name.isEmpty()) {
                    TaughtCommands.describe(action, lang)
                } else {
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI -> "'$name' খুললো না"
                        PlayIntent.SpokenLang.HINDI -> "'$name' नहीं खुला"
                        PlayIntent.SpokenLang.ENGLISH -> "'$name' didn't open"
                    }
                }
            } else {
                TaughtCommands.describe(action, lang)
            }
        }
    }

    /** "ইউটিউব খোলো" / "open youtube" se sirf app ka naam — hisaab wali line ke liye। */
    private fun appNameOf(cmdText: String): String {
        var name = TaughtCommands.triggerKey(cmdText)
        for (verb in OPEN_VERB_WORDS) {
            name = name.replace(verb, " ")
        }
        return name.replace(Regex("\\s+"), " ").trim()
    }

    private val OPEN_VERB_WORDS = listOf(
        "খোলো", "খোল", "খুলে", "খুল",
        "खोलो", "खोल",
        "open", "kholo", "khol"
    )

    // ---------------------------------------------------------------
    // Combo chalana (offline)
    // ---------------------------------------------------------------

    /**
     * Combo chalao — dhap ek ke baad ek, beech me
     * chhota thahrav। Koi dhap asafal ho to aage
     * badho (hisaab me likha jayega); lock wala
     * dhap safal hote hi ruk jao (phone lock —
     * aage ke dhap chal hi nahi sakte)। Kabhi
     * exception nahi — gadbad par khaali hisaab।
     */
    fun runCombo(
        context: Context,
        engine: CommandEngine,
        combo: Combo,
        lang: PlayIntent.SpokenLang,
        allowScreenshot: Boolean
    ): RunReport {
        val runs = mutableListOf<StepRun>()
        var stoppedByLock = false
        try {
            for ((index, action) in combo.steps.withIndex()) {
                val outcome = try {
                    executeStep(context, engine, action, combo.trigger, lang, allowScreenshot)
                } catch (t: Throwable) {
                    StepOutcome(false)
                }
                runs.add(
                    StepRun(
                        action = action,
                        ok = outcome.ok,
                        skippedScreenshot = outcome.skippedScreenshot
                    )
                )
                if (outcome.stoppedByLock) {
                    stoppedByLock = true
                    break
                }
                if (index < combo.steps.size - 1) {
                    try {
                        SystemClock.sleep(STEP_SETTLE_MS)
                    } catch (t: Throwable) {
                        // Thahrav bonus hai; daud isse kabhi nahi rukti.
                    }
                }
            }
            incrementUsed(context, combo.trigger)
        } catch (t: Throwable) {
            // Combo daud kabhi crash nahi — jo hua uska hisaab hi kaafi.
        }
        return RunReport(runs, stoppedByLock)
    }

    /**
     * Ek dhap chalao aur sachha natija do।
     * Safalta ki pehchan har raaste ki apni
     * pakki nishani se hoti hai — [CommandResult]
     * ka "handled" jhanda iske liye kaafi nahi
     * (woh pehchan milne par bhi true rehta hai,
     * kaam asafal hone par bhi)।
     */
    private fun executeStep(
        context: Context,
        engine: CommandEngine,
        action: String,
        trigger: String,
        lang: PlayIntent.SpokenLang,
        allowScreenshot: Boolean
    ): StepOutcome {
        return when (action) {
            TaughtCommands.ACTION_TORCH_ON ->
                StepOutcome(PhoneActions.setTorch(context, true))
            TaughtCommands.ACTION_TORCH_OFF ->
                StepOutcome(PhoneActions.setTorch(context, false))
            TaughtCommands.ACTION_SCROLL_DOWN -> {
                val result = engine.deterministicScrollCommand(SCROLL_DOWN_CANONICAL)
                StepOutcome(result != null && result.responseText in SCROLL_OK_TEXTS)
            }
            TaughtCommands.ACTION_SCROLL_UP -> {
                val result = engine.deterministicScrollCommand(SCROLL_UP_CANONICAL)
                StepOutcome(result != null && result.responseText in SCROLL_OK_TEXTS)
            }
            TaughtCommands.ACTION_SCREENSHOT -> {
                if (!allowScreenshot) {
                    // Saamne wali screen: capture ka
                    // intezaar main thread par atak jata
                    // hai (v40 wala niyam) — dhap
                    // chhoda jata hai, combo nahi rukta।
                    StepOutcome(false, skippedScreenshot = true)
                } else {
                    val result = engine.runScreenshotCommand(trigger)
                    StepOutcome(result.responseText in SHOT_OK_TEXTS)
                }
            }
            ACTION_LOCK -> {
                val ok = try {
                    JarvisAccessibilityService.lockScreen(context)
                } catch (t: Throwable) {
                    false
                }
                StepOutcome(ok, stoppedByLock = ok)
            }
            CMD_DATA_ON -> {
                val result = engine.handle("mobile data on")
                StepOutcome(result.responseText == DATA_ON_OK_TEXT)
            }
            CMD_DATA_OFF -> {
                val result = engine.handle("mobile data off")
                StepOutcome(result.responseText == DATA_OFF_OK_TEXT)
            }
            else -> {
                if (!action.startsWith(CMD_PREFIX)) {
                    return StepOutcome(false)
                }
                // App kholna (cmd:<text>) — khulne ki
                // pakki nishani: "खोल दिया है।" wala jawab।
                val result = engine.handle(action.removePrefix(CMD_PREFIX))
                StepOutcome(result.responseText.contains(APP_OPEN_OK_MARKER))
            }
        }
    }

    private const val PREFS_NAME = "jarvis_combos"
    private const val KEY_COMBOS = "combos"

    /** Bandhe combon ki adhiktam ginati — aage sabse purana hat-ta hai। */
    private const val MAX_COMBOS = 10

    /** Ek combo me zyada se zyada itne dhap। */
    private const val MAX_STEPS = 5

    /** Ek combo ke bol ki adhiktam lambai (aksharon me)। */
    private const val MAX_TRIGGER_CHARS = 60

    /** Ek combo-text ki adhiktam lambai (aksharon me)। */
    private const val MAX_TEXT_CHARS = 300

    /** Dhapon ke beech ka thahrav (ms) — agla kaam pehle wale ke asar ke baad chale। */
    private const val STEP_SETTLE_MS = 400L

    /** "cmd:" — [TaughtCommands] wala hi prefix (wahan private hai, yahan dobara)। */
    private const val CMD_PREFIX = "cmd:"

    /** Disha-pakki data wale kaam — sikhate samay bina disha ka toggle nahi bandhta। */
    const val CMD_DATA_ON = "cmd:mobile data on"
    const val CMD_DATA_OFF = "cmd:mobile data off"

    /** Scroll wale canonical roop — engine ki apni pehchan inpar pakki hai। */
    private const val SCROLL_DOWN_CANONICAL = "স্ক্রল ডাউন"
    private const val SCROLL_UP_CANONICAL = "স্ক্রল আপ"

    /** Safal scroll ka jawab — teeno bhasha me (engine ke pakke shabd)। */
    private val SCROLL_OK_TEXTS = setOf(
        "স্ক্রল করে দিয়েছি।", "स्क्रॉल कर दिया।", "Scrolled."
    )

    /** Safal screenshot ka jawab — teeno bhasha me (engine ke pakke shabd)। */
    private val SHOT_OK_TEXTS = setOf(
        "স্ক্রিনশট নিয়ে গ্যালারিতে রেখে দিয়েছি।",
        "स्क्रीनशॉट लेकर गैलरी में रख दिया है।",
        "Screenshot taken and saved to your gallery."
    )

    /**
     * Data wale kaam ki safalta — [CommandEngine]
     * ke mobileDataCommand me ye dono jawab
     * Hindi me bandhe hain, bhasha se pare।
     */
    private const val DATA_ON_OK_TEXT = "मोबाइल डेटा ऑन कर दिया।"
    private const val DATA_OFF_OK_TEXT = "मोबाइल डेटा बंद कर दिया।"

    /** App khulne ki pakki nishani — [CommandEngine] ke openTarget wala jawab। */
    private const val APP_OPEN_OK_MARKER = "खोल दिया है।"

    /** Milan ke samay hatne wale bhare shabd ([TaughtCommands] wali hi suchi)। */
    private val TRIGGER_FILLER_TOKENS = setOf(
        "একটু", "তো", "শুধু", "please", "zara", "just", "একটুখানি"
    )
}

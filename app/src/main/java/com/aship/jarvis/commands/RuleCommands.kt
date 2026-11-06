package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.BrainRules
import com.aship.jarvis.ai.TaughtCommands
import java.util.Locale

/**
 * v52: NIYAM WALE PAKKE HUKM — do mezbaan (saamne
 * wali screen + background wake service) yahi ek
 * object bulate hain, bilkul [TeachCommands] /
 * [LearnCommands] ki tarah।
 *
 * Teen kaam:
 *  1. NIYAM sikhana —
 *     "নিয়ম মনে রাখো: যখন <X> বলি তখন <Y> করবে"
 *     ya "মনে রাখো, যখন <X> বলি তখন <Y> করবে" —
 *     <Y> wala kaam [TaughtCommands.resolveAction]
 *     se bandhe auzaar me badle tabhi niyam
 *     bandhta hai; na badle to imaandaar manaahi
 *     aur KUCH nahi bandhta। Shart ke bina wala
 *     niyam ("নিয়ম মনে রাখো: সবসময় বাংলায়
 *     উত্তর দেবে") pasand wala niyam bankar
 *     bandhta hai — woh sirf mastishk ki hidayat
 *     me jata hai, offline usse koi kaam nahi
 *     chalta।
 *  2. Niyamon ki suchi ("কী কী নিয়ম মনে আছে")
 *     aur ek niyam bhulna ("… নিয়মটা ভুলে যাও")
 *     ya sab bhulna ("সব নিয়ম ভুলে যাও")।
 *  3. Bandha niyam chalana — [tryApplyRule]:
 *     baat kisi bandhe niyam ki shart se mile to
 *     uska kaam chup-chaap chalega, phir asal hukm
 *     apne aam raaste se chalega (mezbaan isse
 *     pakke raaste se pehle bulata hai)।
 *
 * TAKRAAV se bachav ([TeachCommands] /
 * [LearnCommands] ke saath): ye object sirf WAHI
 * roop pakadta hai jo saaf iske hain — "নিয়ম"
 * wala starter, ya "যখন…তখন…" wali baat।
 * "মনে রেখো: <chaal>" (bina jab-tak ke) v49
 * wali chaal hi rehti hai aur [LearnCommands]
 * ke paas hi jati hai; "মনে রেখো, <tathya>"
 * (bina jab-tak ke) v31 wali tathya-yaad hai aur
 * mastishk ke remember_fact par hi jati hai —
 * isiliye mezbaan ise [TeachCommands] ke baad
 * aur [LearnCommands] se PEHLE bulate hain, par
 * ye bina apne roop ke null dekar raasta chhod
 * deta hai।
 */
object RuleCommands {

    /**
     * Niyam sikhane/suchi/bhulne wali baat ho to
     * bola jaane wala jawab ([lang] me), warna
     * null। Kabhi exception nahi — gadbad par null।
     */
    fun tryHandleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        return try {
            handleAdmin(context, rawText, lang)
        } catch (t: Throwable) {
            null
        }
    }

    private fun handleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)

        // 1. Sab niyam bhulna — "সব নিয়ম ভুলে যাও"।
        if (isClearAllQuery(lower)) {
            return if (BrainRules.clearAll(context)) {
                clearedAllReply(lang)
            } else {
                null
            }
        }

        // 2. Niyam sikhana — "নিয়ম মনে রাখো: …" ya
        // "মনে রাখো, যখন … তখন …"।
        parseRuleTeach(text)?.let { parsed ->
            when (parsed) {
                is TeachParse.Executable -> {
                    val action =
                        TaughtCommands.resolveAction(context, parsed.thenText)
                    return if (action == null) {
                        teachUnresolvedReply(lang, parsed.thenText)
                    } else {
                        BrainRules.save(
                            context, parsed.whenText, action,
                            parsed.fullText
                        )
                        teachSavedReply(lang, parsed.whenText, action)
                    }
                }
                is TeachParse.Preference -> {
                    BrainRules.save(
                        context, "", null, parsed.fullText
                    )
                    preferenceSavedReply(lang, parsed.fullText)
                }
            }
        }

        // 3. Niyamon ki suchi।
        if (isRuleListQuery(lower)) {
            return listReply(context, lang)
        }

        // 4. Ek niyam bhulna — bhulne wala shabd +
        // bandhe niyam ka saaf zikr, dono hon tabhi
        // (warna [LearnCommands] ka routine-wala
        // bhulna chalega)।
        if (hasForgetWord(lower)) {
            val mentioned = BrainRules.findMentioned(context, text)
            if (mentioned != null) {
                BrainRules.delete(context, mentioned)
                return ruleForgotReply(lang, mentioned.whenText)
            }
        }
        return null
    }

    /**
     * Baat par koi bandha niyam lage to uska kaam
     * chalao; laga ho to true, warna false। Kabhi
     * exception nahi — gadbad par false (asal hukm
     * apne aam raaste se chalega)।
     */
    fun tryApplyRule(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        allowScreenshot: Boolean
    ): Boolean {
        return try {
            BrainRules.applyMatchingRule(
                context, engine, rawText, lang, allowScreenshot
            ) != null
        } catch (t: Throwable) {
            false
        }
    }

    /** Sikhane wale niyam ke do roop — shart wala (kaam chalega) aur pasand wala (sirf hidayat)। */
    private sealed class TeachParse {
        class Executable(
            val whenText: String,
            val thenText: String,
            val fullText: String
        ) : TeachParse()

        class Preference(val fullText: String) : TeachParse()
    }

    // ---------------------------------------------------------------
    // Niyam sikhane ki pehchan
    // ---------------------------------------------------------------

    /**
     * Niyam sikhane wala hukm ho to [TeachParse],
     * warna null। Do roop:
     *  (a) "নিয়ম …" starter — uske baad jab-tak
     *      ho to shart wala niyam, warna pasand
     *      wala niyam।
     *  (b) "মনে রাখো, যখন … তখন …" — sirf jab-tak
     *      ho tabhi niyam; bina jab-tak ke ye
     *      tathya hai, yahan nahi bandhta।
     */
    private fun parseRuleTeach(rawText: String): TeachParse? {
        val text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)
        // (a) Niyam wala starter।
        val starter = RULE_STARTERS.firstOrNull {
            lower.startsWith(it)
        }
        if (starter != null) {
            val body = text.substring(starter.length)
                .trimStart(' ', ':', '—', '-', '।', ',')
                .trim()
            if (body.isEmpty()) return null
            parseWhenThen(body)?.let { (whenText, thenText) ->
                return TeachParse.Executable(whenText, thenText, body)
            }
            if (body.length <= MAX_RULE_TEXT_CHARS &&
                !isQuestion(body)
            ) {
                return TeachParse.Preference(body)
            }
            return null
        }
        // (b) "মনে রাখো, যখন … তখন …"।
        val rememberStarter = REMEMBER_STARTERS.firstOrNull {
            lower.startsWith(it)
        } ?: return null
        val body = text.substring(rememberStarter.length)
            .trimStart(' ', ',', ':', '—', '-')
            .trim()
        if (body.isEmpty()) return null
        parseWhenThen(body)?.let { (whenText, thenText) ->
            return TeachParse.Executable(whenText, thenText, body)
        }
        return null
    }

    /** Niyam sikhane ki shuruwaat — inke bina baat niyam nahi। */
    private val RULE_STARTERS = listOf(
        "নিয়ম মনে রাখো", "নিয়ম মনে রেখো",
        "এই নিয়মটা মনে রাখো", "এই নিয়মটা মনে রেখো",
        "নিয়মটা মনে রাখো", "নিয়মটা মনে রেখো",
        "নিয়ম শিখে নাও", "নিয়ম শিখে রাখো",
        "नियम याद रखो", "नियम याद रखना",
        "remember this rule", "remember the rule"
    )

    /** Yaad rakhne ki shuruwaat — jab-tak ho tabhi niyam। */
    private val REMEMBER_STARTERS = listOf(
        "মনে রাখো", "মনে রেখো",
        "याद रखो",
        "remember"
    )

    /**
     * "যখন … তখন …" (ya "जब…तो…"/"when…then…")
     * ho to (shart, kaam); warna null। "তখন" wala
     * pakka sanket pehle dhoondha jata hai — halka
     * "তো" uske baad hi, taaki beech me aaya
     * bhara "তো" shart ko kaat na de। Shart sirf
     * sarvanaam ho ("তুমি") to woh shart nahi।
     */
    private fun parseWhenThen(body: String): Pair<String, String>? {
        val lower = body.lowercase(Locale.ROOT)
        val whenMarker = WHEN_MARKERS.firstOrNull {
            lower.contains(it)
        } ?: return null
        val whenAt = lower.indexOf(whenMarker)
        val afterWhen = whenAt + whenMarker.length
        val thenMarker = THEN_MARKERS_STRONG.firstOrNull {
            lower.indexOf(it, afterWhen) >= 0
        } ?: THEN_MARKERS_WEAK.firstOrNull {
            lower.indexOf(it, afterWhen) >= 0
        } ?: return null
        val thenAt = lower.indexOf(thenMarker, afterWhen)
        if (thenAt < afterWhen) return null
        val whenText = body.substring(afterWhen, thenAt)
            .trim()
            .trim('।', ',', ' ', ':', '—', '-')
            .trim()
        var thenText = body.substring(thenAt + thenMarker.length)
            .trimStart(' ', ':', '—', '-', '।', ',')
            .trim()
        // "তখন তুমি ডেটা চালু করো" — kaam se pehle
        // ka pukar-shabd / bacha hua "তখন" hatao।
        thenText = thenText
            .replace(Regex("^(তখন|तो|then)\\s+"), "")
            .replace(Regex("^(তুমি|তুই|আপনি|তু|you)\\s+"), "")
            .trim()
        thenText = thenText.trimEnd('।', '.', '!', '?', ' ').trim()
        if (whenText.isEmpty() || whenText.length > MAX_WHEN_CHARS) {
            return null
        }
        if (thenText.isEmpty() || thenText.length > MAX_THEN_CHARS) {
            return null
        }
        if (whenText.split(Regex("\\s+")).size > 8) return null
        if (BrainRules.keywordsOf(whenText).isEmpty()) return null
        return whenText to thenText
    }

    /** Shart wale shabd — inse pehle shart, baad me kaam। */
    private val WHEN_MARKERS = listOf("যখন", "जब", "when")

    /** "তখন" wale pakke sanket — ye pehle dhoondhe jate hain। */
    private val THEN_MARKERS_STRONG = listOf("তখন", "तो", "then")

    /** Halka sanket — pakka na mile tabhi ("তো" beech me bhi aata hai)। */
    private val THEN_MARKERS_WEAK = listOf("তো")

    /** Sawal ho to woh niyam nahi — sawal kabhi bandhta nahi। */
    private fun isQuestion(body: String): Boolean {
        val lower = body.lowercase(Locale.ROOT)
        return lower.contains("?") || lower.contains("কী") ||
            lower.contains("क्या") || lower.contains("what")
    }

    /** Bandhe niyam poochne ke roop — [LearnCommands] ki "কী কী শিখেছ" (chaal) se alag। */
    private fun isRuleListQuery(lower: String): Boolean =
        RULE_LIST_MARKERS.any { lower.contains(it) }

    private val RULE_LIST_MARKERS = listOf(
        "কী কী নিয়ম মনে আছে", "কি কি নিয়ম মনে আছে",
        "কী নিয়ম মনে আছে", "কি নিয়ম মনে আছে",
        "নিয়মগুলো বলো", "নিয়ম গুলো বলো",
        "কী নিয়ম আছে", "কি নিয়ম আছে",
        "মনে রাখা নিয়ম", "নিয়ম কী কী",
        "क्या नियम याद हैं", "नियम बताओ",
        "what rules", "list rules", "my rules"
    )

    /** Bhulne wala shabd hai kya ([LearnCommands] ki hi suchi-shaili)। */
    private fun hasForgetWord(lower: String): Boolean =
        FORGET_MARKERS.any { lower.contains(it) }

    private val FORGET_MARKERS = listOf(
        "ভুলে যাও", "ভূলে যাও", "ভুলে যা",
        "মুছে দাও", "মুছে ফেলো", "মুছে ফেল",
        "भूल जाओ", "भूल जा", "मिटा दो", "मिटाओ",
        "forget", "delete"
    )

    /** Sab niyam bhulne ke roop — ek niyam wala bhulna isse alag hai, neeche hai। */
    private fun isClearAllQuery(lower: String): Boolean =
        CLEAR_ALL_MARKERS.any { lower.contains(it) }

    private val CLEAR_ALL_MARKERS = listOf(
        "সব নিয়ম ভুলে যাও", "সব নিয়ম মুছে দাও",
        "সব নিয়ম মুছে ফেলো", "সব নিয়ম ভুলে যা",
        "सारे नियम भूल जाओ", "सब नियम भूल जाओ",
        "forget all rules", "clear all rules"
    )

    private const val MAX_WHEN_CHARS = 80
    private const val MAX_THEN_CHARS = 80
    private const val MAX_RULE_TEXT_CHARS = 200

    // ---------------------------------------------------------------
    // Bole jaane wale jawab
    // ---------------------------------------------------------------

    private fun teachSavedReply(
        lang: PlayIntent.SpokenLang,
        whenText: String,
        action: String
    ): String {
        val desc = TaughtCommands.describe(action, lang)
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "মনে রাখলাম — যখন '$whenText' বলবে তখন $desc।"
            PlayIntent.SpokenLang.HINDI ->
                "याद रख लिया — जब '$whenText' कहेंगे तो $desc।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Remembered — when you say '$whenText', $desc."
        }
    }

    private fun preferenceSavedReply(
        lang: PlayIntent.SpokenLang,
        text: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "মনে রাখলাম — '$text'। এটা মাথায় রেখে কাজ করব।"
        PlayIntent.SpokenLang.HINDI ->
            "याद रख लिया — '$text'। इसे ध्यान में रखकर काम करूँगी।"
        PlayIntent.SpokenLang.ENGLISH ->
            "Remembered — '$text'. I'll keep that in mind."
    }

    /** Kaam na pehchana jaye to imaandaar manaahi — kuch bandha hi nahi। */
    private fun teachUnresolvedReply(
        lang: PlayIntent.SpokenLang,
        thenText: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "এই কাজটা ('$thenText') আমি করতে পারি না, " +
                "তাই নিয়মটা শেখালাম না।"
        PlayIntent.SpokenLang.HINDI ->
            "यह काम ('$thenText') मैं नहीं कर सकती, " +
                "इसलिए नियम नहीं सिखाया।"
        PlayIntent.SpokenLang.ENGLISH ->
            "I can't do '$thenText', so I didn't learn that rule."
    }

    private fun listReply(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): String {
        val rules = BrainRules.readAll(context)
        if (rules.isEmpty()) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "এখনো কোনো নিয়ম মনে রাখিনি।"
                PlayIntent.SpokenLang.HINDI ->
                    "अभी कोई नियम याद नहीं रखा है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I haven't remembered any rules yet."
            }
        }
        val lines = rules.takeLast(10).mapIndexed { index, rule ->
            val action = rule.thenAction
            if (action != null) {
                "${index + 1}. যখন '${rule.whenText}' — " +
                    TaughtCommands.describe(action, lang)
            } else {
                "${index + 1}. ${rule.text}"
            }
        }.joinToString("; ")
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "মনে রাখা নিয়মগুলো: $lines"
            PlayIntent.SpokenLang.HINDI ->
                "याद रखे हुए नियम: $lines"
            PlayIntent.SpokenLang.ENGLISH ->
                "Rules I've remembered: $lines"
        }
    }

    private fun ruleForgotReply(
        lang: PlayIntent.SpokenLang,
        whenText: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "'$whenText' নিয়মটা ভুলে গেছি।"
        PlayIntent.SpokenLang.HINDI ->
            "'$whenText' वाला नियम भूल गई।"
        PlayIntent.SpokenLang.ENGLISH ->
            "Forgotten — the rule for '$whenText'."
    }

    private fun clearedAllReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ঠিক আছে — সব নিয়ম ভুলে গেছি।"
            PlayIntent.SpokenLang.HINDI ->
                "ठीक है — सारे नियम भूल गई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Okay — I've forgotten all the rules."
        }
}

package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.BrainRules
import com.aship.jarvis.ai.ComboRules
import com.aship.jarvis.ai.TaughtCommands
import java.util.Locale

/**
 * v69: COMBO WALE PAKKE HUKM — do mezbaan (saamne
 * wali screen + background wake service) yahi ek
 * object bulate hain, bilkul [RuleCommands] /
 * [TeachCommands] ki tarah।
 *
 * Teen kaam:
 *  1. COMBO sikhana —
 *     "কম্বো মনে রাখো: যখন <X> বলি তখন <Y1>, <Y2>, …"
 *     — <X> combo ka bol, <Y> kaam comma se alag
 *     (2-5 dhap)। Har dhap [TaughtCommands]
 *     .resolveAction se bandhe auzaar me badle
 *     tabhi combo bandhta hai (+ LOCK dhap);
 *     koi dhap na badle to imaandaar manaahi
 *     aur KUCH nahi bandhta। Ek dhap wala "combo"
 *     combo nahi — use niyam/shabd-kaam ki taraf
 *     ishara karke mana kiya jata hai।
 *  2. Combon ki suchi ("কী কী কম্বো মনে আছে")
 *     aur ek combo bhulna ("… কম্বোটা ভুলে যাও")
 *     ya sab bhulna ("সব কম্বো ভুলে যাও")।
 *  3. Bandha combo chalana — [tryRunCombo]:
 *     baat kisi bandhe combo ka bol ho ("গুড
 *     নাইট") ya "… কম্বোটা চালাও" ho to dhap ek
 *     ke baad ek chalte hain aur ek chhota hisaab
 *     bola jata hai। Combo khud ASAL hukm hai —
 *     chala to mezbaan wapas laut jata hai (aam
 *     raasta aage nahi badhta)।
 *
 * TAKRAAV se bachav ([RuleCommands] /
 * [TeachCommands] / [LearnCommands] ke saath):
 * ye object sirf WAHI roop pakadta hai jo saaf
 * iske hain — "কম্বো" wala starter, ya
 * "কম্বো" ka zikr, ya bandhe combo ka bol।
 * Isliye mezbaan ise [TeachCommands] ke baad
 * aur [RuleCommands] se PEHLE bulate hain:
 * "মনে রাখো, যখন … তখন …" wala roop bhi
 * combo tabhi banta hai jab "তখন" ke baad
 * 2 ya zyada SAHI dhap hon — ek dhap ho to
 * woh niyam hi rehta hai aur [RuleCommands]
 * ke paas jata hai (yahan se null dekar raasta
 * chhod diya jata hai)। "কম্বো" shabd ke bina
 * suchi/bhulna/chalana kabhi nahi pakda jata —
 * niyam aur chaal apne raaste surakshit hain।
 */
object ComboCommands {

    /**
     * Combo sikhane/suchi/bhulne wali baat ho to
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

        // 1. Sab combo bhulna — "সব কম্বো ভুলে যাও"।
        // "কম্বো" shabd ke bina ye roop kabhi nahi।
        if (isClearAllComboQuery(lower)) {
            return if (ComboRules.clearAll(context)) {
                clearedAllReply(lang)
            } else {
                noCombosReply(lang)
            }
        }

        // 2. Combo sikhana — "কম্বো মনে রাখো: …" ya
        // (2+ dhap ho tabhi) "মনে রাখো, যখন … তখন …"।
        parseComboTeach(context, text)?.let { parsed ->
            return when (parsed) {
                is TeachParse.Saved -> {
                    ComboRules.save(
                        context, parsed.trigger, parsed.steps,
                        parsed.fullText
                    )
                    teachSavedReply(
                        lang, parsed.trigger, parsed.steps
                    )
                }
                is TeachParse.Refused -> parsed.reason
            }
        }

        // 3. Combon ki suchi — "কম্বো" shabd ke bina nahi।
        if (isComboListQuery(lower)) {
            return listReply(context, lang)
        }

        // 4. Ek combo bhulna — bhulne wala shabd +
        // "কম্বো" ka zikr + bandhe combo ka saaf zikr,
        // teeno hon tabhi (warna niyam/chaal wala
        // bhulna chalega)।
        if (hasForgetWord(lower) && mentionsCombo(lower)) {
            val mentioned = ComboRules.findMentioned(context, text)
            if (mentioned != null) {
                ComboRules.delete(context, mentioned)
                return comboForgotReply(lang, mentioned.trigger)
            }
        }
        return null
    }

    /**
     * Baat kisi bandhe combo ka bol ho ("গুড
     * নাইট") ya "… কম্বোটা চালাও" ho to combo
     * chalao aur hisaab wala jawab do; na ho to
     * null। Kabhi exception nahi — gadbad par null
     * (asal hukm apne aam raaste se chalega)।
     */
    fun tryRunCombo(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        allowScreenshot: Boolean
    ): String? {
        return try {
            val combo = ComboRules.matchTrigger(context, rawText)
                ?: runByNameMention(context, rawText)
                ?: return null
            val report = ComboRules.runCombo(
                context, engine, combo, lang, allowScreenshot
            )
            runSummaryReply(combo, report, lang)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * "… কম্বোটা চালাও" — chalane wala shabd +
     * "কম্বো" ka zikr + bandhe combo ka saaf zikr,
     * teeno hon tabhi। "কম্বো" shabd ke bina ye
     * roop nahi pakda jata — "ডেটা চালু করো"
     * jaisa aam hukm kabhi combo nahi banega।
     */
    private fun runByNameMention(
        context: Context,
        rawText: String
    ): ComboRules.Combo? {
        val lower = rawText.lowercase(Locale.ROOT)
        if (!mentionsCombo(lower)) return null
        if (!RUN_VERB_MARKERS.any { lower.contains(it) }) return null
        return ComboRules.findMentioned(context, rawText)
    }

    /** Sikhane wale combo ke do natije — bandha gaya, ya imaandaar manaahi। */
    private sealed class TeachParse {
        class Saved(
            val trigger: String,
            val steps: List<String>,
            val fullText: String
        ) : TeachParse()

        class Refused(val reason: String) : TeachParse()
    }

    // ---------------------------------------------------------------
    // Combo sikhane ki pehchan
    // ---------------------------------------------------------------

    /**
     * Combo sikhane wala hukm ho to [TeachParse],
     * warna null। Do roop:
     *  (a) "কম্বো …" starter — uske baad jab-tak
     *      ho to combo; ek dhap ho ya dhap na
     *      badle to imaandaar manaahi (kuch nahi
     *      bandhta)।
     *  (b) "মনে রাখো, যখন … তখন …" — sirf tabhi
     *      combo jab "তখন" ke baad 2 ya zyada
     *      SAHI dhap hon; ek dhap ho to niyam hi
     *      hai (null — [RuleCommands] ke paas
     *      jayega), dhap na badle to bhi null
     *      (wahi manaahi karega)।
     */
    private fun parseComboTeach(
        context: Context,
        rawText: String
    ): TeachParse? {
        val text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)
        // (a) Combo wala starter।
        val starter = COMBO_STARTERS.firstOrNull {
            lower.startsWith(it)
        }
        if (starter != null) {
            val body = text.substring(starter.length)
                .trimStart(' ', ':', '—', '-', '।', ',')
                .trim()
            if (body.isEmpty()) return null
            val parsed = parseWhenThen(body) ?: return TeachParse.Refused(
                comboNoWhenThenReply(currentLangOf(text))
            )
            val trigger = cleanTrigger(parsed.first) ?: return TeachParse.Refused(
                comboNoTriggerReply(currentLangOf(text))
            )
            return buildSteps(context, parsed.second, trigger, body)
        }
        // (b) "মনে রাখো, যখন … তখন …" — sirf 2+
        // sahi dhap ho tabhi combo।
        val rememberStarter = REMEMBER_STARTERS.firstOrNull {
            lower.startsWith(it)
        } ?: return null
        val body = text.substring(rememberStarter.length)
            .trimStart(' ', ',', ':', '—', '-')
            .trim()
        if (body.isEmpty()) return null
        val parsed = parseWhenThen(body) ?: return null
        val parts = splitSteps(parsed.second)
        if (parts.size < 2) return null
        val resolved = resolveAllSteps(context, parts) ?: return null
        val trigger = cleanTrigger(parsed.first) ?: return null
        return TeachParse.Saved(trigger, resolved, body)
    }

    /**
     * "তখন" ke baad wale hisse se dhap banao:
     * 2-5 dhap, har dhap bandhe auzaar me badle
     * tabhi combo; ek dhap ho to niyam ki taraf
     * ishara, koi dhap na badle to usi dhap ka
     * naam lekar manaahi। Kuch bhi bandhe bina
     * kabhi nahi lauta jata।
     */
    private fun buildSteps(
        context: Context,
        thenText: String,
        trigger: String,
        fullText: String
    ): TeachParse {
        val lang = currentLangOf(fullText)
        val parts = splitSteps(thenText)
        if (parts.isEmpty()) {
            return TeachParse.Refused(comboNoStepsReply(lang))
        }
        if (parts.size == 1) {
            return TeachParse.Refused(comboOneStepReply(lang, parts[0]))
        }
        if (parts.size > MAX_STEPS) {
            return TeachParse.Refused(comboTooManyReply(lang))
        }
        val resolved = resolveAllSteps(context, parts)
            ?: return TeachParse.Refused(
                comboBadStepReply(lang, firstBadStep(context, parts))
            )
        return TeachParse.Saved(trigger, resolved, fullText)
    }

    /** Saare dhap ek saath badlo — koi ek na badle to null (aadha combo kabhi nahi)। */
    private fun resolveAllSteps(
        context: Context,
        parts: List<String>
    ): List<String>? {
        val out = mutableListOf<String>()
        for (part in parts) {
            when (val resolved = resolveStep(context, part)) {
                is StepResolve.Ok -> out.add(resolved.action)
                is StepResolve.Bad -> return null
            }
        }
        return out
    }

    /** Pehla na-badalne wala dhap (manaahi wale jawab ke liye)। */
    private fun firstBadStep(context: Context, parts: List<String>): String {
        for (part in parts) {
            if (resolveStep(context, part) is StepResolve.Bad) return part
        }
        return parts.firstOrNull() ?: ""
    }

    /** Ek dhap ka natija — bandha kaam, ya manaahi ki wajah। */
    private sealed class StepResolve {
        class Ok(val action: String) : StepResolve()
        class Bad(val reason: String) : StepResolve()
    }

    /**
     * Ek dhap ka text bandhe kaam-id me badlo;
     * na badle to [StepResolve.Bad]। Pehle
     * [TaughtCommands.resolveAction] (torch /
     * scroll / screenshot / data / app kholna),
     * phir LOCK dhap। Bina disha ka data toggle
     * aur call/message wale dhap yahin mana kiye
     * jate hain — combo me woh surakshit nahi।
     */
    private fun resolveStep(context: Context, part: String): StepResolve {
        val cleaned = cleanStepText(part)
        if (cleaned.isEmpty()) {
            return StepResolve.Bad("খালি ধাপ")
        }
        TaughtCommands.resolveAction(context, cleaned)?.let { action ->
            if (action == CMD_DATA_TOGGLE) {
                return StepResolve.Bad("ডেটা চালু না বন্ধ — দিশা বলে দাও")
            }
            return StepResolve.Ok(action)
        }
        // Bare "ডেটা/ডাটা" (bina "মোবাইল" ke) —
        // [TaughtCommands.resolveAction] isse nahi
        // pehchanta ("মোবাইল ডেটা" hi uski suchi me
        // hai), par combo ka sabse aam dhap yahi hai
        // ("ডেটা বন্ধ করো") — isliye yahan khud
        // disha ke saath bandho; disha na ho to
        // manaahi (toggle combo me nahi)।
        resolveBareData(cleaned)?.let { return it }
        resolveLock(cleaned)?.let { return StepResolve.Ok(it) }
        val lower = cleaned.lowercase(Locale.ROOT)
        if (CALL_MARKERS.any { lower.contains(it) }) {
            return StepResolve.Bad(
                "কল/মেসেজ combo-তে হয় না — ফোনে কথা বলার কাজ আলাদা বলো"
            )
        }
        return StepResolve.Bad(cleaned)
    }

    /**
     * Bare data wala dhap — "ডেটা বন্ধ করো" /
     * "ডাটা চালু করো" / "data off"। Disha saaf
     * ho to bandha data kaam, disha na ho to
     * manaahi, data ka zikr hi na ho to null
     * (aage wale dhap ki pehchan chalegi)।
     */
    private fun resolveBareData(cleaned: String): StepResolve? {
        val lower = cleaned.lowercase(Locale.ROOT)
        val dataMention = lower.contains("ডেটা") || lower.contains("ডাটা") ||
            lower.contains("डेटा") || lower.contains("data")
        if (!dataMention) return null
        return when {
            DATA_OFF_MARKERS.any { lower.contains(it) } ->
                StepResolve.Ok(ComboRules.CMD_DATA_OFF)
            DATA_ON_MARKERS.any { lower.contains(it) } ->
                StepResolve.Ok(ComboRules.CMD_DATA_ON)
            else -> StepResolve.Bad("ডেটা চালু না বন্ধ — দিশা বলে দাও")
        }
    }

    /** Data wale OFF sanket ([TaughtCommands] wali hi suchi)। */
    private val DATA_OFF_MARKERS = listOf(
        "বন্ধ", "বন্দ", "অফ", "off", "बंद", "बन्द", "ऑफ", "নিভিয়ে", "নেভাও"
    )

    /** Data wale ON sanket ([TaughtCommands] wali hi suchi)। */
    private val DATA_ON_MARKERS = listOf(
        "অন", "on", "চালু", "জ্বালাও", "জ্বালা", "জ্বলবে",
        "चालू", "जलाओ", "जला", "ऑन"
    )

    /** LOCK dhap — [CommandEngine] ke lockCommand wali hi pehchan (poora shabd, unlock nahi)। */
    private fun resolveLock(cleaned: String): String? {
        val tokens = TaughtCommands.triggerKey(cleaned)
            .split(" ").filter { it.isNotEmpty() }
        val hasLockWord = tokens.any {
            it == "lock" || it == "লক" || it == "लॉक"
        }
        if (!hasLockWord) return null
        if (tokens.any { it == "unlock" || it == "আনলক" || it == "अनलॉक" }) {
            return null
        }
        val hasVerb = tokens.any { it in LOCK_VERBS } ||
            tokens.any { it.contains("কর") || it.contains("कर") } ||
            tokens.any { it == "ফোন" || it == "phone" }
        if (!hasVerb && tokens.size > 2) return null
        return ComboRules.ACTION_LOCK
    }

    private val LOCK_VERBS = setOf(
        "করো", "কর", "করবে", "করে", "দাও", "দেবে", "দিও",
        "करो", "कर", "दो"
    )

    /** Call/message wale sanket — combo me mana (dialer/composer aadha kaam hai)। */
    private val CALL_MARKERS = listOf(
        "কল", "call", "ফোন কর", "मैसेज", "মেসেজ", "message",
        "sms", "হোয়াটসঅ্যাপ", "whatsapp", "টেলিগ্রাম", "telegram"
    )

    /** Dhapon ko alag karo — comma / dari / "তারপর" se। */
    private fun splitSteps(thenText: String): List<String> {
        val out = mutableListOf<String>()
        val commaParts = thenText.split(Regex("[,।]"))
        for (commaPart in commaParts) {
            val thenParts = commaPart.split(
                Regex("\\s+তারপর(ে)?\\s+")
            )
            for (part in thenParts) {
                val clean = cleanStepText(part)
                if (clean.isNotEmpty()) out.add(clean)
            }
        }
        return out
    }

    /** Ek dhap saaf karo — shuru ka "তখন/তুমি" aur aakhir ka viram hatao। */
    private fun cleanStepText(part: String): String {
        var text = part.trim()
            .trim('।', '.', '!', '?', ' ', ':', '—', '-', ',')
            .trim()
        text = text
            .replace(Regex("^(তখন|तो|then)\\s+"), "")
            .replace(Regex("^(তুমি|তুই|আপনি|তু|you)\\s+"), "")
            .trim()
        return text.trimEnd('।', '.', '!', '?', ' ').trim()
    }

    /**
     * Shart wale hisse se combo ka bol — "গুড
     * নাইট বলি" → "গুড নাইট" ("বলি" jaisi
     * framing hatakar)। Bol bache hi na to null।
     */
    private fun cleanTrigger(whenText: String): String? {
        val words = TaughtCommands.triggerKey(whenText)
            .split(" ")
            .filter { it.isNotEmpty() && it !in TRIGGER_STRIP_WORDS }
        if (words.isEmpty()) return null
        val trigger = words.joinToString(" ").trim()
        if (trigger.isEmpty() || trigger.length > MAX_TRIGGER_CHARS) {
            return null
        }
        if (BrainRules.keywordsOf(whenText).isEmpty()) return null
        return trigger
    }

    /** Bol se hatne wale framing wale shabd — "বলি/বললে" jaisi kriya। */
    private val TRIGGER_STRIP_WORDS = setOf(
        "বলি", "বললে", "বলো", "বল", "বলবে", "বলতে", "বললাম",
        "বলছি", "করলে", "করি",
        "कहो", "कहूँ", "कहना",
        "say", "tell", "says"
    )

    /** Combo sikhane ki shuruwaat — inke bina baat combo nahi। */
    private val COMBO_STARTERS = listOf(
        "কম্বো মনে রাখো", "কম্বো মনে রেখো",
        "কম্বো শিখে নাও", "কম্বো শিখে রাখো",
        "কম্বো মনে রাখবে",
        "कॉम्बो याद रखो", "कॉम्बो याद रखना", "कॉम्बो सीख लो",
        "remember this combo", "learn this combo",
        "remember combo", "learn combo"
    )

    /** Yaad rakhne ki shuruwaat — jab-tak + 2+ dhap ho tabhi combo। */
    private val REMEMBER_STARTERS = listOf(
        "মনে রাখো", "মনে রেখো",
        "याद रखो",
        "remember"
    )

    /**
     * "যখন … তখন …" (ya "जब…तो…"/"when…then…")
     * ho to (shart, kaam); warna null —
     * [RuleCommands] wali hi pehchan, yahan
     * dobara (wahan private hai)।
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

    /** Halka sanket — pakka na mile tabhi। */
    private val THEN_MARKERS_WEAK = listOf("তো")

    /** "কম্বো" ka zikr hai kya (Bangla/Hindi/English)। */
    private fun mentionsCombo(lower: String): Boolean =
        COMBO_MENTION_MARKERS.any { lower.contains(it) }

    private val COMBO_MENTION_MARKERS = listOf(
        "কম্বো", "कॉम्बो", "combo"
    )

    /** Bandhe combo poochne ke roop — "কম্বো" shabd ke bina nahi। */
    private fun isComboListQuery(lower: String): Boolean =
        mentionsCombo(lower) && COMBO_LIST_MARKERS.any { lower.contains(it) }

    private val COMBO_LIST_MARKERS = listOf(
        "কী কী", "কি কি", "কী", "কি",
        "বলো", "বলে দাও", "বল",
        "মনে আছে", "আছে",
        "क्या", "बताओ",
        "what", "list", "my"
    )

    /** Chalane wala shabd — "… কম্বোটা চালাও" ke liye। */
    private val RUN_VERB_MARKERS = listOf(
        "চালাও", "চালিয়ে দাও", "চালিয়ে নাও", "চালু করো",
        "চালিয়ে", "চালা",
        "चलाओ", "चला दो", "चालू करो",
        "run", "start", "execute"
    )

    /** Bhulne wala shabd hai kya ([RuleCommands] ki hi suchi-shaili)। */
    private fun hasForgetWord(lower: String): Boolean =
        FORGET_MARKERS.any { lower.contains(it) }

    private val FORGET_MARKERS = listOf(
        "ভুলে যাও", "ভূলে যাও", "ভুলে যা",
        "মুছে দাও", "মুছে ফেলো", "মুছে ফেল",
        "भूल जाओ", "भूल जा", "मिटा दो", "मिटाओ",
        "forget", "delete"
    )

    /** Sab combo bhulne ke roop — "কম্বো" shabd ke bina nahi। */
    private fun isClearAllComboQuery(lower: String): Boolean =
        mentionsCombo(lower) && CLEAR_ALL_MARKERS.any { lower.contains(it) }

    private val CLEAR_ALL_MARKERS = listOf(
        "সব ভুলে যাও", "সব মুছে দাও",
        "সব মুছে ফেলো", "সব ভুলে যা",
        "सारे भूल जाओ", "सब भूल जाओ",
        "forget all", "clear all"
    )

    /** Hukm ki bhasha — jawab ki bhasha ke liye। */
    private fun currentLangOf(text: String): PlayIntent.SpokenLang =
        PlayIntent.requestLanguage(text)

    private const val MAX_WHEN_CHARS = 80
    private const val MAX_THEN_CHARS = 400
    private const val MAX_TRIGGER_CHARS = 60
    private const val MAX_STEPS = 5

    /** Bina disha ka data toggle — combo me nahi bandhta। */
    private const val CMD_DATA_TOGGLE = "cmd:mobile data"

    // ---------------------------------------------------------------
    // Chalane ka hisaab
    // ---------------------------------------------------------------

    /**
     * Combo chalne ke baad ek chhota hisaab — kaun
     * sa dhap hua, kaun sa nahi, aur lock ki wajah
     * se baaki dhap ruke hon to wahi bhi। Ek hi
     * saans me bola jata hai।
     */
    private fun runSummaryReply(
        combo: ComboRules.Combo,
        report: ComboRules.RunReport,
        lang: PlayIntent.SpokenLang
    ): String {
        val parts = mutableListOf<String>()
        for (run in report.steps) {
            parts.add(
                when {
                    run.ok -> ComboRules.stepDoneText(run.action, lang)
                    run.skippedScreenshot -> screenshotForegroundText(lang)
                    else -> ComboRules.stepFailText(run.action, lang)
                }
            )
        }
        if (report.stoppedByLock && report.steps.size < combo.steps.size) {
            parts.add(remainingAfterLockText(lang))
        }
        val body = parts.joinToString(", ")
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "'${combo.trigger}' কম্বো চালালাম — $body।"
            PlayIntent.SpokenLang.HINDI ->
                "'${combo.trigger}' कॉम्बो चलाया — $body।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Ran the '${combo.trigger}' combo — $body."
        }
    }

    /** Screenshot saamne wali screen par chhoda gaya — hisaab wali line। */
    private fun screenshotForegroundText(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনশট এই স্ক্রিনে নেওয়া হলো না"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीनशॉट इस स्क्रीन पर नहीं लिया गया"
            PlayIntent.SpokenLang.ENGLISH ->
                "Screenshot can't be taken from this screen"
        }

    /** Lock ki wajah se baaki dhap ruke — hisaab wali line। */
    private fun remainingAfterLockText(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "বাকি কাজগুলো হয়নি — ফোন লক হয়ে গেছে"
            PlayIntent.SpokenLang.HINDI ->
                "बाकी काम नहीं हुए — फ़ोन लॉक हो गया"
            PlayIntent.SpokenLang.ENGLISH ->
                "the rest didn't run — the phone is locked"
        }

    // ---------------------------------------------------------------
    // Bole jaane wale jawab
    // ---------------------------------------------------------------

    private fun teachSavedReply(
        lang: PlayIntent.SpokenLang,
        trigger: String,
        steps: List<String>
    ): String {
        val desc = steps.joinToString(", ") {
            ComboRules.describeStep(it, lang)
        }
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "মনে রাখলাম — '$trigger' বললে $desc।"
            PlayIntent.SpokenLang.HINDI ->
                "याद रख लिया — '$trigger' कहेंगे तो $desc।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Remembered — when you say '$trigger', $desc."
        }
    }

    /** Koi dhap na badle to imaandaar manaahi — kuch bandha hi nahi। */
    private fun comboBadStepReply(lang: PlayIntent.SpokenLang, badStep: String): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "এই ধাপটা ('$badStep') আমি করতে পারি না, " +
                    "তাই কম্বোটা শেখালাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "यह कदम ('$badStep') मैं नहीं कर सकती, " +
                    "इसलिए कॉम्बो नहीं सिखाया।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I can't do the step '$badStep', so I didn't learn that combo."
        }

    /** Ek dhap wala "combo" combo nahi — niyam/shabd-kaam ki taraf ishara। */
    private fun comboOneStepReply(
        lang: PlayIntent.SpokenLang,
        step: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "একটা কাজের জন্য কম্বো নয় — '$step'-এর জন্য " +
                "নিয়ম শেখাও ('নিয়ম মনে রাখো: যখন … তখন …')।"
        PlayIntent.SpokenLang.HINDI ->
            "एक काम के लिए कॉम्बो नहीं — '$step' के लिए " +
                "नियम सिखाओ ('नियम याद रखो: जब … तो …')।"
        PlayIntent.SpokenLang.ENGLISH ->
            "One step isn't a combo — teach it as a rule " +
                "('remember this rule: when … then …')."
    }

    /** 5 dhap se zyada — seema wali manaahi। */
    private fun comboTooManyReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "বেশি হয়ে গেছে — একটা কম্বোতে ৫টার বেশি ধাপ নয়।"
            PlayIntent.SpokenLang.HINDI ->
                "बहुत ज़्यादा हो गया — एक कॉम्बो में ५ से ज़्यादा कदम नहीं।"
            PlayIntent.SpokenLang.ENGLISH ->
                "That's too many — a combo has at most 5 steps."
        }

    /** "তখন" ke baad kuch na ho to manaahi। */
    private fun comboNoStepsReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "কম্বোতে কী কী কাজ হবে, সেটা বলো — " +
                    "যেমন 'কম্বো মনে রাখো: যখন গুড নাইট বলি তখন ডেটা বন্ধ করো, ফোন লক করো'।"
            PlayIntent.SpokenLang.HINDI ->
                "कॉम्बो में क्या-क्या काम होंगे, वो बताओ — " +
                    "जैसे 'कॉम्बो याद रखो: जब गुड नाइट कहूँ तो डेटा बंद करो, फ़ोन लॉक करो'।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Tell me what the combo should do — " +
                    "like 'remember this combo: when I say good night, turn data off, lock the phone'."
        }

    /** Jab-tak na ho to sikhane ka roop batao। */
    private fun comboNoWhenThenReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "কম্বো শেখাতে 'যখন … তখন …' বলো — " +
                    "যেমন 'কম্বো মনে রাখো: যখন গুড নাইট বলি তখন ডেটা বন্ধ করো, ফোন লক করো'।"
            PlayIntent.SpokenLang.HINDI ->
                "कॉम्बो सिखाने के लिए 'जब … तो …' कहो — " +
                    "जैसे 'कॉम्बो याद रखो: जब गुड नाइट कहूँ तो डेटा बंद करो, फ़ोन लॉक करो'।"
            PlayIntent.SpokenLang.ENGLISH ->
                "To teach a combo, say 'when … then …' — " +
                    "like 'remember this combo: when I say good night, turn data off, lock the phone'."
        }

    /** Bol bache hi na (sirf "বলি" jaisi framing) to manaahi। */
    private fun comboNoTriggerReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "কম্বোটার নামটা (বলটা) বলো — যেমন 'গুড নাইট'।"
            PlayIntent.SpokenLang.HINDI ->
                "कॉम्बो का नाम (बोल) बताओ — जैसे 'गुड नाइट'।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Tell me the combo's name (the phrase) — like 'good night'."
        }

    private fun listReply(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): String {
        val combos = ComboRules.readAll(context)
        if (combos.isEmpty()) {
            return noCombosReply(lang)
        }
        val lines = combos.takeLast(10).mapIndexed { index, combo ->
            val desc = combo.steps.joinToString(", ") {
                ComboRules.describeStep(it, lang)
            }
            "${index + 1}. '${combo.trigger}' — $desc"
        }.joinToString("; ")
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "মনে রাখা কম্বোগুলো: $lines"
            PlayIntent.SpokenLang.HINDI ->
                "याद रखे हुए कॉम्बो: $lines"
            PlayIntent.SpokenLang.ENGLISH ->
                "Combos I've remembered: $lines"
        }
    }

    private fun noCombosReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "এখনো কোনো কম্বো মনে রাখিনি।"
            PlayIntent.SpokenLang.HINDI ->
                "अभी कोई कॉम्बो याद नहीं रखा है।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I haven't remembered any combos yet."
        }

    private fun comboForgotReply(
        lang: PlayIntent.SpokenLang,
        trigger: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "'$trigger' কম্বোটা ভুলে গেছি।"
        PlayIntent.SpokenLang.HINDI ->
            "'$trigger' वाला कॉम्बो भूल गई।"
        PlayIntent.SpokenLang.ENGLISH ->
            "Forgotten — the '$trigger' combo."
    }

    private fun clearedAllReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ঠিক আছে — সব কম্বো ভুলে গেছি।"
            PlayIntent.SpokenLang.HINDI ->
                "ठीक है — सारे कॉम्बो भूल गई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Okay — I've forgotten all the combos."
        }
}

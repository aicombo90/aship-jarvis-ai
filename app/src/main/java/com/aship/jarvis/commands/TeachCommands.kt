package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.TaughtCommands
import com.aship.jarvis.ai.UserProfile
import java.util.Locale

/**
 * v50: SIKHANE WALE NAYE PAKKE HUKM — do mezbaan
 * (saamne wali screen + background wake service)
 * yahi ek object bulate hain, bilkul
 * [LearnCommands] ki tarah।
 *
 * Teen kaam:
 *  1. NAAM bandhna — "আমার নাম <নাম>
 *     (মনে রেখো)" → [UserProfile] me naam;
 *     "নাম ভুলে যাও" → naam mita। Sawal
 *     ("আমার নাম কী?") na bandhta hai na
 *     mitata hai — null, aam raasta chalega।
 *  2. SHABD-KAAM sikhana —
 *     "শিখে নাও: '<shabd>' বললে <kaam>" —
 *     kaam [TaughtCommands.resolveAction]
 *     se bandhe auzaar me badle tabhi bandhta
 *     hai; na badle to imaandaar manaahi aur
 *     KUCH nahi bandhta। Saath hi bandhe
 *     jodon ki suchi ("কী কী শেখানো আছে") aur
 *     ek jod bhulna ("<shabd> ভুলে যাও")।
 *  3. Bandhe shabd ka kaam chalana —
 *     [tryExecuteTaught]: baat kisi bandhe
 *     shabd ke akele-roop se mile to kaam
 *     seedha offline। Mehmaan ise shutdown ke
 *     baad, mastishk se pehle bulate hain।
 *
 * TAKRAAV se bachav ([LearnCommands] ke
 * saath): ye object sirf WAHI roop pakadta
 * hai jo saaf iske hain — "বললে" wala
 * sikhana, naam wali baat, bandhe-jod wali
 * suchi/bhulna। "শিখে নাও: <chaal>" (bina
 * "বললে" ke) v49 wali chaal hi rehti hai aur
 * [LearnCommands] ke paas hi jati hai —
 * isiliye mezbaan ise [LearnCommands] se
 * PEHLE bulate hain, par ye bina apne roop
 * ke null dekar raasta chhod deta hai।
 */
object TeachCommands {

    /**
     * Sikhane/naam wali baat ho to bola jaane
     * wala jawab ([lang] me), warna null।
     * Kabhi exception nahi — gadbad par null।
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

        // 1. Naam bhulna — "আমার নাম ভুলে যাও" /
        // "নামটা ভুলে যাও"। (Bandhe-jod wala
        // bhulna isse alag hai, neeche hai —
        // naam wali baat me "নাম" saaf hai,
        // isliye pehle yahi।)
        if (hasForgetWord(lower) && NAME_WORD_MARKERS.any {
                lower.contains(it)
            }
        ) {
            if (UserProfile.getName(context) != null) {
                UserProfile.clearName(context)
                return nameForgotReply(lang)
            }
            return null
        }

        // 2. Naam bandhna — sawal ho to nahi।
        extractStatedName(text)?.let { name ->
            UserProfile.setName(context, name)
            return nameSavedReply(lang, name)
        }

        // 3. Shabd-kaam sikhana — "'X' বললে Y"।
        parseMappingTeach(text)?.let { (trigger, actionText) ->
            val action = TaughtCommands.resolveAction(context, actionText)
            return if (action == null) {
                teachUnresolvedReply(lang, actionText)
            } else {
                TaughtCommands.save(context, trigger, action)
                teachSavedReply(lang, trigger, action)
            }
        }

        // 4. Bandhe jodon ki suchi।
        if (isMappingListQuery(lower)) {
            return listReply(context, lang)
        }

        // 5. Ek bandha jod bhulna — bhulne wala
        // shabd + bandhe shabd ka saaf zikr,
        // dono hon tabhi (warna [LearnCommands]
        // ka routine-wala bhulna chalega)।
        if (hasForgetWord(lower)) {
            val mentioned = TaughtCommands.findMentioned(context, text)
            if (mentioned != null) {
                TaughtCommands.delete(context, mentioned)
                return mappingForgotReply(lang, mentioned.trigger)
            }
        }
        return null
    }

    /**
     * Baat kisi bandhe shabd ka akela roop ho
     * to uska kaam chalakar natija; warna
     * null। Kabhi exception nahi।
     */
    fun tryExecuteTaught(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): TaughtCommands.Execution? {
        return try {
            val mapping = TaughtCommands.matchTrigger(context, rawText)
                ?: return null
            TaughtCommands.execute(context, engine, mapping, lang)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Naam ki pehchan
    // ---------------------------------------------------------------

    /**
     * "আমার নাম <নাম>" wali baat se naam
     * nikalo — marker ke baad ka hissa, yaad
     * wale shabdon/viram par kata hua। Sawal
     * ho ("নাম কী"), marker ke baad kuch na
     * ho, ya hissa bahut lamba/ulta-sawaal
     * jaisa ho to null।
     */
    private fun extractStatedName(rawText: String): String? {
        val text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)
        if (QUESTION_MARKERS.any { lower.contains(it) }) return null
        val marker = NAME_MARKERS.firstOrNull { lower.contains(it) }
            ?: return null
        val start = lower.indexOf(marker) + marker.length
        var rest = text.substring(start)
            .trimStart(' ', ':', '—', '-')
        // "আমার নাম হল X" — aage ka "হল/হলো" bhi hatao
        // (upar trimStart akshar-kaat hai, isliye
        // shabd wala kaat yahan alag se)।
        rest = rest.replace(Regex("^(হলো|হল|হচ্ছে|is)\\s+"), "")
        val cutAt = NAME_CUT_MARKERS
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
        if (cutAt != null) rest = rest.substring(0, cutAt)
        val name = rest
            .trim()
            .trim('।', '.', '!', '?', ',', '"', '\'')
            .trim()
        if (name.isEmpty() || name.length > 40) return null
        if (name.split(Regex("\\s+")).size > 4) return null
        if (NAME_REJECT_MARKERS.any { name.contains(it) }) return null
        if (NAME_WORD_MARKERS.any { name.contains(it) } &&
            name.split(Regex("\\s+")).size > 2
        ) {
            // "নাম" shabd phir ghusa hua lamba
            // wakya — naam nahi, aam baat hai।
            return null
        }
        return name
    }

    /** Naam batane ki shuruwaat — inke baad ka hissa naam hai। */
    private val NAME_MARKERS = listOf(
        "আমার নাম", "আমার নামটা",
        "मेरा नाम", "my name is", "my name",
        "mera naam", "amar naam"
    )

    /** Naam wale hisse ko yahin kaato — iske aage yaad wala hukm hai, naam nahi। */
    private val NAME_CUT_MARKERS = listOf(
        "মনে রেখো", "মনে রাখো", "মনে রেখে", "মনে রাখবে",
        "যেন মনে থাকে", "याद रखो", "याद रखना",
        "remember", "।", ",", "!", "?"
    )

    /** Naam ke hisse me ye ho to woh naam nahi, koi aur baat hai (jaise "নাম পাল্টাতে চাই")। */
    private val NAME_REJECT_MARKERS = listOf(
        "চাই", "বলো", "বলতে", "জানো", "জানতে", "করতে", "পাল্টা"
    )

    /** "নাম" ke roop — naam-bhulne ki pehchan ke liye। */
    private val NAME_WORD_MARKERS = listOf(
        "নাম", "नाम", "name", "naam"
    )

    /** Sawal ke sanket — sawaal par naam kabhi nahi bandhta। */
    private val QUESTION_MARKERS = listOf(
        "কী", "কি?", "কী?", "কি ", "কী ",
        "क्या", "what", "?"
    )

    // ---------------------------------------------------------------
    // Shabd-kaam sikhaane ki pehchan
    // ---------------------------------------------------------------

    /**
     * "'<shabd>' বললে <kaam>" wala sikhana ho
     * to (shabd, kaam-text); warna null।
     * Shuruwaat me sikhane wala hukm (শিখে
     * নাও / মনে রেখো + colon / सीख लो) hona
     * chahiye — aam baat me aaya "বললে" sikhana
     * NAHI hai। "বললে" se pehle ka hissa
     * (hukm hatakar, uddharan-chinh hatakar)
     * bandha shabd hai; baad ka hissa kaam।
     */
    private fun parseMappingTeach(rawText: String): Pair<String, String>? {
        var text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)
        val starter = TEACH_STARTERS.firstOrNull {
            lower.startsWith(it)
        } ?: return null
        text = text.substring(starter.length)
            .trimStart(' ', ':', '—', '-', '"', '\'')
        val body = text.lowercase(Locale.ROOT)
        val marker = CONDITION_MARKERS.firstOrNull {
            body.contains(it)
        } ?: return null
        val at = body.indexOf(marker)
        val trigger = text.substring(0, at)
            .trim()
            .trim('\'', '"', '‘', '’', '“', '”', ' ', '।', ',')
            .trim()
        var action = text.substring(at + marker.length)
            .trimStart(' ', ':', '—', '-', '।', ',')
            .trim()
        // "বললে তুমি টর্চ জ্বালাবে" — kaam se
        // pehle ka pukar-shabd hatao।
        action = action.replace(
            Regex("^(তুমি|তুই|আপনি|তু|you)\\s+"), ""
        ).trim()
        // Kaam ke aakhir ke "হবে/করবে" wale
        // latke roop waise hi rahenge —
        // resolveAction unhi par pehchan lagata
        // hai (shabd wahin chhupa hota hai)।
        if (trigger.isEmpty() || trigger.length > 60) return null
        if (action.isEmpty() || action.length > 80) return null
        if (trigger.split(Regex("\\s+")).size > 5) return null
        return trigger to action
    }

    /** Sikhaane ki shuruwaat — inke bina "বললে" wala wakya sikhana nahi। */
    private val TEACH_STARTERS = listOf(
        "শিখে নাও", "শিখে রাখো", "শিখে নে",
        "মনে রেখো:", "মনে রেখো :", "মনে রাখো:",
        "শেখাও", "শিখিয়ে দাও",
        "सीख लो", "याद रखो:",
        "learn this", "teach"
    )

    /** Sart wale shabd — inse pehle bandha shabd, baad me kaam। */
    private val CONDITION_MARKERS = listOf(
        "বললেই", "বললে", "বলি তো", "বললে তুমি",
        "बोलने पर", "बोलूँ तो", "कहने पर", "कहूँ तो",
        "when i say", "if i say"
    )

    /** Bandhe jod poochne ke roop — [LearnCommands] ki "কী কী শিখেছ" (chaal) se alag। */
    private fun isMappingListQuery(lower: String): Boolean =
        MAPPING_LIST_MARKERS.any { lower.contains(it) }

    private val MAPPING_LIST_MARKERS = listOf(
        "কী কী শেখানো আছে", "কি কি শেখানো আছে",
        "কী শেখানো আছে", "কি শেখানো আছে",
        "শেখানো কমান্ড", "শেখানো শব্দ",
        "কমান্ড শেখানো", "শব্দ শেখানো",
        "क्या क्या सिखाया है", "क्या सिखाया है",
        "taught commands", "my commands"
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

    // ---------------------------------------------------------------
    // Bole jaane wale jawab
    // ---------------------------------------------------------------

    private fun nameSavedReply(
        lang: PlayIntent.SpokenLang,
        name: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "মনে রাখলাম — তোমার নাম $name। এবার থেকে নাম ধরেই ডাকব।"
        PlayIntent.SpokenLang.HINDI ->
            "याद रख लिया — आपका नाम $name है। अब से नाम लेकर ही बुलाऊँगी।"
        PlayIntent.SpokenLang.ENGLISH ->
            "Remembered — your name is $name. I'll call you by name now."
    }

    private fun nameForgotReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ঠিক আছে — নামটা ভুলে গেলাম।"
            PlayIntent.SpokenLang.HINDI ->
                "ठीक है — नाम भूल गई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Okay — I've forgotten the name."
        }

    private fun teachSavedReply(
        lang: PlayIntent.SpokenLang,
        trigger: String,
        action: String
    ): String {
        val desc = TaughtCommands.describe(action, lang)
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "শিখে নিলাম — '$trigger' বললেই $desc।"
            PlayIntent.SpokenLang.HINDI ->
                "सीख लिया — '$trigger' कहने पर $desc।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Learned — saying '$trigger' now makes $desc."
        }
    }

    /** Kaam na pehchana jaye to imaandaar manaahi — kuch bandha hi nahi। */
    private fun teachUnresolvedReply(
        lang: PlayIntent.SpokenLang,
        actionText: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "এটা ঠিক বুঝলাম না — '$actionText' দিয়ে কোন কাজটা হবে " +
                "পরিষ্কার হলো না, তাই কিছু শেখালাম না।"
        PlayIntent.SpokenLang.HINDI ->
            "यह ठीक समझ नहीं आया — '$actionText' से कौन-सा काम होगा " +
                "साफ़ नहीं है, इसलिए कुछ नहीं सिखाया।"
        PlayIntent.SpokenLang.ENGLISH ->
            "I couldn't tell what action '$actionText' is, so I " +
                "didn't learn anything."
    }

    private fun listReply(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): String {
        val mappings = TaughtCommands.readAll(context)
        if (mappings.isEmpty()) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "এখনো কোনো শেখানো কমান্ড নেই।"
                PlayIntent.SpokenLang.HINDI ->
                    "अभी कोई सिखाया हुआ कमांड नहीं है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "There are no taught commands yet."
            }
        }
        val lines = mappings.takeLast(10).mapIndexed { index, mapping ->
            "${index + 1}. '${mapping.trigger}' — " +
                TaughtCommands.describe(mapping.action, lang)
        }.joinToString("; ")
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "শেখানো কমান্ডগুলো: $lines"
            PlayIntent.SpokenLang.HINDI ->
                "सिखाए हुए कमांड: $lines"
            PlayIntent.SpokenLang.ENGLISH ->
                "Taught commands: $lines"
        }
    }

    private fun mappingForgotReply(
        lang: PlayIntent.SpokenLang,
        trigger: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "'$trigger' — ভুলে গেছি।"
        PlayIntent.SpokenLang.HINDI ->
            "'$trigger' — भूल गई।"
        PlayIntent.SpokenLang.ENGLISH ->
            "Forgotten — '$trigger'."
    }
}

package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.LearnedRoutines
import java.util.Locale

/**
 * v49: SEEKHNE WALE PAKKE HUKM — "মনে রেখো"
 * (aakhiri safal kaam bandho), "শিখে নাও: …"
 * (bolkar chaal sikhao), "কী কী শিখেছ" (bandhe
 * kaamon ke naam sunao), "… ভুলে যাও" (ek bandha
 * kaam mitao)।
 *
 * Dono mezbaan (saamne wali screen + background
 * wake service) yahi ek object bulate hain —
 * [tryHandle] baat seekhne wali ho to bola
 * jaane wala sachha jawab lautata hai, na ho
 * to null (bulane wala apna aam raasta chalata
 * hai, bilkul pehle jaisa)।
 *
 * V31 WALI TATHYA-YAAD KE SAATH TAKRAAV NA HO,
 * iske bandhe niyam:
 *  - "মনে রেখো" AKELA (ya "এটা/ওটা/এই কাজটা"
 *    jaise ishare ke saath) = aakhiri safal
 *    KAAM bandho। "মনে রেখো, <koi baat>" — baad
 *    me tathya ho to ye hukm NAHI hai: jawab
 *    null, baat AgentBrain ke remember_fact
 *    wale purane raaste par hi jayegi।
 *  - "শিখে নাও" ke baad kuch ho to woh SIKHAYI
 *    chaal hai (kaam) — tathya nahi। "মনে রেখো:"
 *    colon ke saath bhi waisa hi (colon = woh
 *    kuch bandha hua sikha raha hai); bina
 *    colon wala "মনে রেখো <baat>" tathya wala
 *    purana raasta hi rakhta hai।
 *  - "ভুলে যাও" tabhi yahan sambhalta hai jab
 *    baat me kisi BANDHE KAAM ka naam saaf
 *    mile; warna null — tathya-bhulne wala
 *    purana vyavhaar (brain ka forget_fact)
 *    bilkul pehle jaisa chalega।
 *
 * SAFALTA KA DWAAR sabse sakht hai aur
 * [LearnedRoutines.saveFromLastRun] me bandha
 * hai: aakhiri screen-daud SUCCESS na ho to
 * kuch bandhta NAHI aur jawab imaandaari se
 * wahi kehta hai — asafal chaal kabhi nahi
 * seekhi jati (user ka pakka hukm)।
 */
object LearnCommands {

    /**
     * Baat seekhne wali ho to bola jaane wala
     * jawab (user ki / shaksiyat ki bhasha [lang]
     * me), warna null। Kabhi exception nahi —
     * godam ki gadbad par null (aam raasta chalega)।
     */
    fun tryHandle(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        return try {
            handle(context, rawText, lang)
        } catch (t: Throwable) {
            null
        }
    }

    private fun handle(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        // 1. Sikhayi chaal — "শিখে নাও …" sabse
        // pehle: iske baad ka hissa kaam hai, tathya
        // nahi (upar wala niyam dekho)।
        teachProcedure(text)?.let { procedure ->
            val steps = splitSteps(procedure)
            if (steps.isNotEmpty()) {
                val name = deriveTaughtName(procedure)
                LearnedRoutines.saveTaught(context, name, steps)
                return taughtSavedReply(lang, name)
            }
        }
        // 2. Bandhe kaamon ki suchi — "কী কী শিখেছ"।
        if (isLearnedListQuery(text)) {
            return listReply(context, lang)
        }
        // 3. Ek bandha kaam bhulna — naam saaf mile
        // tabhi; warna purana tathya-wala raasta।
        if (hasForgetWord(text)) {
            val mentioned = LearnedRoutines.findMentioned(context, text)
            if (mentioned != null) {
                LearnedRoutines.delete(context, mentioned)
                return forgotReply(lang, mentioned.name)
            }
            return null
        }
        // 4. "মনে রেখো" akela / ishare ke saath —
        // aakhiri safal kaam bandho (dwaar sakht)।
        if (isRememberCommand(text)) {
            return when (
                val outcome = LearnedRoutines.saveFromLastRun(context)
            ) {
                is LearnedRoutines.SaveOutcome.Saved ->
                    savedReply(lang)
                LearnedRoutines.SaveOutcome.NoSuccessfulRun ->
                    notSavedReply(lang)
            }
        }
        return null
    }

    // ---------------------------------------------------------------
    // Pehchan
    // ---------------------------------------------------------------

    /**
     * Sikhayi chaal wala hukm ho to chaal ka text,
     * warna null। Shuruwaat bandhe roop se honi
     * chahiye aur uske baad ASAL kuch bacha hona
     * chahiye — akela "শিখে নাও" chaal nahi hai
     * (woh [isRememberCommand] wala raasta hai)।
     */
    private fun teachProcedure(rawText: String): String? {
        val text = rawText.trim()
        val lower = text.lowercase(Locale.ROOT)
        for (starter in TEACH_STRONG_STARTERS) {
            if (lower.startsWith(starter)) {
                val rest = text.substring(starter.length)
                    .trimStart(' ', ':', '—', '-', '।', ',', '"')
                    .trim()
                if (rest.isNotEmpty()) return rest
            }
        }
        // "মনে রেখো:" — colon wala roop hi sikhaana
        // hai; bina colon wala tathya wala raasta hai।
        for (starter in REMEMBER_STARTERS) {
            if (lower.startsWith(starter)) {
                val after = text.substring(starter.length)
                val colonAt = after.indexOf(':')
                if (colonAt in 0..2) {
                    val rest = after.substring(colonAt + 1).trim()
                    if (rest.isNotEmpty()) return rest
                }
            }
        }
        return null
    }

    /** Seekhne ki shuruwaat — inke baad ka hissa hamesha chaal hai। */
    private val TEACH_STRONG_STARTERS = listOf(
        "শিখে নাও", "শিখে রাখো", "শিখে নে",
        "सीख लो", "सीख रखो",
        "learn this"
    )

    /** Yaad rakhne ki shuruwaat — colon ho tabhi sikhana, warna tathya। */
    private val REMEMBER_STARTERS = listOf(
        "মনে রেখো", "মনে রাখো",
        "याद रखो",
        "remember this"
    )

    /**
     * "মনে রেখো" apne aap me poora hukm hai kya —
     * wakya me yaad/shikhne wala wakyaansh ho AUR
     * uske alawa sirf ishara-shabd (এটা/ওটা/এই
     * কাজটা/this/it) bache hon। Usse zyada bacha
     * ho to woh tathya hai — ye hukm nahi, null
     * wala raasta (brain ka remember_fact)।
     */
    private fun isRememberCommand(rawText: String): Boolean {
        var rest = rawText.trim()
            .trim('।', '.', '!', '?', ' ')
        val lower = rest.lowercase(Locale.ROOT)
        val phrase = REMEMBER_PHRASES.firstOrNull {
            lower.contains(it)
        } ?: return false
        rest = lower.replaceFirst(phrase, " ")
            .replace(Regex("[।.,!?,;:—\\-]"), " ")
        val tokens = rest.split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return tokens.all { it in DEMONSTRATIVE_TOKENS }
    }

    /** Yaad/shikhne wale wakyaansh (koi bhi ek ho to ummeedwaar)। */
    private val REMEMBER_PHRASES = listOf(
        "মনে রেখো", "মনে রাখো", "মনে রেখ",
        "শিখে নাও", "শিখে রাখো",
        "याद रखो", "सीख लो",
        "remember this", "learn this"
    )

    /** Ishara-shabd — inke alawa kuch bacha to baat tathya hai, kaam nahi। */
    private val DEMONSTRATIVE_TOKENS = setOf(
        "এটা", "ওটা", "এইটা", "সেইটা", "এই", "ওই", "সেই",
        "কাজটা", "কাজ", "টা", "এবারের", "এবার",
        "this", "it", "that", "the", "task"
    )

    /** "কী কী শিখেছ" — bandhe kaamon ke naam poochna। */
    private fun isLearnedListQuery(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        return LIST_QUERY_MARKERS.any { lower.contains(it) }
    }

    private val LIST_QUERY_MARKERS = listOf(
        "কী কী শিখেছ", "কি কি শিখেছ",
        "কী শিখেছো", "কি শিখেছো",
        "কী শিখেছ", "কি শিখেছ",
        "তুমি কী শিখেছ", "তুমি কি শিখেছ",
        "क्या सीखा है", "क्या क्या सीखा", "क्या सीखा",
        "what have you learned", "what did you learn"
    )

    /** Bhulne wala shabd hai kya (naam-milan iske baad hi hota hai)। */
    private fun hasForgetWord(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        return FORGET_MARKERS.any { lower.contains(it) }
    }

    private val FORGET_MARKERS = listOf(
        "ভুলে যাও", "ভূলে যাও", "ভুলে যা",
        "মুছে দাও", "মুছে ফেলো", "মুছে ফেল",
        "भूल जाओ", "भूल जा", "मिटा दो", "मिटाओ",
        "forget", "delete"
    )

    // ---------------------------------------------------------------
    // Chaal ke kadam + naam
    // ---------------------------------------------------------------

    /**
     * Sikhayi chaal ko kadmon me todo — bandhe
     * alag-karak: nayi line, "তারপর", "then",
     * ",", "।", "→", ";"। Koi alag-karak na mile
     * to poora text EK kadam (jyon ka tyon)।
     */
    private fun splitSteps(procedure: String): List<String> {
        val normalized = procedure.replace("\r\n", "\n")
        val parts = normalized
            .split(
                Regex(
                    "\\n+|তারপর|\\bthen\\b|,|।|→|;|\\band\\b",
                    RegexOption.IGNORE_CASE
                )
            )
            .map { it.trim().trim('।', '.', ' ') }
            .filter { it.isNotEmpty() }
        return parts.take(LearnedRoutines.MAX_STEPS)
    }

    /** Sikhayi chaal ka naam — uske pehle hisse ka kata roop (bolne me bhi yahi sunega)। */
    private fun deriveTaughtName(procedure: String): String {
        val first = splitSteps(procedure).firstOrNull()
            ?: procedure.trim()
        return first.take(50).trim()
    }

    // ---------------------------------------------------------------
    // Bole jaane wale jawab
    // ---------------------------------------------------------------

    private fun savedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "মনে রেখেছি — পরের বার শুধু বললেই আবার করে দেব।"
            PlayIntent.SpokenLang.HINDI ->
                "याद रख लिया — अगली बार बस कहिएगा, फिर से कर दूँगी।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Remembered — next time just say the word and " +
                    "I'll do it again."
        }

    /** User ka pakka imaandaar jawab: asafal kaam kabhi nahi seekha jata। */
    private fun notSavedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "কাজটা সফলভাবে শেষ হয়নি, তাই এটা মনে রাখলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "काम सफलतापूर्वक पूरा नहीं हुआ, इसलिए इसे याद " +
                    "नहीं रखा।"
            PlayIntent.SpokenLang.ENGLISH ->
                "That task didn't finish successfully, so I " +
                    "didn't remember it."
        }

    private fun taughtSavedReply(
        lang: PlayIntent.SpokenLang,
        name: String
    ): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "শিখে নিলাম — '$name'। পরের বার বললেই এই পথে করে দেব।"
            PlayIntent.SpokenLang.HINDI ->
                "सीख लिया — '$name'। अगली बार कहिएगा तो इसी " +
                    "रास्ते से कर दूँगी।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Learned it — '$name'. Next time you ask, I'll " +
                    "follow these steps."
        }

    private fun listReply(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): String {
        val routines = LearnedRoutines.readAll(context)
        if (routines.isEmpty()) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "এখনো কিছু শিখিনি।"
                PlayIntent.SpokenLang.HINDI ->
                    "अभी तक कुछ नहीं सीखा है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I haven't learned anything yet."
            }
        }
        val names = routines.takeLast(10)
            .mapIndexed { index, routine ->
                "${index + 1}. ${routine.name}"
            }
            .joinToString("; ")
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "আমি এই কাজগুলো শিখেছি: $names"
            PlayIntent.SpokenLang.HINDI ->
                "मैंने ये काम सीखे हैं: $names"
            PlayIntent.SpokenLang.ENGLISH ->
                "I have learned these tasks: $names"
        }
    }

    private fun forgotReply(
        lang: PlayIntent.SpokenLang,
        name: String
    ): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "'$name' — ভুলে গেছি।"
            PlayIntent.SpokenLang.HINDI ->
                "'$name' — भूल गई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Forgotten — '$name'."
        }
}

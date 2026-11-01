package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.AgentTools
import java.util.Locale

/**
 * v36: "gaana chalao" wale SANYOJIT (compound) hukm ki
 * pehchan — "YouTube khulo AUR Kishore Kumar ka gaana
 * chalao" ek hi saans me bola gaya kaam.
 *
 * v35 tak ki gadbad: CommandEngine ki pakki app-list
 * "youtube" dekhte hi sirf app khol deti thi, aur agent
 * ko round 1 par 429 mil jaye to poora kaam wahin mar
 * jata tha — gaana kabhi chalta hi nahi tha. Isliye ab
 * routing ki sabse pehli seedhi yahi hai: play-iraada
 * (play kriya + media shabd ya YouTube ka zikr) pehle
 * pakdo, query nikalo, phir bulane wala faisla karta
 * hai — agent uplabdh ho to agent se, warna seedha
 * youtube_play auzaar se (bina Gemini ke bhi gaana
 * bajega, yahi is file ka dusra kaam hai).
 *
 * Pehchan kachhe (raw) sune hue text par hoti hai —
 * Bangla / Devanagari / Latin, teeno scripton ke shabd
 * ek saath rakhe hain. Query nikaalne me sirf hukm wale
 * shabd (YouTube ka naam, kholne/chalane ki kriya,
 * jodne wale shabd) kat-te hain — media shabd ("গান")
 * query me BANA rehta hai, bilkul user ki misal jaisa:
 * "ইউটিউব খুলে কিশোর কুমারের গান চালাও" -> query
 * "কিশোর কুমারের গান"।
 *
 * Bola jaane wala natija hamesha imaandaar aur user ki
 * bhasha me: chal gaya / results khule par pakka nahi /
 * YouTube khula hi nahi. Auzaar ke Hinglish text ko
 * awaaz ke liye yahan bhasha ke hisaab se badla jata
 * hai — agent wale raaste par ye kaam mastishk khud
 * karta hai, seedhe raaste par yahi file.
 */
object PlayIntent {

    /** Bolne wale jawab ki bhasha — sune hue text ki script se. */
    enum class SpokenLang { BENGALI, HINDI, ENGLISH }

    /**
     * Play-iraada ho to search query lautao. Iraada hi na
     * ho to null. Iraada ho par hukm wale shabd kaatne ke
     * baad query khaali bache ("YouTube chalao") to khaali
     * string — bulane wala us haal me aam raasta hi lega
     * (sirf YouTube khulega, pehle jaisa).
     */
    fun extractPlayQuery(rawText: String): String? {
        val lower = rawText.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        val tokens = lower.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', ':', ';', '"', '\'') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val hasPlayVerb = tokens.any { it in PLAY_VERBS } ||
            SCRIPT_PLAY_VERBS.any { lower.contains(it) }
        if (!hasPlayVerb) return null
        val hasMedia = tokens.any { it in MEDIA_TOKENS } ||
            SCRIPT_MEDIA_WORDS.any { lower.contains(it) }
        val hasYouTube = tokens.any { it in YOUTUBE_TOKENS } ||
            SCRIPT_YOUTUBE_WORDS.any { lower.contains(it) }
        if (!hasMedia && !hasYouTube) return null
        return tokens.filter { it !in STRIP_TOKENS }.joinToString(" ")
    }

    /** Sune hue text ki bhasha — aksharon ke Unicode block gin kar. */
    fun requestLanguage(text: String): SpokenLang {
        var bengali = 0
        var devanagari = 0
        for (ch in text) {
            when (ch) {
                in '\u0980'..'\u09FF' -> bengali++
                in '\u0900'..'\u097F' -> devanagari++
            }
        }
        return when {
            bengali > 0 && bengali >= devanagari -> SpokenLang.BENGALI
            devanagari > 0 -> SpokenLang.HINDI
            else -> SpokenLang.ENGLISH
        }
    }

    /**
     * Saare model 429 (quota) par marein to Maya ka
     * imaandaar jawab — online seema khatm hai, jhootha
     * "nahi kar sakta" nahi. User ki bhasha me.
     */
    fun quotaReply(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "এখন অনলাইনের সীমা শেষ হয়ে গেছে — একটু পরে আবার চেষ্টা করো।"
        SpokenLang.HINDI ->
            "अभी online की सीमा ख़त्म हो गई है — थोड़ी देर बाद फिर कोशिश करना।"
        SpokenLang.ENGLISH ->
            "The online limit is used up right now — please try again a little later."
    }

    /**
     * Seedhe youtube_play auzaar ke natije ka bola jaane
     * wala roop — user ki bhasha me, aur hamesha sachha:
     * ok = video chal gaya; FAILED se shuru = YouTube
     * khula hi nahi; baaki sab PARTIAL (results khule,
     * par video chalna pakka nahi).
     */
    fun playOutcomeReply(
        result: AgentTools.ToolResult,
        query: String,
        lang: SpokenLang
    ): String = when {
        result.ok -> when (lang) {
            SpokenLang.BENGALI -> "'$query' চালিয়ে দিয়েছি।"
            SpokenLang.HINDI -> "'$query' चला दिया है।"
            SpokenLang.ENGLISH -> "Playing '$query'."
        }
        result.text.startsWith("FAILED") -> when (lang) {
            SpokenLang.BENGALI ->
                "YouTube খুলতেই পারলাম না — একবার নিজে চেষ্টা করে দেখো।"
            SpokenLang.HINDI ->
                "YouTube खुल ही नहीं पाया — एक बार ख़ुद कोशिश करके देखो।"
            SpokenLang.ENGLISH ->
                "I couldn't open YouTube at all — please try once yourself."
        }
        else -> when (lang) {
            SpokenLang.BENGALI ->
                "'$query'-এর results খুলে দিয়েছি, কিন্তু ভিডিওটা সত্যিই " +
                    "চালু হয়েছে কিনা পাক্কা বুঝতে পারিনি — screen-এ " +
                    "results আছে, একটা tap করে দাও।"
            SpokenLang.HINDI ->
                "'$query' के results खोल दिए हैं, पर video सच में चला " +
                    "या नहीं यह पक्का नहीं हो पाया — screen पर results " +
                    "हैं, एक tap कर देना।"
            SpokenLang.ENGLISH ->
                "I opened the results for '$query', but I can't confirm " +
                    "the video actually started — the results are on " +
                    "the screen, please tap one."
        }
    }

    /**
     * Seedhi daud ki report bhi "jarvis_diag" me band ho —
     * "Agent chaal (aakhiri)" wali line seedhe raaste ko
     * bhi dikhaye (agent wali report se alag pehchan ke
     * liye "seedha" shabd ke saath).
     */
    fun saveDirectPlayDiag(
        context: Context,
        query: String,
        result: AgentTools.ToolResult
    ) {
        val tag = when {
            result.ok -> "OK"
            result.text.startsWith("FAILED") -> "FAIL"
            else -> "PARTIAL"
        }
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "seedha youtube_play(" + query.take(20) + ") = " + tag
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — gaana bajna isse nahi rukta.
        }
    }

    /** Play kriya ke saare roop (token milaane ke liye). */
    private val PLAY_VERBS = setOf(
        "play", "chalao", "bajao", "sunao",
        "চালাও", "চালা", "বাজাও", "শোনাও",
        "चलाओ", "बजाओ", "सुनाओ"
    )

    /** Script wali kriyaen chipki hui aayein to bhi pakdi jayein (contains). */
    private val SCRIPT_PLAY_VERBS = listOf(
        "চালাও", "চালা", "বাজাও", "শোনাও",
        "चलाओ", "बजाओ", "सुनाओ"
    )

    /** Latin media shabd (token milaane ke liye). */
    private val MEDIA_TOKENS = setOf(
        "song", "songs", "video", "videos", "gaana", "gana", "music"
    )

    /** Script wale media shabd (contains — "গান" har roop me mile). */
    private val SCRIPT_MEDIA_WORDS = listOf(
        "গান", "ভিডিও", "মিউজিক",
        "गाना", "गाने", "वीडियो", "म्यूजिक"
    )

    private val YOUTUBE_TOKENS = setOf("youtube")

    private val SCRIPT_YOUTUBE_WORDS = listOf(
        "ইউটিউব", "ইউটুব", "য়ুটুব",
        "यूट्यूब", "युट्यूब", "यूटुब",
        "you tube"
    )

    /**
     * Query se katne wale hukm wale shabd: YouTube ka naam,
     * kholne ki kriya, chalane ki kriya, jodne wale shabd.
     * Media shabd jaan-boojh kar NAHI hai — woh query ka
     * hissa hai ("কিশোর কুমারের গান")।
     */
    private val STRIP_TOKENS = setOf(
        // YouTube ka naam
        "youtube", "ইউটিউব", "ইউটুব", "য়ুটুব", "যুটুব",
        "यूट्यूब", "युट्यूब", "यूटुब",
        // Kholne ki kriya
        "open", "kholo", "khol", "khule",
        "খোলো", "খোল", "খুলে", "খুলো",
        "खोलो", "खोल", "खोलकर",
        // Chalane ki kriya
        "play", "chalao", "bajao", "sunao",
        "চালাও", "চালা", "চালিয়ে", "বাজাও", "শোনাও",
        "चलाओ", "बजाओ", "सुनाओ",
        // Jodne wale shabd
        "and", "aur", "এবং", "আর", "ও", "और"
    )
}

package com.aship.jarvis.commands

import java.util.Locale

/**
 * v49: TAANA (continuous) SCREEN SESSION ke pakke
 * shabd-jaanch — wake service ki session wali
 * kataar inhi chaar faislon par chalti hai:
 *
 *  1. [isShutdownCommand] — "সিস্টেম শাটডাউন":
 *     session khatm, gola gayab, par service zinda
 *     (wake sunna chaloo)। Session ke dispatch me
 *     ye jaanch SABSE PEHLE lagti hai, har baat par।
 *  2. [isNoMoreAnswer] — "আর কিছু করতে হবে?" ke
 *     jawab me saaf NA / bas: session vinamrata se
 *     band। Ye jaanch sirf usi prompt ke turant
 *     baad wali baat par lagti hai (bulane wala
 *     iska dhyaan rakhta hai) — beech kaam me "না"
 *     bolna session nahi band karta।
 *  3. [isSendInstruction] — user ka APNA hukm hi
 *     bhejne ko kehta ho ("পাঠিয়ে দাও", "भेज दो",
 *     "send it"): usi hukm se chale screen-kaam
 *     me Send wala tap usi ke diye hue hukm se
 *     pehle-se-pusht (pre-confirmed) mana jata
 *     hai (asal dwaar [com.aship.jarvis.ai.ScreenAgent]
 *     me hai; ye sirf pehchan hai)। Bina is
 *     pehchan ke bhejna hamesha pushti maangta
 *     hai — bilkul pehle jaisa।
 *  4. [refersToEarlierScreenshot] — baat session
 *     me pehle li gayi tasveer ki taraf ishara
 *     karti ho ("ওই স্ক্রিনশটটা", "আগের ছবিটা"):
 *     share wala auzaar nayi tasveer lene ke
 *     bajay bandhi hui purani hi lega।
 *
 * Saari jaanchein saade contains / shabd-seema
 * wali hain — koi andaza, koi model nahi। "না"
 * jaisi chhoti baat par [isNoMoreAnswer] poori
 * baat ka milan maangta hai (tukdon par nahi),
 * taaki "না, ওটা নয়, এটা করো" jaisi asal baat
 * galti se session-band na ban jaye।
 */
object SessionCommands {

    // ---------------------------------------------------------------
    // 1. Shutdown
    // ---------------------------------------------------------------

    /**
     * Session khatm karne wala pakka wakya —
     * "সিস্টেম শাটডাউন" ke teeno script wale roop
     * + akela "শাটডাউন / shutdown / शटडाउन" (ye
     * shabd aam baat me aate hi nahi, isliye akela
     * bhi kaafi hai)।
     */
    fun isShutdownCommand(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        return SHUTDOWN_MARKERS.any { lower.contains(it) }
    }

    private val SHUTDOWN_MARKERS = listOf(
        "সিস্টেম শাটডাউন", "সিস্টেম শাট ডাউন",
        "শাটডাউন", "শাট ডাউন",
        "system shutdown", "shutdown",
        "सिस्टम शटडाउन", "सिस्टम शट डाउन",
        "शटडाउन", "शट डाउन"
    )

    // ---------------------------------------------------------------
    // 2. "আর কিছু?" ka saaf NA
    // ---------------------------------------------------------------

    /**
     * "আর কিছু করতে হবে?" ke jawab me saaf
     * manaahi / samaapti — poori baat bandhe roop
     * me se ek ho (viram-chinh hatakar), ya kisi
     * bandhe wakya se shuru hokar chhoti hi rahe।
     * Lambe vakya me aaya "না" isme NAHI aata —
     * woh agla hukm bhi ho sakta hai।
     */
    fun isNoMoreAnswer(rawText: String): Boolean {
        val squashed = rawText.lowercase(Locale.ROOT)
            .replace(Regex("[।.,!?,;:—\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (squashed.isEmpty()) return false
        if (squashed in NO_EXACT) return true
        return NO_PREFIXES.any { prefix ->
            squashed == prefix ||
                (squashed.startsWith("$prefix ") &&
                    squashed.split(" ").size <= 5)
        }
    }

    /** Poori baat hi ye ho to saaf NA। */
    private val NO_EXACT = setOf(
        // Bangla
        "না", "আর না", "না আর", "না আর না",
        "ব্যাস", "ব্যস", "শেষ", "হয়ে গেছে", "হয়েছে",
        "আর কিছু না", "কিছু না", "লাগবে না",
        "আর লাগবে না", "আর কিছু লাগবে না",
        "না ধন্যবাদ", "ধন্যবাদ না",
        // Hindi
        "नहीं", "नही", "बस", "हो गया", "बस हो गया",
        "नहीं चाहिए", "और कुछ नहीं", "और नहीं",
        // English
        "no", "nope", "done", "nothing",
        "nothing else", "that's all", "thats all",
        "i'm done", "im done", "no thanks"
    )

    /** Inse shuru hokar chhoti rahe to bhi saaf NA। */
    private val NO_PREFIXES = listOf(
        "না আর", "আর কিছু না", "হয়ে গেছে",
        "और कुछ नहीं", "बस हो गया",
        "that's all", "nothing else"
    )

    // ---------------------------------------------------------------
    // 3. Khud hukm me bhejne ka aadesh
    // ---------------------------------------------------------------

    /**
     * User ka apna hukm SAAF taur par bhejne ko
     * kehta hai kya — teeno bhashaon ke bhejne
     * wale aadesh-roop। Sirf session ke andar ke
     * hukm par bulane wala ise lagata hai; session
     * ke bahar is pehchan ka koi asar nahi (pushti
     * dwaar wahan bilkul pehle jaisa hai)।
     */
    fun isSendInstruction(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (SEND_MARKERS.any { lower.contains(it) }) return true
        // Latin "send" shabd-seema ke saath ("send it",
        // "send করে দাও", "send karo") — "send" kisi
        // bade shabd ka tukda ho to nahi।
        return SEND_WORD_REGEX.containsMatchIn(lower)
    }

    private val SEND_MARKERS = listOf(
        // Bangla (script)
        "সেন্ড করে দাও", "সেন্ড করো", "সেন্ড করে",
        "পাঠিয়ে দাও", "পাঠিয়ে দিও", "পাঠিয়ে দে",
        "পাঠিয়ে", "পাঠাও",
        // Bangla (roman roop — recognizer kabhi ye bhi deta hai)
        "pathiye dao", "pathiye", "pathao",
        // Hindi
        "भेज दो", "भेज दीजिए", "भेज दे", "भेजो",
        // English wakya
        "send it"
    )

    private val SEND_WORD_REGEX = Regex("\\bsend\\b")

    // ---------------------------------------------------------------
    // 4. Pehle wali screenshot ki taraf ishara
    // ---------------------------------------------------------------

    /**
     * Baat session me pehle li gayi tasveer ki
     * taraf ishara karti hai kya — "আগের/ওই/সেই
     * + স্ক্রিনশট/ছবি" wale bandhe jod, aur unke
     * Hindi/English roop। Naya screenshot MAANGNA
     * isme nahi aata (uske liye pakka screenshot
     * hukm apni jagah hai, aur woh hamesha taaza
     * tasveer leta hai) — ye sirf PURANI wali ki
     * pehchan hai।
     */
    fun refersToEarlierScreenshot(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        return EARLIER_SHOT_MARKERS.any { lower.contains(it) }
    }

    private val EARLIER_SHOT_MARKERS = listOf(
        // Bangla
        "আগের স্ক্রিনশট", "ওই স্ক্রিনশট", "সেই স্ক্রিনশট",
        "আগের ছবি", "ওই ছবি", "সেই ছবি",
        "আগের স্ক্রিনশটটা", "ওই স্ক্রিনশটটা", "সেই স্ক্রিনশটটা",
        "আগের ছবিটা", "ওই ছবিটা", "সেই ছবিটা",
        // Hindi
        "पिछला स्क्रीनशॉट", "वह स्क्रीनशॉट", "वही तस्वीर",
        "पिछली तस्वीर", "वह तस्वीर",
        // English
        "that screenshot", "the screenshot",
        "previous screenshot", "earlier screenshot",
        "that picture", "the picture"
    )
}

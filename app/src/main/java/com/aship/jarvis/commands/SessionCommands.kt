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
 *     "send it") ya saaf "koi permission nahi
 *     chahiye, akele hi kar" kehta ho (v66):
 *     usi hukm se chale screen-kaam me Send wala
 *     tap usi ke diye hue hukm se pehle-se-pusht
 *     (pre-confirmed) mana jata hai (asal dwaar
 *     [com.aship.jarvis.ai.ScreenAgent] me hai; ye
 *     sirf pehchan hai)। Bina is pehchan ke
 *     bhejna hamesha pushti maangta hai — bilkul
 *     pehle jaisa।
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
    // 1b. FULL SHUTDOWN (v75)
    // ---------------------------------------------------------------

    /**
     * v75: PURA band — "শাটডাউন / ফুল শাটডাউন" sunte
     * hi Maya pehle "কমপ্লিট" bolkar background
     * sunna (wake service) + khud ka process poori
     * tarah band kar degi। "সিস্টেম শাটডাউন" isme
     * NAHI aata — woh upar wali [isShutdownCommand]
     * wali chup session-ant hi rehta hai (gola
     * gayab, wake sunna chaloo), taaki taana session
     * ka purana bandobast na toote। "বন্ধ" jaisa
     * aam shabd isme bilkul nahi hai — sirf ye
     * pakke shutdown wale shabd hi chalenge।
     */
    fun isFullShutdownCommand(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        // "সিস্টেম শাটডাউন" — chup session-ant, pura band nahi।
        if (lower.contains("সিস্টেম") ||
            lower.contains("system") ||
            lower.contains("सिस्टम")
        ) return false
        return FULL_SHUTDOWN_MARKERS.any { lower.contains(it) }
    }

    private val FULL_SHUTDOWN_MARKERS = listOf(
        // Bangla (script)
        "ফুল শাটডাউন", "ফুল শাট ডাউন",
        "শাটডাউন", "শাট ডাউন",
        // English
        "full shutdown", "shutdown",
        // Hindi
        "फुल शटडाउन", "फुल शट डाउन",
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
     * wale aadesh-roop, ya saaf "koi permission
     * nahi chahiye, akele hi kar" wala aadesh
     * (v66: user ne khud kaha ho "আমার কোনো
     * পারমিশন নেওয়ার প্রয়োজন নেই, কাজটা একা
     * একাই কর" to woh bhi bhejne ka aadesh hi
     * hai — pushti dobara maangna usi hukm ko
     * todna hai)। Bulane wala (wake service ka
     * session ya MainActivity ka saamne wala
     * hukm) ise lagata hai; jhanda sirf usi daud
     * ke SEND tap par asar karta hai — PAY /
     * DELETE / CONFIRM aur banking-rok pehle
     * jaise hi rehte hain।
     */
    fun isSendInstruction(rawText: String): Boolean {
        val lower = rawText.lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        if (SEND_MARKERS.any { lower.contains(it) }) return true
        if (NO_PERMISSION_MARKERS.any { lower.contains(it) }) return true
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
        // v74: sawal wali shakal ("আমি কেন সেন্ড বাটন
        // প্রেস করব তুমি সেন্ড করে দেবে") bhi bhejne
        // ka aadesh hi hai — baatchit wala mastishk
        // is par bahana banata tha ("system security")।
        "সেন্ড বাটন", "সেন্ড বোতাম",
        // Bangla (roman roop — recognizer kabhi ye bhi deta hai)
        "pathiye dao", "pathiye", "pathao",
        // Hindi
        "भेज दो", "भेज दीजिए", "भेज दे", "भेजो",
        // v103: Roman Hindi — recognizer aksar aadesh
        // isi roop me deta hai ("ChatGPT ke message box
        // mein ye likho aur bhejo")। Iske bina woh hukm
        // bhejne wala pehchana hi nahi jata tha, isliye
        // deterministic send kabhi lagta hi nahi tha aur
        // screen agent model + vision se bhejne ki
        // koshish me hi samay khatm kar deta tha
        // (live: 9 kadam, vision 6/15, FAILED samay
        // khatm)। "bhejo" me "likh kar bhejo" / "ye
        // likho aur bhejo" bhi aate hain (contains)।
        "bhej do", "bhej dijiye", "bhej de", "bhejo",
        "bhej dena", "send kar do", "send karo",
        "send kar", "send karde",
        // English wakya
        "send it"
    )

    private val SEND_WORD_REGEX = Regex("\\bsend\\b")

    /**
     * v66: "koi permission nahi chahiye, akele hi
     * kar" wale aadesh — user ne bhejne se PEHLE hi
     * pushti ki zaroorat ko saaf mana kar diya ho।
     * Ye chinh akele bhi [isSendInstruction] ko
     * sach banate hain: jhanda sirf usi daud ke
     * SEND tap ko pehle-se-pusht karta hai, kisi
     * aur kadam ko nahi, isliye aam baat me ye
     * shabd aane se koi khatra nahi।
     */
    private val NO_PERMISSION_MARKERS = listOf(
        // Bangla (script)
        "পারমিশন নেওয়ার প্রয়োজন নেই", "পারমিশন লাগবে না",
        "কোনো পারমিশন নেই", "পারমিশন দরকার নেই",
        "একা একাই কর", "একা একাই", "নিজে নিজেই কর",
        // Bangla (roman roop)
        "eka ekai",
        // Hindi
        "परमिशन की जरूरत नहीं", "अकेले ही कर",
        // English
        "no permission needed", "permission not needed",
        "do it alone", "do it by yourself"
    )

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

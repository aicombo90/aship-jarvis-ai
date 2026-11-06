package com.aship.jarvis.commands

import java.util.Locale

/**
 * v51 (item 2): sunne ka plausibility dwar (gate) — ASR se
 * aaya kachra text hukm bankar aage jaane se pehle yahin
 * rukta hai.
 *
 * Kyun: v50 tak recognizer (system / Vosk / Zipformer) jo
 * bhi text deta tha, woh seedha onCommandHeard tak jata
 * tha — chhoti awaaz, saans, ya Zipformer ki bani-banayi
 * behki baat bhi "command" bankar session ko
 * not-understood par khatm kar deti thi. Maya ke live
 * test (2026-11-03) me yahi sabse badi shikayat thi.
 *
 * Niyam (jaan-boojh kar RAKSHAATMAK / conservative):
 *  1. Khaali text — hamesha reject.
 *  2. Koi pehchan-ne wala command shabd (token) ho to
 *     hamesha accept — chhote pakke hukm ("না", "লাইট",
 *     "বন্ধ করো") kabhi nahi rukte. Sikhaye hue shabd
 *     (taught triggers) bulane wale se [extraAccepted]
 *     bankar aate hain, woh bhi token maane jate hain.
 *  3. Script-mismatch: sirf tab reject jab text CHHOTA
 *     bhi ho. Lambi asal doosri-bhasha wali baat accept
 *     rehti hai — v48 ka vinamra redirect (Maya: "मैं
 *     सिर्फ़ हिंदी समझती हूँ…") usi ke liye hai, usey
 *     yahan khaana galat hota. Ye jaanch sirf
 *     [scriptReliable] wale raaston par lagti hai
 *     (system engine / Zipformer) — Vosk hamesha
 *     Devanagari lipyantaran deta hai, wahan script
 *     jaanch ka koi matlab nahi.
 *  4. Bahut chhota AUR bina token — reject (sirf
 *     awaaz/shor samjho).
 *  5. Zipformer ka bahut tez decode (<600 ms) AUR bina
 *     token — reject (chhoti awaaz par bani hui lambi
 *     behki baat ki pehchan yahi hai).
 *  6. Baaki sab accept — lambi baat ya to asal hukm hai
 *     ya brain ka sawal; dono ka faisla aage hota hai.
 *
 * Reject ka matlab bulane wale ke liye: MISS — bina
 * boli-retry kharch kiye phir suno (echo-restart wala
 * raasta, seema bandhi hui).
 *
 * Ye class shudh Kotlin hai (koi Android nahi) taaki
 * JVM par test ho sake.
 */
object HearingGate {

    data class Decision(val accepted: Boolean, val reason: String)

    private const val ZIPFORMER_FAST_DECODE_MS = 600L

    fun evaluate(
        text: String,
        persona: Persona?,
        scriptReliable: Boolean,
        decodeMs: Long? = null,
        extraAccepted: Collection<String> = emptyList()
    ): Decision {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Decision(false, "blank")
        if (hasCommandToken(trimmed, extraAccepted)) {
            return Decision(true, "token")
        }
        // Token nahi mila — ab sirf "kachra" wale roop bachte hain.
        if (decodeMs != null && decodeMs < ZIPFORMER_FAST_DECODE_MS) {
            return Decision(false, "fast-decode")
        }
        if (isShort(trimmed)) {
            if (scriptReliable && persona != null &&
                scriptMismatched(trimmed, persona)
            ) {
                return Decision(false, "script-mismatch")
            }
            return Decision(false, "short-noise")
        }
        return Decision(true, "long")
    }

    /** Bahut chhota = akshar <=3, ya <=2 shabd aur kul akshar <=10. */
    private fun isShort(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters <= 3) return true
        val tokens = tokensOf(text)
        return tokens.size <= 2 && letters <= 10
    }

    private fun tokensOf(text: String): List<String> =
        text.lowercase(Locale.ROOT)
            .split(Regex("""[\s,.\u0964?!\uFF1F;:'"()\-]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Script ka bhaaRi pan: Maya (Hindi) ke turn me
     * Bangla-lipi wali baat, ya Tara (Bangla) ke turn me
     * Devanagari-bhaari baat.
     */
    private fun scriptMismatched(text: String, persona: Persona): Boolean {
        var bengali = 0
        var devanagari = 0
        var latin = 0
        for (ch in text) {
            when (ch) {
                in '\u0980'..'\u09FF' -> bengali++
                in '\u0900'..'\u097F' -> devanagari++
                in 'a'..'z', in 'A'..'Z' -> latin++
            }
        }
        return when (persona) {
            Persona.MAYA -> bengali > 0 && bengali >= devanagari && bengali >= latin
            Persona.TARA -> devanagari > 0 && devanagari > bengali && devanagari >= latin
        }
    }

    /**
     * Pehchan-ne wala command shabd hai kya — stem wale
     * shabd substring se, bahut chhote pakke shabd poore
     * token se, aur sikhaye hue shabd token-mel se.
     */
    fun hasCommandToken(
        text: String,
        extraAccepted: Collection<String> = emptyList()
    ): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        for (stem in TOKEN_STEMS) {
            if (lower.contains(stem)) return true
        }
        val tokens = tokensOf(text)
        val tokenSet = tokens.toSet()
        for (word in WHOLE_TOKENS) {
            if (word in tokenSet) return true
        }
        if (extraAccepted.isNotEmpty()) {
            val extraTokens = extraAccepted
                .flatMap { tokensOf(it) }
                .toSet()
            if (tokens.any { it in extraTokens }) return true
        }
        return false
    }

    /** Poore token se milne wale chhote pakke shabd (substring se galat-mel ka dar). */
    private val WHOLE_TOKENS = setOf(
        "না", "নাহ", "হ্যাঁ", "হাঁ", "না।",
        "yes", "no", "ok", "ওকে", "ঠিক",
        "नहीं", "हाँ", "हां", "ठीक"
    )

    /**
     * Stem wale pehchan shabd — teeno lipiyon me. Ye
     * jaan-boojh kar bade rakhe hain: gate ka kaam sirf
     * shor rokna hai, asal hukm kabhi nahi.
     */
    private val TOKEN_STEMS = listOf(
        // Latin
        "torch", "light", "scroll", "screenshot", "screen", "data",
        "alarm", "timer", "time", "date", "call", "play", "song",
        "music", "open", "search", "google", "youtube", "whatsapp",
        "telegram", "facebook", "instagram", "camera", "volume",
        "battery", "weather", "news", "message", "share", "send",
        "photo", "video", "shutdown", "stop", "help", "name",
        "jarvis", "maya", "tara",
        // Bangla
        "লাইট", "টর্চ", "স্ক্রল", "স্ক্রিনশট", "ডাটা", "ডেটা",
        "মোবাইল", "অ্যালার্ম", "টাইমার", "সময়", "তারিখ", "কল",
        "ফোন", "গান", "চালাও", "চালু", "জ্বালাও", "খোলো", "খোল",
        "ইউটিউব", "হোয়াটসঅ্যাপ", "টেলিগ্রাম", "ফেসবুক",
        "ক্যামেরা", "ভলিউম", "ব্যাটারি", "আবহাওয়া", "বৃষ্টি",
        "খবর", "মেসেজ", "শেয়ার", "পাঠাও", "পাঠিয়ে", "সেন্ড", "ছবি", "শাটডাউন",
        "বন্ধ", "বলো", "বলুন", "দেখাও", "নাম", "মায়া", "তারা",
        "জার্ভিস", "শোনো", "করো", "কেমন", "আছো", "কোথায়",
        "কবে", "আজ", "কাল",
        // Devanagari
        "लाइट", "टॉर्च", "टार्च", "स्क्रॉल", "स्क्रोल",
        "स्क्रीनशॉट", "डेटा", "डाटा", "मोबाइल", "अलार्म",
        "टाइमर", "समय", "तारीख", "कॉल", "फोन", "गाना",
        "चलाओ", "चालू", "जलाओ", "खोलो", "खोल", "यूट्यूब",
        "व्हाट्सएप", "टेलीग्राम", "फेसबुक", "कैमरा", "वॉल्यूम",
        "बैटरी", "मौसम", "बारिश", "खबर", "मैसेज", "शेयर",
        "भेजो", "फोटो", "शटडाउन", "बंद", "बोलो", "बताओ",
        "दिखाओ", "नाम", "माया", "तारा", "जार्विस", "सुनो",
        "करो", "कैसा", "कहाँ", "कब", "आज", "कल"
    )
}

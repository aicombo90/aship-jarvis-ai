package com.aship.jarvis.commands

import java.util.Locale

/**
 * v93: MAYA KI BHASHAEN — ek hi jagah, poori suchi.
 *
 * User ka hukm (2026-11-10): "মায়ার মধ্যে আরও বড় বড়
 * ভাষা অ্যাড করো" — Maya me aur badi-badi bhashaen.
 * Maya = Hindi aur Tara = Bangla waisi ki waisi hain
 * (Persona dekho — unka bandhan nahi badla); ye suchi
 * unke AAGE ki hai, unki jagah nahi.
 *
 * Har bhasha SIRF system wale raaston se chalti hai —
 * koi Vosk model download NAHI (user ka mobile data
 * limited hai; har model ~40-50 MB hota):
 *  - sunna: Android SpeechRecognizer, isi tag se
 *    (SystemSpeechEngine + CascadeOrder),
 *  - samajhna/jawab: Gemini online raasta (AgentBrain
 *    ka LANGUAGE OVERRIDE — Gemini pehle se hi user
 *    ki bhasha me jawab deta hai),
 *  - bolna: Android system TTS, isi tag se
 *    (TtsSpeaker — voice na mile to Hindi/English
 *    par imaandaar fallback, crash nahi).
 * Offline Vosk Hindi model sirf Hindi ke liye fallback
 * rehta hai, pehle jaisa — aur koi offline model nahi.
 *
 * [spokenLang] sirf un bhashaon ke liye hai jinki
 * bandhi hui (canned) jawab-linein pehle se hain
 * (Bangla/Hindi/English — PlayIntent.SpokenLang);
 * baaki bhashaon ke liye null hai — unke jawab
 * Gemini override se aate hain, aur bandhi linein
 * English wali chalti hain.
 */
data class MayaLanguage(
    /** BCP 47 tag — recognizer aur TTS, dono isi se. */
    val tag: String,
    /** Apni lipi me naam — card/dialog me dikhta hai. */
    val nativeName: String,
    /** English me naam — voice-matching aur list me. */
    val englishName: String,
    /** Gemini ko batane wala naam (lipi sahit). */
    val geminiName: String,
    /** Bandhi jawab-linein wali bhasha ho to wahi, warna null. */
    val spokenLang: PlayIntent.SpokenLang?,
    /** Is bhasha ko awaaz se pakadne wale shabd (chhote akshar). */
    val matchWords: List<String>,
    /** Nayi bhasha me badalne ki ek-chhoti pushti-line. */
    val confirmLine: String
)

object MayaLanguages {

    val ALL: List<MayaLanguage> = listOf(
        MayaLanguage(
            "bn-IN", "বাংলা", "Bengali", "Bengali in Bengali script",
            PlayIntent.SpokenLang.BENGALI,
            listOf("বাংলা", "bengali", "bangla"),
            "ঠিক আছে, এখন থেকে বাংলায় কথা বলবো।"
        ),
        MayaLanguage(
            "hi-IN", "हिन्दी", "Hindi", "Hindi in Devanagari script",
            PlayIntent.SpokenLang.HINDI,
            listOf("हिन्दी", "हिंदी", "hindi"),
            "ठीक है, अब से हिंदी में बात करूँगी।"
        ),
        MayaLanguage(
            "en-IN", "English", "English", "English in Latin script",
            PlayIntent.SpokenLang.ENGLISH,
            listOf("english", "ইংরেজি", "ইংলিশ"),
            "Got it — I'll speak in English from now on."
        ),
        MayaLanguage(
            "ur-IN", "اردو", "Urdu", "Urdu in Urdu (Arabic) script",
            null,
            listOf("urdu", "উর্দু", "اردو"),
            "ٹھیک ہے، اب سے اردو میں بات کروں گی۔"
        ),
        MayaLanguage(
            "ta-IN", "தமிழ்", "Tamil", "Tamil in Tamil script",
            null,
            listOf("tamil", "তামিল", "தமிழ்"),
            "சரி, இனிமேல் தமிழில் பேசுவேன்."
        ),
        MayaLanguage(
            "te-IN", "తెలుగు", "Telugu", "Telugu in Telugu script",
            null,
            listOf("telugu", "তেলুগু", "తెలుగు"),
            "సరే, ఇకపై తెలుగులో మాట్లాడతాను."
        ),
        MayaLanguage(
            "mr-IN", "मराठी", "Marathi", "Marathi in Devanagari script",
            null,
            listOf("marathi", "মারাঠি", "मराठी"),
            "ठीक आहे, आतापासून मराठीत बोलेन."
        ),
        MayaLanguage(
            "gu-IN", "ગુજરાતી", "Gujarati", "Gujarati in Gujarati script",
            null,
            listOf("gujarati", "গুজরাটি", "ગુજરાતી"),
            "સરસ, હવેથી ગુજરાતીમાં બોલીશ."
        ),
        MayaLanguage(
            "pa-IN", "ਪੰਜਾਬੀ", "Punjabi", "Punjabi in Gurmukhi script",
            null,
            listOf("punjabi", "পাঞ্জাবি", "ਪੰਜਾਬੀ"),
            "ਠੀਕ ਹੈ, ਹੁਣ ਤੋਂ ਪੰਜਾਬੀ ਵਿੱਚ ਬੋਲਾਂਗੀ।"
        ),
        MayaLanguage(
            "ml-IN", "മലയാളം", "Malayalam", "Malayalam in Malayalam script",
            null,
            listOf("malayalam", "মালায়ালাম", "മലയാളം"),
            "ശരി, ഇനി മുതൽ മലയാളത്തിൽ സംസാരിക്കാം."
        ),
        MayaLanguage(
            "kn-IN", "ಕನ್ನಡ", "Kannada", "Kannada in Kannada script",
            null,
            listOf("kannada", "কন্নড়", "ಕನ್ನಡ"),
            "ಸರಿ, ಇನ್ನು ಮುಂದೆ ಕನ್ನಡದಲ್ಲಿ ಮಾತನಾಡುತ್ತೇನೆ."
        ),
        MayaLanguage(
            "or-IN", "ଓଡ଼ିଆ", "Odia", "Odia in Odia script",
            null,
            listOf("odia", "oriya", "ওড়িয়া", "ଓଡ଼ିଆ"),
            "ଠିକ ଅଛି, ଏବେଠାରୁ ଓଡ଼ିଆରେ କଥା ହେବି।"
        ),
        MayaLanguage(
            "as-IN", "অসমীয়া", "Assamese", "Assamese in Bengali-Assamese script",
            null,
            listOf("assamese", "অসমীয়া"),
            "ঠিক আছে, এতিয়াৰ পৰা অসমীয়াত কথা ক'ম।"
        ),
        MayaLanguage(
            "es-ES", "español", "Spanish", "Spanish in Latin script",
            null,
            listOf("spanish", "স্প্যানিশ", "español"),
            "De acuerdo, a partir de ahora hablaré en español."
        ),
        MayaLanguage(
            "fr-FR", "français", "French", "French in Latin script",
            null,
            listOf("french", "ফরাসি", "français"),
            "D'accord, je parlerai en français à partir de maintenant."
        ),
        MayaLanguage(
            "ar-SA", "العربية", "Arabic", "Arabic in Arabic script",
            null,
            listOf("arabic", "আরবি", "العربية"),
            "حسنًا، سأتحدث بالعربية من الآن فصاعدًا."
        ),
        MayaLanguage(
            "id-ID", "Bahasa Indonesia", "Indonesian", "Indonesian in Latin script",
            null,
            listOf("indonesian", "ইন্দোনেশীয়", "bahasa indonesia"),
            "Baik, mulai sekarang saya akan berbicara dalam Bahasa Indonesia."
        ),
        MayaLanguage(
            "pt-BR", "português", "Portuguese", "Portuguese in Latin script",
            null,
            listOf("portuguese", "পর্তুগিজ", "português"),
            "Certo, a partir de agora vou falar em português."
        ),
        MayaLanguage(
            "ru-RU", "русский", "Russian", "Russian in Cyrillic script",
            null,
            listOf("russian", "রাশিয়ান", "русский"),
            "Хорошо, с этого момента буду говорить по-русски."
        )
    )

    /** Tag se bhasha — pura tag na mile to bhasha-hissa ("en" -> en-IN). */
    fun fromTag(tag: String?): MayaLanguage? {
        val clean = tag?.trim().orEmpty()
        if (clean.isEmpty()) return null
        ALL.firstOrNull { it.tag.equals(clean, ignoreCase = true) }?.let { return it }
        val langPart = clean.substringBefore('-').lowercase(Locale.ROOT)
        return ALL.firstOrNull {
            it.tag.substringBefore('-').lowercase(Locale.ROOT) == langPart
        }
    }

    /** Card/button par dikhne wala naam — anjaan tag par tag hi. */
    fun displayName(tag: String?): String =
        fromTag(tag)?.nativeName ?: tag?.trim().orEmpty().ifEmpty { "বাংলা" }

    /** Maya/Tara ki pakki bhashaen — sirf in par shaksiyat ka bandhan chalta hai. */
    fun isPersonaLanguage(tag: String?): Boolean {
        val clean = tag?.trim().orEmpty()
        return clean.equals("hi-IN", ignoreCase = true) ||
            clean.equals("bn-IN", ignoreCase = true)
    }

    /** Bandhi jawab-bhasha se uska tag (Maya/Tara wale raaste ke liye). */
    fun tagForSpoken(spoken: PlayIntent.SpokenLang): String = when (spoken) {
        PlayIntent.SpokenLang.BENGALI -> "bn-IN"
        PlayIntent.SpokenLang.HINDI -> "hi-IN"
        PlayIntent.SpokenLang.ENGLISH -> "en-IN"
    }

    /**
     * Is pal ki asal jawab-bhasha.
     *
     * Shaksiyat ka bandhan SIRF tab jeetta hai jab chuni
     * hui bhasha Hindi/Bangla ho — Maya ko bulaya to
     * Hindi, Tara ko bulaya to Bangla (v48 wala vyavhaar,
     * bilkul pehle jaisa)। Chuni hui bhasha koi AUR ho
     * (Tamil, English, Spanish...) to wahi jeetti hai —
     * naam sirf jagane wala shabd rehta hai, bhasha
     * malik ki pasand ki hoti hai। Bina shaksiyat ke
     * (saamne wali screen) chuni hui bhasha hi chalti hai।
     */
    fun effectiveSessionTag(persona: Persona?, selectedTag: String): String {
        if (isPersonaLanguage(selectedTag) && persona != null) {
            return tagForSpoken(persona.spokenLang())
        }
        return fromTag(selectedTag)?.tag ?: "bn-IN"
    }

    /**
     * TTS ko dene wala tag — SIRF nayi bhashaon ke liye।
     * Bangla/Hindi/English ke jawab script-pehchan se
     * hi sahi awaaz pa lete hain; Latin-lipi wale naye
     * jawabon (Spanish, French...) ko script se pehchana
     * hi nahi ja sakta, isliye wahan saaf tag dena
     * zaroori hai। null = purana script wala raasta।
     */
    fun ttsTagFor(tag: String?): String? {
        val lang = fromTag(tag) ?: return null
        return if (lang.spokenLang == null) lang.tag else null
    }
}

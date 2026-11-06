package com.aship.jarvis.commands

import java.util.Locale

/**
 * v52: SEND BUTTON WALA HUKM — user ne kaha: Jarvis
 * ko khud apna SEND button dabana chahiye. Screenshot
 * me Maya ne "safety rules" bolkar mana kar diya tha —
 * woh manaahi online mastishk (Gemini) ki aam
 * hichkichahat thi, app ki koi rok nahi: ye button
 * app ka APNA hai, iska dabana koi bahari kaam nahi.
 *
 * Do mezbaan, ek hi object ([TeachCommands] /
 * [RuleCommands] ki tarah):
 *  1. SAAMNE WALI SCREEN ([MainActivity]): "সেন্ড
 *     করো" / "কমান্ড পাঠাও" bolte hi box me jo
 *     likha hai wohi bheja jata hai — button
 *     dabane wala hi raasta ([MainActivity]
 *     ka submitTypedCommand), isliye bilkul wahi
 *     hota hai jo button dabane par hota hai.
 *     Box khaali ho to imaandaar jawab, aur kuch
 *     nahi. Baat mastishk (online) tak jayegi hi
 *     nahi — "safety rules" wala manaahi ab
 *     kabhi aayega nahi।
 *  2. BACKGROUND WAKE SERVICE: wahan koi box
 *     hota hi nahi — isliye sirf imaandaar jawab
 *     (screen kholo, likho, phir "সেন্ড করো"
 *     bolo)। Kuch invent karke bheja nahi jata।
 *
 * TAKRAAV se bachav: ye object sirf WAHI roop
 * pakadta hai jo saaf send wale hain — poori
 * baat ek send hukm ho (aage-peeche halka
 * "এবার"/"তো" chalta hai)। "সেন্ড করো না"
 * (manaahi) kabhi nahi pakda jata — peeche
 * wala "না" hataya hi nahi jata।
 */
object SendButtonCommands {

    /** Baat send button dabane wali ho to true, warna false। Kabhi exception nahi। */
    fun isSendCommand(rawText: String): Boolean {
        return try {
            val norm = normalize(rawText) ?: return false
            if (SEND_PHRASES.contains(norm)) return true
            SEND_PHRASES.contains(stripFillers(norm))
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Baat ko ek saaf roop me lao: aas-paas ki khaali
     * jagah aur peeche wala viram hatao, chhota karo।
     * Khaali bache to null।
     */
    private fun normalize(rawText: String): String? {
        val text = rawText.trim()
            .trimEnd('।', '.', '!', '?', ',', ' ', '—', '-')
            .trim()
            .lowercase(Locale.ROOT)
        return if (text.isEmpty()) null else text
    }

    /**
     * Aage-peeche ke halke bhara shabd hatao —
     * "এবার সেন্ড করো" / "সেন্ড করো তো" bhi
     * wahi send hukm hai। Peeche wala "না"
     * kabhi nahi hataya jata ("সেন্ড করো না"
     * manaahi hai, hukm nahi)।
     */
    private fun stripFillers(norm: String): String {
        var text = norm
        var changed: Boolean
        do {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (text.startsWith("$filler ")) {
                    text = text.substring(filler.length).trimStart()
                    changed = true
                    break
                }
            }
        } while (changed)
        for (filler in TRAILING_FILLERS) {
            if (text.endsWith(" $filler")) {
                text = text.substring(0, text.length - filler.length - 1)
                    .trimEnd()
                break
            }
        }
        return text
    }

    /** Aage wale halke shabd — ye hatakar bhi hukm wahi rehta hai। */
    private val LEADING_FILLERS = listOf(
        "এবার", "তাহলে", "এখন", "আচ্ছা", "আরে",
        "अब", "तो", "अच्छा", "अरे",
        "now", "ok", "okay", "please"
    )

    /** Peeche wala halka shabd — sirf "তো"/"तो" (manaahi wala "না" kabhi nahi)। */
    private val TRAILING_FILLERS = listOf("তো", "तो")

    /** Send button dabane wale saaf roop — teeno bhashaon me। */
    private val SEND_PHRASES = setOf(
        // Bangla
        "সেন্ড করো", "সেন্ড কর", "সেন্ড করে দাও", "সেন্ড করে দে",
        "সেন্ড বাটন টিপো", "সেন্ড বাটনটা টিপো",
        "সেন্ড বাটন চাপো", "সেন্ড বাটনটা চাপো",
        "সেন্ড বাটন দাবাও", "সেন্ড বাটনটা দাবাও",
        "কমান্ড পাঠাও", "কমান্ডটা পাঠাও",
        "কমান্ড পাঠিয়ে দাও", "কমান্ড সেন্ড করো",
        "কমান্ড সেন্ড করে দাও", "পাঠিয়ে দাও", "পাঠিয়ে দে",
        // Hindi
        "सेंड करो", "सेंड कर दो", "कमांड भेजो", "भेज दो",
        "सेंड बटन दबाओ", "सेंड बटन दबा दो", "भेज दीजिए",
        // English
        "send karo", "send kar do",
        "press send", "press the send button",
        "tap send", "tap the send button",
        "send it", "send the command", "send this command",
        "send button dabao"
    )

    // ---------------------------------------------------------------
    // Bole jaane wale jawab
    // ---------------------------------------------------------------

    /** Box khaali ho to imaandaar jawab — kuch bheja hi nahi। */
    fun emptyReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "বক্সে কিছু লেখা নেই — পাঠানোর মতো কিছু নেই।"
        PlayIntent.SpokenLang.HINDI ->
            "बॉक्स में कुछ लिखा नहीं है — भेजने के लिए कुछ नहीं है।"
        PlayIntent.SpokenLang.ENGLISH ->
            "The box is empty — there's nothing to send."
    }

    /** Background me koi box nahi — screen kholne ko bolo, kuch invent mat karo। */
    fun backgroundReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "সেন্ড বাটন শুধু অ্যাপের স্ক্রিন খোলা থাকলে কাজ করে — " +
                "এখন স্ক্রিন খোলা নেই, তাই পাঠাতে পারলাম না। " +
                "অ্যাপ খুলে লিখে 'সেন্ড করো' বলো।"
        PlayIntent.SpokenLang.HINDI ->
            "सेंड बटन सिर्फ तब काम करता है जब ऐप की स्क्रीन " +
                "खुली हो — अभी स्क्रीन खुली नहीं है, इसलिए भेज " +
                "नहीं पाई। ऐप खोलकर लिखिए, फिर 'सेंड करो' कहिए।"
        PlayIntent.SpokenLang.ENGLISH ->
            "The send button only works when the app screen is " +
                "open — it's not open right now, so I couldn't " +
                "send it. Open the app, type it, then say 'send'."
    }
}

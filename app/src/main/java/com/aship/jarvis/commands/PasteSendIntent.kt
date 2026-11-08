package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.AgentTools
import java.util.Locale

/**
 * v67: PASTE / SEND-TEXT WALE KAAM KA PAKKA RAASTA — [UrlIntent] wala hi pattern.
 *
 * Pakda gaya asal bug (2026-11-07, live test): user ne kaha "এই লেখাটা
 * চেট জিবিতে পেস্ট করো, পেস্ট করে ওকে সেন্ড করে দাও" — aur Maya ne
 * seedha mana kar diya: "চ্যাটজিপিটি সরাসরি খোলার বা সেখানে টেক্সট
 * পেস্ট করার কোনো টুল আমার কাছে নেই" / "ক্লিপবোর্ডে কপি বা পেস্ট
 * করার সরাসরি কোনো টুল আমার নেই।" Ye mana code me likhi koi line
 * NAHI hai — model ka apna "final" jawab hai: [AgentBrain] ki
 * instruction me "paste" / "clipboard" shabd kahin hai hi nahi,
 * isliye Gemini "paste" ko kisi auzaar se jod hi nahi paya.
 * (Usse pehle wale "সেন্ড করে দাও" wale hukm par woh screen_task
 * par gaya tha aur type karke dikha bhi diya tha — sirf "পেস্ট"
 * shabd aate hi raasta badal gaya।)
 *
 * Isliye ab niyam pakka hai — baat me kisi app ka naam ho AUR
 * paste / send-text ka iraada ho, to baat seedha screen_task
 * auzaar ko jayegi, user ke apne shabdon me, bilkul jaisi suni
 * gayi (paste karne wala text akshar-akshar waisa hi; yahan
 * koi andaza-marammat NAHI hoti)। Baatchit wala mastishk is
 * raaste par aata hi nahi — har baar ek hi vyavhaar, chahe
 * user "পেস্ট করো" bole ya "সেন্ড করে দাও"।
 *
 * Kram: ye jaanch [UrlIntent] ke BAAD lagti hai — link wala
 * iraada (UrlIntent) jeetta rehta hai, bilkul pehle jaisa.
 * Dono dispatch jagah (saamne wali screen + background wake
 * service) yahi object bulati hain।
 */
object PasteSendIntent {

    /**
     * Nishana app — ChatGPT / Gemini / WhatsApp / Telegram /
     * Messenger / Facebook / Instagram, recognizer ke aam
     * roopon ke saath ("চেট জিবি", "চ্যাট জিবি")।
     */
    private val APP_MARKERS = listOf(
        // ChatGPT
        "চ্যাটজিপিটি", "চেট জিবি", "চেটজিবি", "চ্যাট জিবি",
        "চ্যাট জিপিটি", "chatgpt", "chat gpt", "chat-gpt",
        // Gemini
        "জেমিনি", "জিমিনি", "gemini",
        // WhatsApp
        "হোয়াটসঅ্যাপ", "হোয়াটসাপ", "whatsapp",
        // Telegram
        "টেলিগ্রাম", "telegram",
        // Messenger / Facebook / Instagram
        "মেসেঞ্জার", "messenger",
        "ফেসবুক", "facebook",
        "ইনস্টাগ্রাম", "instagram"
    )

    /**
     * Paste wale chinh — "পেস্ট" shabd aate hi ye raasta
     * pakka hai (bhejne ka zikr ho ya na ho)।
     */
    private val PASTE_MARKERS = listOf(
        // Bangla (script)
        "পেস্ট", "কপি করে পেস্ট",
        // Bangla (roman roop — recognizer kabhi ye bhi deta hai)
        "paste koro", "paste kore", "paste",
        // Hindi
        "पेस्ट", "कॉपी करके पेस्ट"
    )

    /**
     * Bhejne wale chinh — ye akele kaafi nahi: saath me
     * [TEXT_MARKERS] me se koi likhai ka ishara bhi
     * chahiye (warna "মাকে WhatsApp-এ পাঠিয়ে দাও" jaisi
     * aam baat bhi yahan aa jayegi — woh whatsapp_message
     * wale auzaar ka kaam hai, is raaste ka nahi)।
     */
    private val SEND_MARKERS = listOf(
        // Bangla (script)
        "সেন্ড করে দাও", "সেন্ড করো", "সেন্ড করে",
        "পাঠিয়ে দাও", "পাঠিয়ে দিও", "পাঠিয়ে দে",
        "পাঠিয়ে", "পাঠাও",
        // Bangla (roman roop)
        "pathiye dao", "pathiye", "pathao",
        "send kore dao", "send koro",
        // Hindi
        "भेज दो", "भेज दीजिए", "भेज दे", "भेजो",
        // English wakya
        "send this text", "send this", "send it"
    )

    /** Bheji jaane wali likhai ka ishara — "এই লেখাটা" / "এটা" / "text"। */
    private val TEXT_MARKERS = listOf(
        "লেখা", "টেক্সট", "text", "মেসেজ", "এটা", "এই"
    )

    /** Latin "send" shabd-seema ke saath ("send করো")। */
    private val SEND_WORD_REGEX = Regex("\\bsend\\b")

    /** Ye baat paste / send-text wala screen-kaam hai kya (app + iraada dono)? */
    fun isPasteSendTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        if (!APP_MARKERS.any { lower.contains(it) }) return false
        // Paste: app + paste shabd hi kaafi hai।
        if (PASTE_MARKERS.any { lower.contains(it) }) return true
        // Send: app + bhejne ka verb + likhai ka ishara, teeno chahiye।
        val sendVerb = SEND_MARKERS.any { lower.contains(it) } ||
            SEND_WORD_REGEX.containsMatchIn(lower)
        if (!sendVerb) return false
        return TEXT_MARKERS.any { lower.contains(it) }
    }

    /** Kaam ka text — user ki baat bilkul waisi ki waisi (kata-trim karke)। */
    fun extractPasteSendTask(rawText: String): String? =
        if (isPasteSendTask(rawText)) rawText.trim() else null

    /**
     * v74: sawal wali shakal me bhejne ka hukm — "তুমি
     * সেন্ড করে দেবে" / "আমি কেন সেন্ড বাটন প্রেস করব"।
     * Na app ka naam hai, na bhejne wala text — isliye
     * [isPasteSendTask] ise nahi pakadta aur baat
     * baatchit wale mastishk tak jaakar wahan bahana
     * banti thi ("system security")। Ab ye bhi pakka
     * screen_task hai: jo app khula hai, usi me type
     * hua text bhejna — deterministic send wala raasta।
     */
    private val QUESTION_SEND_MARKERS = listOf(
        // Bangla (script)
        "তুমি সেন্ড করে দেবে", "তুমি সেন্ড করবে",
        "তুমি সেন্ড করে দাও", "তুমি সেন্ড করো",
        "আমি কেন সেন্ড বাটন প্রেস করব",
        "সেন্ড বাটন প্রেস করব", "সেন্ড বাটন প্রেস করো",
        "তুমি পাঠিয়ে দেবে", "তুমি পাঠিয়ে দাও",
        // Bangla (roman roop)
        "tumi send kore debe", "tumi send korbe",
        "tumi send koro",
        // Hindi
        "तुम सेंड कर दो", "तुम सेंड करो",
        "तुम भेज दो"
    )

    /** Sawal wali shakal me bhejne ka hukm hai kya? */
    fun isQuestionFormSend(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return QUESTION_SEND_MARKERS.any { lower.contains(it) }
    }

    /**
     * Sawal wale hukm ka screen-kaam — khule hue app me
     * type hua text bhejna (kaam user ke shabdon me
     * nahi, kyunki uske shabdon me app/text hai hi nahi;
     * deterministic send khud khana + batan dhoondhta hai)।
     */
    fun extractQuestionSendTask(rawText: String): String? =
        if (isQuestionFormSend(rawText)) QUESTION_SEND_TASK else null

    private const val QUESTION_SEND_TASK =
        "এখন যে চ্যাট অ্যাপটা খোলা আছে, তার ইনপুট বক্সে " +
            "টাইপ করা লেখাটা সেন্ড করো — সেন্ড বাটন চাপো।"

    /**
     * Seedhe chalaye gaye screen_task ka bola jaane
     * wala saar — hamesha sachha, user ki bhasha me:
     *  - screen agent ne apna saar khud diya ho
     *    (COMPLETED / model ke roke wala roop, uske
     *    apne shabdon me bandha hota hai) to wohi;
     *  - banking/payment wali suraksha-rok ho to
     *    uski saaf wajah;
     *  - warna natije (ok/fail) ki bandhi imaandaar
     *    line — ok wali line jaan-boojh kar tatasth
     *    hai ("কাজটা হয়ে গেছে"), kyunki paste-only
     *    kaam me "পাঠিয়ে দিয়েছি" jhootha daava hoga।
     */
    fun spokenSummary(
        result: AgentTools.ToolResult,
        lang: PlayIntent.SpokenLang
    ): String {
        UrlIntent.extractOwnSummary(result.text)?.let { return it }
        if (result.text.contains("banking/payment", ignoreCase = true)) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "এটা ব্যাংকিং/পেমেন্ট অ্যাপ — তোমার সুরক্ষার জন্য " +
                        "এর ভিতরে আমি কাজ করি না।"
                PlayIntent.SpokenLang.HINDI ->
                    "यह बैंकिंग/पेमेंट ऐप है — आपकी सुरक्षा के लिए मैं " +
                        "इसके अंदर काम नहीं करती।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "That is a banking/payment app — I do not work " +
                        "inside those, for your safety."
            }
        }
        return if (result.ok) {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "কাজটা হয়ে গেছে।"
                PlayIntent.SpokenLang.HINDI -> "काम हो गया है।"
                PlayIntent.SpokenLang.ENGLISH -> "Done — the task is complete."
            }
        } else {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "পাঠাতে পারলাম না — লেখাটা বক্সে বসেনি বা সেন্ড হয়নি।"
                PlayIntent.SpokenLang.HINDI ->
                    "भेज नहीं पाई — लिखा हुआ बॉक्स में नहीं बैठा या सेंड नहीं हुआ।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I couldn't send it — the text didn't go into " +
                        "the box or didn't send."
            }
        }
    }

    /** Seedhi screen_task daud ki report — "jarvis_diag" ki "last_agent_run" par। */
    fun saveRouteDiag(context: Context, ok: Boolean) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "steps: screen_task(paste-route)=" + if (ok) "OK" else "FAIL"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }
}

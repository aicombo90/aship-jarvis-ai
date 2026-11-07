package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.AgentTools
import java.util.Locale

/**
 * v48: LINK WALE KAAM KA PAKKA RAASTA — URL detector.
 *
 * Live test (2026-11-02) me pakda gaya asal bug: user
 * ne poora GitHub URL bolkar kaha "browser me kholo",
 * aur baat seedha baatchit wale mastishk ke paas gayi —
 * usne jawab me baat bana di, screen chali hi nahi।
 * Link wala kaam baatchit nahi hai: woh screen_task
 * hai. Isliye ab niyam pakka hai — baat me URL ho
 * AUR kholne/browser/jaane ka iraada ho, to baat
 * seedha screen_task auzaar ko jayegi, user ke apne
 * shabdon me, bilkul jaisi suni gayi (URL akshar-
 * akshar waisa hi; recognizer ne use pehle hi
 * bigada ho to jo suna wahi jayega — yahan koi
 * andaza-marammat NAHI hoti; Chrome playbook ka
 * wait_for/search wala raasta usi par chalega)।
 *
 * Kram: ye jaanch PlayIntent ke BAAD lagti hai —
 * gaana/video wala iraada (PlayIntent) jeetta rehta
 * hai, bilkul pehle jaisa. Dono dispatch jagah
 * (saamne wali screen + background wake service)
 * yahi object bulati hain।
 */
object UrlIntent {

    /**
     * URL ki pehchan: http(s):// ya www. se shuru
     * roop, ya jaana-pehchana TLD wala khula domain
     * (github.com/... , kuch.gov.in aadi), raaste
     * (path) ke saath ho to woh bhi saath।
     */
    private val URL_REGEX = Regex(
        "(https?://|www\\.)\\S+" +
            "|\\b[a-z0-9][a-z0-9-]*(\\.[a-z0-9-]+)*" +
            "\\.(gov\\.in|co\\.in|com|in|org|net|io)(/\\S*)?",
        RegexOption.IGNORE_CASE
    )

    /**
     * Kholne / browser / jaane ke iraade ke chinh —
     * Bangla, Hindi, English teeno me. "খোল"/"খুল"
     * wale up-roop jaan-boojh kar hain: kholo/khule
     * sab unse dhak jate hain।
     */
    private val NAV_INTENT_MARKERS = listOf(
        // Bangla
        "খোলো", "খুলে", "খোল", "খুল",
        "ব্রাউজারে", "ব্রাউজার", "যাও", "যেতে",
        // Hindi
        "खोलो", "खोल", "खुल",
        "ब्राउज़र", "ब्राउजर", "जाओ",
        // English
        "open", "browser", "navigate", "visit", "go to"
    )

    /** Ye baat link wala screen-kaam hai kya (URL + iraada dono)? */
    fun isUrlTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        if (!URL_REGEX.containsMatchIn(rawText)) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return NAV_INTENT_MARKERS.any { lower.contains(it) }
    }

    /** Kaam ka text — user ki baat bilkul waisi ki waisi (kata-trim karke)। */
    fun extractUrlTask(rawText: String): String? =
        if (isUrlTask(rawText)) rawText.trim() else null

    /**
     * Seedhe chalaye gaye screen_task ka bola jaane
     * wala saar — hamesha sachha, user ki bhasha me:
     *  - screen agent ne apna saar khud diya ho
     *    (COMPLETED / model ke roke wala roop, uske
     *    apne shabdon me bandha hota hai) to wohi;
     *  - banking/payment wali suraksha-rok ho to
     *    uski saaf wajah;
     *  - warna natije (ok/fail) ki bandhi imaandaar
     *    line. Poora lamba varnan "Screen chaal"
     *    report me waise bhi bandhta hai।
     */
    fun spokenSummary(
        result: AgentTools.ToolResult,
        lang: PlayIntent.SpokenLang
    ): String {
        extractOwnSummary(result.text)?.let { return it }
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
                PlayIntent.SpokenLang.BENGALI -> "কাজটা করতে পারলাম না।"
                PlayIntent.SpokenLang.HINDI -> "काम पूरा नहीं हो पाया।"
                PlayIntent.SpokenLang.ENGLISH -> "I couldn't complete that task."
            }
        }
    }

    /**
     * ScreenAgent ke natija-text se agent ka apna
     * bandha saar nikalo — woh roop jahan uska jawab
     * `already in the user's language: "..." —` wale
     * bandhan me aata hai (kaam poora / model ne
     * imaandaari se roka)। Na mile to null।
     */
    /** v67: [PasteSendIntent] bhi yahi saar istemal karta hai — isliye public. */
    fun extractOwnSummary(text: String): String? {
        val match = Regex("language: \"(.+?)\" —").find(text) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }

    /** Seedhi screen_task daud ki report — "jarvis_diag" ki "last_agent_run" par। */
    fun saveRouteDiag(context: Context, ok: Boolean) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "steps: screen_task(url-route)=" + if (ok) "OK" else "FAIL"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }
}

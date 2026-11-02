package com.aship.jarvis.ai

import java.util.Locale

/**
 * v47: APP-WISE PLAYBOOK PACK — WhatsApp / Chrome /
 * Gallery ke kaamon ke liye bandhi chaal-suchi।
 *
 * [TrashPlaybook] wala hi andaaz: jab screen_task
 * ka kaam kisi jaanch se milta hai, us app ki
 * bandhi hidayat screen wale mastishk ke har kadam
 * ke sandesh me jud jati hai ([ScreenAgent] ka
 * playbook wala hissa), aur kataar me shuru me ek
 * "playbook: <naam>" line band hoti hai taaki woh
 * v46 wale audit record me bhi dikhe।
 *
 * Ye playbook sirf RASSTA dikhate hain — kaun si
 * chaal kis kram me chalni hai. Bhejne/mitaane ki
 * pushti hamesha ki tarah system ka gate hi leta
 * hai; playbook kisi pushti ko chhodta (bypass)
 * nahi hai aur koi naya action nahi deta — ye
 * sirf mastishk ke liye likhi hidayat hai।
 *
 * TAARTIB (pehle jo mile, ek kaam par sirf EK
 * pack playbook):
 * 0. Trash wala playbook sabse pehle — woh
 *    [TrashPlaybook] me alag hai aur [AgentTools]
 *    use pehle jaanchta hai; yahan woh aata hi
 *    nahi, isliye uski chaal bilkul nahi badli।
 * 1. Gallery MANZIL wali jaanch: kaam me koi app
 *    sirf zikr ho (jaise "WhatsApp-e pathano
 *    chhobita") par asal kaam gallery me SEV
 *    karna ho, to kaam gallery ka hai — manzil
 *    zikr par bhaari padti hai।
 * 2. WhatsApp (zikr + message/send/chat/call ka
 *    iraada, dono ek saath)।
 * 3. Chrome / web (browser/website/GitHub ka zikr,
 *    ya bina kisi doosre app ke search ka iraada)।
 * 4. Aam gallery (gallery/album ka zikr +
 *    dekhna/sev/share ka iraada, ya sirf chhobi
 *    dekhne ka kaam jahan koi doosra app na ho)।
 * Sirf app ka naam aane se playbook nahi lagta —
 * har jaanch do sanket maangti hai, [TrashPlaybook]
 * ki tang jaanch ki hi tarah।
 */
object Playbooks {

    /** Laga hua playbook: uska chhota naam (kataar/audit ke liye) + poora text. */
    data class Attached(val name: String, val text: String)

    /**
     * Is kaam par pack ka koi playbook lagta ho to
     * woh, warna null. Kaam ka apna text kabhi nahi
     * badalta — sirf milan jaancha jata hai।
     */
    fun forTask(task: String): Attached? {
        val lower = task.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        // Manzil pehle: "…chhobita gallery-te sev
        // karo" jaise kaam me WhatsApp/Chrome sirf
        // zikr hai, asal kaam gallery ka hai।
        if (matchesGalleryDestination(lower)) {
            return Attached("gallery", GALLERY_TEXT)
        }
        if (matchesWhatsApp(lower)) {
            return Attached("whatsapp", WHATSAPP_TEXT)
        }
        if (matchesChrome(lower)) {
            return Attached("chrome", CHROME_TEXT)
        }
        if (matchesGallery(lower)) {
            return Attached("gallery", GALLERY_TEXT)
        }
        return null
    }

    /** Kisi chhote sanket-samuh ka koi shabd maujud? */
    private fun anyIn(lower: String, words: List<String>): Boolean =
        words.any { lower.contains(it) }

    // ---------------------------------------------------------------
    // Gallery: MANZIL wali jaanch (taartib 1)
    // ---------------------------------------------------------------

    /**
     * Kaam ka samaapan gallery me hai kya — "gallery"
     * -te (manzil wali sthiti) AUR sev/rakhne ka
     * AADESH-roop (imperative) iraada, dono ek saath,
     * ya bandha manzil-wakya mile।
     * Aadesh-roop isliye zaroori hai: "gallery-te sev
     * KORA chhobita WhatsApp-e pathao" me sev sirf
     * bhoot-kaal ka varnan hai — asal aadesh pathano
     * hai, isliye woh kaam WhatsApp ka hai, gallery
     * ka nahi. "Koro/kore" (aadesh) aur "kora" (varnan)
     * ke akshar alag hain, wahi bandha sanket hai।
     * "Gallery theke" (srot) bhi isme nahi aata —
     * woh sirf zikr hai, manzil nahi।
     */
    private fun matchesGalleryDestination(lower: String): Boolean {
        if (anyIn(lower, GALLERY_DEST_PHRASES)) return true
        return anyIn(lower, GALLERY_LOC) &&
            anyIn(lower, GALLERY_DEST_INTENTS)
    }

    /** Bandhe manzil-wakya (sab aadesh-roop me). */
    private val GALLERY_DEST_PHRASES = listOf(
        "গ্যালারিতে সেভ করো", "গ্যালারিতে সেভ করে দাও",
        "গ্যালারীতে সেভ করো", "গ্যালারীতে সেভ করে দাও",
        "গ্যালারিতে রেখে দাও", "গ্যালারীতে রেখে দাও",
        "গ্যালারিতে রাখো", "গ্যালারীতে রাখো",
        "save in the gallery", "save to gallery",
        "save it in the gallery", "save this in the gallery",
        "गैलरी में सेव करो", "गैलरी में सेव कर दो"
    )

    /** "Gallery"-te wala sthiti-roop (manzil). */
    private val GALLERY_LOC = listOf(
        "গ্যালারিতে", "গ্যালারীতে", "gallery-তে", "gallery তে"
    )

    /** Sev / rakhne ke AADESH-roop sanket. */
    private val GALLERY_DEST_INTENTS = listOf(
        "সেভ করো", "সেভ করে দাও", "রেখে দাও", "রাখো",
        "save", "सेव करो", "सेव कर दो"
    )

    // ---------------------------------------------------------------
    // WhatsApp (taartib 2)
    // ---------------------------------------------------------------

    /**
     * WhatsApp ka zikr AUR message bhejna / chat /
     * call ka iraada — dono ek saath. Sirf "WhatsApp
     * kholo" isme nahi aata (iraada nahi hai), aur
     * sirf "…" pathao" bhi nahi (zikr nahi hai)।
     */
    private fun matchesWhatsApp(lower: String): Boolean {
        if (!anyIn(lower, WHATSAPP_REFS)) return false
        return anyIn(lower, WHATSAPP_INTENTS)
    }

    /** WhatsApp ke zikr (teeno script + aam galat-bani). */
    private val WHATSAPP_REFS = listOf(
        "whatsapp", "whats app", "whatsap",
        "হোয়াটসঅ্যাপ", "হোয়াটস অ্যাপ", "হোয়াটসয়্যাপ",
        "ওয়াটসঅ্যাপ", "হোয়াটসআপ",
        "व्हाट्सएप", "व्हाट्सऐप", "वाट्सएप", "व्हाट्सएप्प"
    )

    /** Message / send / chat / call ke iraade ke sanket. */
    private val WHATSAPP_INTENTS = listOf(
        "মেসেজ", "বার্তা", "চ্যাট",
        "পাঠাও", "পাঠিয়ে", "পাঠাতে",
        "লিখে দাও", "লিখো",
        "কল করো", "কল দাও",
        "message", "send", "chat", "pathao", "bhejo", "call",
        "मैसेज", "संदेश", "भेजो", "भेज", "चैट", "कॉल करो"
    )

    // ---------------------------------------------------------------
    // Chrome / web (taartib 3)
    // ---------------------------------------------------------------

    /**
     * Chrome/browser/website/GitHub ka saaf zikr —
     * ya sirf search ka iraada jab koi DOOSRA app
     * (YouTube/WhatsApp/Gallery aadi) naam se na
     * aaya ho: "YouTube-e gaan search karo" YouTube
     * ka kaam hai, Chrome ka nahi; "Google-e search
     * karo" web ka kaam hai।
     */
    private fun matchesChrome(lower: String): Boolean {
        if (anyIn(lower, CHROME_REFS)) return true
        if (!anyIn(lower, SEARCH_INTENTS)) return false
        return !anyIn(lower, OTHER_PLATFORM_REFS)
    }

    /** Browser / web / GitHub ke saaf zikr. */
    private val CHROME_REFS = listOf(
        "chrome", "ক্রোম", "क्रोम",
        "browser", "ব্রাউজার", "ब्राउज़र",
        "ওয়েবসাইট", "website", "ওয়েবপেজ", "webpage", "web page",
        "github", "গিটহাব", "गिटहब",
        "http://", "https://", "www.",
        ".com", ".org", ".net", ".io", ".gov.in", ".co.in"
    )

    /** Search / khoj ke iraade ke sanket. */
    private val SEARCH_INTENTS = listOf(
        "সার্চ", "search", "খুঁজ", "খোঁজ",
        "গুগল", "google", "खोज", "सर्च"
    )

    /**
     * Doosre naam-daar platform — search wala kaam
     * inme se kisi ke liye ho to Chrome playbook
     * nahi lagta (uska apna app hi woh kaam karega)।
     */
    private val OTHER_PLATFORM_REFS = listOf(
        "ইউটিউব", "youtube",
        "টেলিগ্রাম", "telegram",
        "ফেসবুক", "facebook",
        "ইনস্টাগ্রাম", "instagram",
        "whatsapp", "হোয়াটসঅ্যাপ", "व्हाट्सएप",
        "গ্যালারি", "gallery", "গ্যালারী"
    )

    // ---------------------------------------------------------------
    // Gallery: aam jaanch (taartib 4)
    // ---------------------------------------------------------------

    /**
     * Gallery/album ka zikr + dekhne/sev/share ka
     * iraada — ya sirf "chhobi dekhao" wala kaam
     * jisme koi doosra platform naam se na aaya ho।
     * Trash-khaali karne wala kaam yahan kabhi nahi
     * aata: woh [TrashPlaybook] pehle hi le jata hai
     * aur ek kaam par do playbook nahi lagte।
     */
    private fun matchesGallery(lower: String): Boolean {
        if (anyIn(lower, GALLERY_REFS) &&
            anyIn(lower, GALLERY_INTENTS)
        ) {
            return true
        }
        // "Shesh chhobita dekhao" — chhobi + dekhna,
        // koi doosra platform nahi।
        return anyIn(lower, PHOTO_REFS) &&
            anyIn(lower, PHOTO_VIEW_INTENTS) &&
            !anyIn(lower, OTHER_PLATFORM_REFS)
    }

    /** Gallery / album / photos-app ke zikr. */
    private val GALLERY_REFS = listOf(
        "গ্যালারি", "গ্যালারী", "gallery",
        "অ্যালবাম", "album",
        "গুগল ফটোস", "google photos",
        "गैलरी", "एल्बम"
    )

    /** Gallery ke kaamon ke iraade ke sanket. */
    private val GALLERY_INTENTS = listOf(
        "দেখাও", "দেখো", "দেখা", "খোলো",
        "সেভ", "শেয়ার",
        "open", "show", "save", "share",
        "दिखाओ", "खोलो", "देखो"
    )

    /** Chhobi ke zikr (gallery ka naam na aaye tab ke liye). */
    private val PHOTO_REFS = listOf(
        "ছবি", "ফটো", "photo", "फ़ोटो", "तस्वीर"
    )

    /** Chhobi dekhne ke iraade ke sanket. */
    private val PHOTO_VIEW_INTENTS = listOf(
        "দেখাও", "দেখো", "দেখা", "show", "दिखाओ", "देखो"
    )

    // ---------------------------------------------------------------
    // Playbook texts — mastishk ke liye bandhi hidayat
    // (har kadam ke sandesh me "PLAYBOOK" bankar jati hain;
    // pushti ka kaam hamesha system ke gate ka hi hai)
    // ---------------------------------------------------------------

    const val WHATSAPP_TEXT =
        "WHATSAPP PLAYBOOK — find the chat by SEARCH, never by scrolling.\n" +
            "1. Start with open_app \"WhatsApp\".\n" +
            "2. Tap the Search icon and type_into the contact or group " +
            "name in the search box. NEVER scroll the chat list hunting " +
            "for a chat.\n" +
            "3. Tap the search result whose label matches the name " +
            "exactly; prefer the top exact match. If an individual chat " +
            "and a group share a similar name, prefer the one the task " +
            "means.\n" +
            "4. If no result appears after searching, use ask_user and " +
            "ask for the exact name or spelling — do not guess, and do " +
            "not open a wrong chat.\n" +
            "5. Type the message with type_into on the message box " +
            "(label \"Message\" / \"বার্তা লিখুন\").\n" +
            "6. Tap Send ONLY if the task explicitly asked to send the " +
            "message; the system will ask the user to confirm before " +
            "it goes.\n" +
            "7. NEVER tap call or video-call buttons unless the task " +
            "explicitly asked for a call.\n" +
            "8. If the task asks for a voice or video call: open the " +
            "chat, then ask_user and stop — calls are out of scope " +
            "for you.\n" +
            "9. If a block/report screen or any WhatsApp payment " +
            "screen appears, stop and report honestly."

    const val CHROME_TEXT =
        "CHROME / WEB PLAYBOOK — the user's network is SLOW; " +
            "wait for pages, never guess.\n" +
            "1. Start with open_app \"Chrome\".\n" +
            "2. Tap the address bar, type_into the search query or " +
            "the full URL, then use submit.\n" +
            "3. After every navigation, ALWAYS wait_for the expected " +
            "result text BEFORE tapping anything — pages here can " +
            "take 10+ seconds to load.\n" +
            "4. Dismiss popups with their standard labels only: " +
            "\"Not now\", \"No thanks\", \"পরে\", \"এখন নয়\", \"Skip\".\n" +
            "5. NEVER sign in and NEVER type any password, OTP or " +
            "other credential anywhere. If a page demands login to " +
            "continue, stop and report honestly.\n" +
            "6. Prefer the result link whose visible title or " +
            "snippet matches the target; read snippets before " +
            "tapping.\n" +
            "7. GitHub: use the site's own search box for repo " +
            "search. Results show \"owner/repo\" text — tap by " +
            "that exact text.\n" +
            "8. On a repo page, file rows are list items — tap by " +
            "the exact filename text. The code view may need a " +
            "horizontal swipe or scroll to read fully.\n" +
            "9. If Chrome shows a first-run / welcome screen, " +
            "dismiss it with its no-thanks style button and continue."

    const val GALLERY_TEXT =
        "GALLERY PLAYBOOK — albums by label, photos by swipe.\n" +
            "1. Start with open_app on the gallery/photos app.\n" +
            "2. For an album task, go to the Albums / Collections " +
            "tab and open the named album by tapping its exact " +
            "label.\n" +
            "3. If albums are not visible, look in the \"Device " +
            "folders\" / \"On this device\" section — albums often " +
            "live there.\n" +
            "4. Open a photo by tapping its thumbnail.\n" +
            "5. Move between photos with swipe left / swipe right " +
            "— NOT scroll.\n" +
            "6. To share a photo, tap the visible Share icon and " +
            "use the normal share flow; the user picks the " +
            "destination app and contact.\n" +
            "7. Deleting a single photo: use its Delete option so " +
            "it goes to trash — the system confirmation gate " +
            "handles approval. Never use permanent-delete wording " +
            "or dialogs on your own.\n" +
            "8. Emptying the trash itself is a different playbook " +
            "— never improvise a permanent deletion here."
}

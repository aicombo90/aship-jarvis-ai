package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.commands.PlayIntent.SpokenLang
import java.net.URLEncoder
import java.util.Locale

/**
 * v76: DEKHAO — "দেখাও" (show-me) wala pakka raasta.
 *
 * User ka kaam (2026-11-09): "শাড়ি দেখতে চাই" /
 * "কোরিয়ান শার্ট দেখতে চাই" bole to Maya pehle soche,
 * phir Google Images me achhi tasveerein kholkar
 * dikhaye; "পছন্দ হচ্ছে না" bole to dobara dhoondhkar
 * aur dikhaye; "দাম কত / ডিটেলস বলো" bole to rang,
 * design, daam, quality achhi-buri — tafseel se bole.
 *
 * Niyam PasteSendIntent / UrlIntent wala hi hai — baat
 * me dikhane ka saaf iraada ho to baat seedha pakka
 * raasta legi, baatchit wala mastishk is par aayega hi
 * nahi (warna woh sirf baat banakar reh jata hai).
 * Sirf khoj-natijon wala page khulta hai (Google
 * Images) — koi dukaan ka checkout/payment page
 * kabhi nahi khulta, koi khareed kabhi nahi hoti.
 *
 * Teen kaam, ek hi jagah:
 *  1. SHOW — "X দেখাও / X দেখতে চাই": X nikaalkar
 *     Google Images kholo. Online ho to mastishk
 *     (GeminiBrain.chat) se khoj-shabd behtar karwao;
 *     offline ho to kachcha X hi khoj-shabd. Aakhiri
 *     dikhaya hua vishay + variant [jarvis_showme]
 *     me bandhta hai.
 *  2. REFINE — "পছন্দ হচ্ছে না / আরও দেখাও": aakhiri
 *     vishay par NAYA khoj-roop (variant ghumakar:
 *     latest design, new collection, price, online
 *     shopping). Aakhiri vishay na ho to imaandaar
 *     "আগে কী দেখতে চাও বলো।"
 *  3. DETAILS — "এটার দাম কত / ডিটেলস বলো": aakhiri
 *     dikhaye hue vishay par online mastishk se
 *     tafseel (daam ki seema, kapda/quality, khareedne
 *     se pehle kya jaanchna) bolkar sunao. Mastishk ko
 *     bheja jaane wala sawal saaf kehta hai: kisi
 *     dukaan ka live daam sach maan kar kabhi mat
 *     banao — daam dukaan-dar-dukaan badalte hain,
 *     sirf seema do. Offline ho to imaandaar manaahi.
 *
 * Kram dispatch me: REFINE pehle (usme "দেখাও" bhi
 * hai, isliye SHOW se pehle jaanchna zaroori hai),
 * phir DETAILS, phir SHOW — aur teeno agent se pehle.
 */
object ShowMeIntent {

    /** Teen kaam — dispatch inhi me se ek chunta hai. */
    enum class Kind { SHOW, REFINE, DETAILS }

    // ---------------------------------------------------------------
    // Khoj-roop (variant) — REFINE har baar agla roop kholta hai
    // ---------------------------------------------------------------

    /** Variant 0 = seedha vishay; baaki khoj ke naye kon. */
    private val VARIANT_SUFFIXES = listOf(
        "",
        " latest design",
        " new collection",
        " price",
        " online shopping"
    )

    /** Kul kitne roop hain (ghumaane ke liye). */
    val variantCount: Int get() = VARIANT_SUFFIXES.size

    /** Vishay + variant se khoj-shabd banao. */
    fun buildVariantQuery(topic: String, variant: Int): String {
        val suffix = VARIANT_SUFFIXES[
            (variant % VARIANT_SUFFIXES.size + VARIANT_SUFFIXES.size) %
                VARIANT_SUFFIXES.size
        ]
        return (topic + suffix).trim()
    }

    /** Google Images wala khoj URL — sirf natije, koi dukaan nahi. */
    fun imagesUrl(query: String): String =
        "https://www.google.com/search?tbm=isch&q=" +
            URLEncoder.encode(query, "UTF-8")

    // ---------------------------------------------------------------
    // Aakhiri dikhaya hua vishay — "jarvis_showme" prefs
    // ---------------------------------------------------------------

    private const val PREFS_NAME = "jarvis_showme"
    private const val KEY_TOPIC = "last_topic"
    private const val KEY_VARIANT = "last_variant"

    fun saveLastShown(context: Context, topic: String, variant: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TOPIC, topic)
                .putInt(KEY_VARIANT, variant)
                .apply()
        } catch (t: Throwable) {
            // Yaad rakhna bonus hai — dikhana isse nahi rukta.
        }
    }

    /** Aakhiri vishay + variant, ya null (abhi kuch dikhaya hi nahi). */
    fun loadLastShown(context: Context): Pair<String, Int>? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            val topic = prefs.getString(KEY_TOPIC, null)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val variant = prefs.getInt(KEY_VARIANT, 0) % variantCount
            Pair(topic, if (variant < 0) 0 else variant)
        } catch (t: Throwable) {
            null
        }
    }

    fun hasLastShown(context: Context): Boolean = loadLastShown(context) != null

    // ---------------------------------------------------------------
    // 1. SHOW — "X দেখাও / X দেখতে চাই"
    // ---------------------------------------------------------------

    /**
     * Dikhane ke chinh — saaf kriya-roop hi (akelā
     * "দেখতে" nahi: woh aam baat me bhi aata hai)।
     * Inme se koi ho AUR kaatne ke baad vishay bache
     * to hi SHOW hai ("আমাকে দেখাও" me vishay nahi
     * bachta, isliye woh SHOW nahi — aam raasta)।
     */
    private val SHOW_MARKERS = listOf(
        // Bangla (script)
        "দেখতে চাইছি", "দেখতে চাই", "দেখতে দাও",
        "এর ছবি দেখাও", "ছবি দেখাও", "দেখিয়ে দাও",
        "দেখাও", "দেখান",
        // Bangla (roman roop — recognizer kabhi ye bhi deta hai)
        "dekhte chaichi", "dekhte chai", "dekhte dao",
        "chobi dekhao", "dekhao", "dekhan",
        // Hindi
        "देखना चाहती हूँ", "देखना चाहता हूँ",
        "दिखाइए", "दिखा दो", "दिखाओ",
        // English (poora wakyaansh — akela "show" bahut dheela hai)
        "show me"
    )

    /**
     * Kaatne wale shabd — vishay se hatane ke liye:
     * dikhane wale kriya-roop + "ছবি/ফটো" (khoj
     * pehle se hi tasveeron ki hai) + bharaav shabd।
     * Sabse lambe pehle kaate jate hain।
     */
    private val SHOW_STRIP_MARKERS = (SHOW_MARKERS + listOf(
        "ছবিগুলো", "ছবিটা", "ছবি", "ফটো", "পিকচার",
        "photo", "images", "image", "picture"
    )).sortedByDescending { it.length }

    /** Kaatne ke baad bache hue bharav wale tukde (shabd-seema par)। */
    private val FILLER_TOKENS = setOf(
        "আমি", "আমাকে", "আমায়", "আমার", "আমার জন্য",
        "একটা", "একটি", "একটু", "একবার", "তো",
        "এর", "এই", "ওই", "সেই",
        "please", "ekta", "ektu", "amake", "ami"
    )

    /** Dikhane wala kaam hai kya (chinh + bacha hua vishay dono)? */
    fun isShowTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        if (!SHOW_MARKERS.any { lower.contains(it) }) return false
        return extractShowItem(rawText) != null
    }

    /** Vishay nikaalo ("শাড়ি দেখতে চাই" -> "শাড়ি"), na mile to null। */
    fun extractShowItem(rawText: String): String? {
        if (rawText.isBlank()) return null
        var s = " " + rawText.lowercase(Locale.ROOT) + " "
        for (marker in SHOW_STRIP_MARKERS) {
            s = s.replace(marker, " ")
        }
        val kept = s.split(Regex("\\s+"))
            .map { it.trim('।', '.', ',', '!', '?', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() && it !in FILLER_TOKENS }
        if (kept.isEmpty()) return null
        return kept.joinToString(" ").trim().takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------
    // 2. REFINE — "পছন্দ হচ্ছে না / আরও দেখাও"
    // ---------------------------------------------------------------

    /**
     * Dobara dhoondhne ke chinh — ye SHOW se PEHLE
     * jaanche jate hain ("আরও দেখাও" me "দেখাও" bhi
     * hai)। Inme koi bhi ho to REFINE hai, vishay ho
     * ya na ho (na ho to imaandaar jawab)।
     */
    private val REFINE_MARKERS = listOf(
        // Bangla (script)
        "পছন্দ হচ্ছে না", "পছন্দ হয়নি", "পছন্দ হলো না",
        "পছন্দ হল না", "ভালো লাগছে না", "ভালো লাগলো না",
        "ভালো লাগেনি", "আরও দেখাও", "আরো দেখাও",
        "অন্যরকম দেখাও", "অন্য রকম দেখাও",
        "অন্য কিছু দেখাও", "অন্য দেখাও",
        "আরেকটা দেখাও", "আর একটা দেখাও",
        "আরও দেখান", "আরও ছবি",
        // Bangla (roman roop)
        "pochondo hocche na", "pochondo hoyni",
        "aro dekhao", "onno rokom dekhao",
        "onno kichu dekhao", "arekta dekhao",
        // Hindi
        "पसंद नहीं आया", "पसंद नहीं",
        "और दिखाओ", "और दिखाइए",
        // English
        "not this", "show more", "show another",
        "show different", "show others"
    )

    /** Dobara dhoondhne wala hukm hai kya? */
    fun isRefineTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return REFINE_MARKERS.any { lower.contains(it) }
    }

    // ---------------------------------------------------------------
    // 3. DETAILS — "এটার দাম কত / ডিটেলস বলো"
    // ---------------------------------------------------------------

    /**
     * Bandhe chinh — "এটা/এর" ke saath, isliye aakhiri
     * vishay ho ya na ho, DETAILS hi hai (na ho to
     * imaandaar "কোন জিনিসের" wala jawab)।
     */
    private val DETAILS_ANCHORED_MARKERS = listOf(
        // Bangla (script)
        "ডিটেলস বলো", "ডিটেইলস বলো", "বিস্তারিত বলো",
        "এটা সম্পর্কে বলো", "এর সম্পর্কে বলো",
        "এই সম্পর্কে বলো", "এটার দাম", "এর দাম",
        "এই দাম", "এটার কোয়ালিটি", "এর কোয়ালিটি",
        "এটা ভালো না খারাপ", "এর ভালো খারাপ",
        // Bangla (roman roop)
        "details bolo", "bistarito bolo",
        "etar dam", "er dam", "eta somporke bolo",
        // Hindi
        "इसके बारे में बताओ", "इसकी कीमत",
        "detail batao", "विस्तार से बताओ",
        // English
        "tell me about this", "tell me the details"
    )

    /**
     * Khule chinh — sirf tab jab aakhiri dikhaya hua
     * vishay maujood ho (warna "আজ সোনার দাম কত"
     * jaisi aam baat bhi yahan aa jayegi — woh
     * mastishk ka kaam hai, is raaste ka nahi)।
     * Akela "দাম" / "price" jaan-boojh kar nahi hai।
     */
    private val DETAILS_BARE_MARKERS = listOf(
        // Bangla (script)
        "দাম কত", "কোয়ালিটি কেমন", "কেমন কোয়ালিটি",
        // Bangla (roman roop)
        "dam koto", "quality kemon",
        // Hindi
        "कीमत कितनी", "क्वालिटी कैसी",
        // English
        "how much is it", "what is the price",
        "what's the price"
    )

    /** Tafseel maangne wala hukm hai kya? */
    fun isDetailsTask(rawText: String, hasLastTopic: Boolean): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        if (DETAILS_ANCHORED_MARKERS.any { lower.contains(it) }) return true
        if (!hasLastTopic) return false
        return DETAILS_BARE_MARKERS.any { lower.contains(it) }
    }

    /**
     * Teenon me se kaunsa kaam hai — REFINE pehle,
     * phir DETAILS, phir SHOW; koi na ho to null।
     */
    fun classify(rawText: String, hasLastTopic: Boolean): Kind? {
        if (rawText.isBlank()) return null
        if (isRefineTask(rawText)) return Kind.REFINE
        if (isDetailsTask(rawText, hasLastTopic)) return Kind.DETAILS
        if (isShowTask(rawText)) return Kind.SHOW
        return null
    }

    // ---------------------------------------------------------------
    // Mastishk wale sawal (GeminiBrain.chat ke liye)
    // ---------------------------------------------------------------

    /**
     * Khoj-shabd behtar karne ka sawal — jawab me
     * SIRF khoj-shabd (zyada se zyada 8 shabd)।
     * Angrezi khoj-shabd isliye: fashion wale shabd
     * usme zyada milte hain, aur Google Images use
     * behtar samajhta hai।
     */
    fun refineQueryPrompt(item: String): String =
        "Give me one short Google Images search query " +
            "(maximum 8 words) for finding good pictures of " +
            "\"$item\". Reply with ONLY the query text, " +
            "nothing else — no explanation, no quotes."

    /** Mastishk ke khoj-shabd ko saaf karo — gadbad ho to null (kachcha vishay chalega)। */
    fun sanitizeRefinedQuery(reply: String?): String? {
        val first = reply?.lineSequence()
            ?.map { it.trim().trim('"', '\'', '`', '*', '#') }
            ?.firstOrNull { it.isNotEmpty() } ?: return null
        val cleaned = first
            .removePrefix("query:")
            .removePrefix("Query:")
            .trim()
            .trim('"', '\'', '`')
        if (cleaned.isEmpty()) return null
        if (cleaned.split(Regex("\\s+")).size > 12) return null
        return cleaned
    }

    /**
     * Tafseel wala sawal — daam ki SEEMA, kapda /
     * quality, khareedne se pehle jaanch। Sawal me hi
     * pakka niyam: kisi dukaan ka live daam sach
     * maan kar kabhi mat banao — daam dukaan-dar-
     * dukaan badalte hain, sirf seema do, aur saaf
     * kaho ki asli daam khule hue page par hai।
     */
    fun detailsPrompt(topic: String, lang: SpokenLang): String {
        val langName = when (lang) {
            SpokenLang.BENGALI -> "Bengali"
            SpokenLang.HINDI -> "Hindi"
            SpokenLang.ENGLISH -> "English"
        }
        return "In $langName, in 3 to 4 short sentences, tell me " +
            "about \"$topic\": typical price range in India, " +
            "what fabric or material and quality to look for, " +
            "and what to check before buying. IMPORTANT: Never " +
            "invent a specific shop's live price as a fact — " +
            "prices vary by shop, so give a range only and say " +
            "the exact price is on the opened shopping page. " +
            "Keep it short, suitable for speaking aloud."
    }

    /** Tafseel wale jawab ko bolne layak saaf karo (600 akshar tak)। */
    fun cleanDetailsReply(reply: String): String {
        val cleaned = reply
            .replace("*", "")
            .replace("#", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length <= 600) return cleaned
        val cut = cleaned.substring(0, 600)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > 400) cut.substring(0, lastSpace).trim() + "…" else cut.trim() + "…"
    }

    // ---------------------------------------------------------------
    // Boli jaane wali linein (user ki bhasha me, hamesha sachhi)
    // ---------------------------------------------------------------

    /** Kholne se pehle sochne wali line (screen par / status me)। */
    fun thinkingLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ভেবে দেখছি — ভালো ছবি খুঁজছি..."
        SpokenLang.HINDI -> "सोच रही हूँ — अच्छी तस्वीरें खोज रही हूँ..."
        SpokenLang.ENGLISH -> "Thinking — finding good pictures..."
    }

    /** Khul gaya — kya dikhaya, aur aage kya bole. */
    fun showingLine(topic: String, lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "$topic-এর ভালো ভালো ছবি খুলে দিলাম — পছন্দ না হলে " +
                "'পছন্দ হচ্ছে না' বলো।"
        SpokenLang.HINDI ->
            "$topic की अच्छी-अच्छी तस्वीरें खोल दी हैं — पसंद न आए " +
                "तो 'पसंद नहीं' बोलो।"
        SpokenLang.ENGLISH ->
            "Opened good pictures of $topic — if you don't like " +
                "them, say so and I'll show more."
    }

    /** Dobara dhoondhkar khol rahe hain. */
    fun refiningLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "অন্যরকম করে খুঁজছি — আরও ছবি খুলে দিচ্ছি।"
        SpokenLang.HINDI -> "दूसरी तरह से खोज रही हूँ — और तस्वीरें खोल रही हूँ।"
        SpokenLang.ENGLISH -> "Searching another way — opening more pictures."
    }

    /** SHOW me vishay hi nahi mila — kya dikhana hai poochho. */
    fun askWhatLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "কী দেখতে চাও বলো — শাড়ি, শার্ট, যা খুশি।"
        SpokenLang.HINDI -> "क्या देखना चाहते हो बोलो — साड़ी, शर्ट, जो भी।"
        SpokenLang.ENGLISH -> "Tell me what you want to see — a saree, a shirt, anything."
    }

    /** Aakhiri vishay hai hi nahi — REFINE / DETAILS par imaandaar jawab. */
    fun noTopicLine(kind: Kind, lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> when (kind) {
            Kind.REFINE -> "আগে কী দেখতে চাও বলো — তারপর আরও দেখাতে পারবো।"
            else -> "কোন জিনিসের ডিটেলস জানতে চাও বলো।"
        }
        SpokenLang.HINDI -> when (kind) {
            Kind.REFINE -> "पहले बोलो क्या देखना है — फिर और दिखा पाऊँगी।"
            else -> "किस चीज़ के बारे में जानना है बोलो।"
        }
        SpokenLang.ENGLISH -> when (kind) {
            Kind.REFINE -> "First tell me what you want to see — then I can show more."
            else -> "Tell me which thing you want the details of."
        }
    }

    /** DETAILS offline — imaandaar manaahi + chhobi phir kholne ki peshkash. */
    fun offlineDetailsLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "ডিটেলস বলতে অনলাইন লাগে, এখন অনলাইন নেই — ছবি আবার " +
                "দেখতে চাইলে 'আরও দেখাও' বলো।"
        SpokenLang.HINDI ->
            "बातें बताने के लिए ऑनलाइन चाहिए, अभी ऑनलाइन नहीं है — " +
                "तस्वीरें फिर देखने के लिए 'और दिखाओ' बोलो।"
        SpokenLang.ENGLISH ->
            "Details need the internet, which is off right now — " +
                "say 'show more' if you want to see the pictures again."
    }

    /** Browser khula hi nahi (aam wajah)। */
    fun openFailedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ব্রাউজার খুলতে পারলাম না — আবার বলো দেখি।"
        SpokenLang.HINDI -> "ब्राउज़र खोल नहीं पाई — फिर से बोलो।"
        SpokenLang.ENGLISH -> "I couldn't open the browser — please say it again."
    }

    /** Phone lock hai — browser khul nahi sakta (sirf background raasta)। */
    fun lockedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ফোন লক আছে — আনলক করে আবার বলো।"
        SpokenLang.HINDI -> "फ़ोन लॉक है — अनलॉक करके फिर बोलो।"
        SpokenLang.ENGLISH -> "The phone is locked — unlock it and say it again."
    }

    /** 'Display over other apps' anumati nahi (sirf background raasta)। */
    fun permissionLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "ব্রাউজার খুলতে 'অন্য অ্যাপের ওপরে দেখানো' অনুমতি লাগবে — " +
                "অ্যাপের সেটিংসে গিয়ে চালু করো।"
        SpokenLang.HINDI ->
            "ब्राउज़र खोलने के लिए 'दूसरे ऐप्स पर दिखाने' की अनुमति " +
                "चाहिए — ऐप की सेटिंग्स में जाकर चालू करो।"
        SpokenLang.ENGLISH ->
            "Opening the browser needs the 'display over other apps' " +
                "permission — turn it on in the app settings."
    }

    /** Seedhi daud ki report — "jarvis_diag" ki "last_agent_run" par। */
    fun saveRouteDiag(context: Context, kind: Kind, ok: Boolean) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "steps: showme(${kind.name.lowercase(Locale.ROOT)})=" +
                        if (ok) "OK" else "FAIL"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }
}

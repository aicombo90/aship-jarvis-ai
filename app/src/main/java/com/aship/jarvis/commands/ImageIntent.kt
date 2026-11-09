package com.aship.jarvis.commands

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import com.aship.jarvis.ai.ScreenshotStore
import com.aship.jarvis.commands.PlayIntent.SpokenLang
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v78: CHHOBI BANAO — "ছবি বানাও" wala pakka raasta.
 *
 * User ka kaam (2026-11-09): doosre Jarvis app me
 * Gemini se anime tasveer banti dekhi — "X-এর ছবি
 * বানাও" bole to Maya AI tasveer banakar dikhaye;
 * "আরেকটা বানাও" bole to dobara banaye; "শেয়ার করো"
 * bole to aakhiri tasveer share kare.
 *
 * Teen kaam, ek hi jagah (ShowMeIntent wala hi dhang):
 *  1. GENERATE — "X-এর ছবি বানাও / X বানিয়ে দাও":
 *     vishay nikaalkar [ImageGen] (gemini-2.5-flash-image)
 *     se tasveer banao, cache me rakho, gallery me bhi
 *     bachao, viewer me kholkar dikhao. Vishay na ho to
 *     "কিসের ছবি বানাবো?" — andaza kabhi nahi.
 *  2. REGENERATE — "আরেকটা বানাও": aakhiri vishay
 *     par dobara, alag style wali variation. Aakhiri
 *     vishay na ho to imaandaar jawab.
 *  3. SHARE — "শেয়ার করো": aakhiri bani tasveer ka
 *     share screen kholo (FileProvider URI, chooser)।
 *     Bhejna hamesha user ke haath me — intent sirf
 *     share screen KHOLTA hai, khud kisi ko nahi bhejta.
 *
 * IMAANDARI: tasveer sirf Gemini bana sakta hai —
 * text wali fallback kataar tasveer nahi bana sakti,
 * isliye key na ho / quota khatm ho / call fail ho to
 * runner sach bolta hai; naqli tasveer kabhi nahi,
 * purani tasveer ko nayi batakar kabhi nahi.
 *
 * Kram dispatch me: REGENERATE pehle (usme "বানাও"
 * bhi hai), phir SHARE, phir GENERATE — aur teeno
 * agent se pehle (ShowMeIntent ke baad, bilkul usi
 * jagah jahan DEKHAO wala check hai)।
 */
object ImageIntent {

    /** Teen kaam — dispatch inhi me se ek chunta hai. */
    enum class Kind { GENERATE, REGENERATE, SHARE }

    // ---------------------------------------------------------------
    // Aakhiri bani tasveer — "jarvis_images" prefs
    // ---------------------------------------------------------------

    private const val PREFS_NAME = "jarvis_images"
    private const val KEY_PATH = "last_path"
    private const val KEY_PROMPT = "last_prompt"

    /** Aakhiri tasveer (file ka raasta + vishay) bandho. */
    fun saveLastImage(context: Context, path: String, prompt: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PATH, path)
                .putString(KEY_PROMPT, prompt)
                .apply()
        } catch (t: Throwable) {
            // Yaad rakhna bonus hai — dikhana isse nahi rukta.
        }
    }

    /** Aakhiri tasveer + vishay, ya null (file bhi honi chahiye). */
    fun loadLastImage(context: Context): Pair<String, String>? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            val path = prefs.getString(KEY_PATH, null)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val prompt = prefs.getString(KEY_PROMPT, null)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            if (!File(path).exists()) return null
            Pair(path, prompt)
        } catch (t: Throwable) {
            null
        }
    }

    fun hasLastImage(context: Context): Boolean = loadLastImage(context) != null

    // ---------------------------------------------------------------
    // 1. GENERATE — "X-এর ছবি বানাও / X বানিয়ে দাও"
    // ---------------------------------------------------------------

    /**
     * Banane ke chinh — tasveer wala shabd + banane
     * wali kriya, ya "বানিয়ে দাও" (brief wala roop:
     * "X বানিয়ে দাও")। Akela "বানাও" jaan-boojh kar
     * nahi hai — woh "ভিডিও বানাও" jaisi aam baaton
     * me bhi aata hai aur use hijack kar lega.
     */
    private val GENERATE_MARKERS = listOf(
        // Bangla (script)
        "ছবি বানিয়ে দাও", "ছবি বানিয়ে দে",
        "ছবিটা বানিয়ে দাও", "ছবি বানাও",
        "ছবিটা বানাও", "এর ছবি বানিয়ে দাও",
        "এর ছবি বানাও", "ফটো বানিয়ে দাও",
        "ফটো বানাও", "পিকচার বানিয়ে দাও",
        "পিকচার বানাও", "বানিয়ে দাও", "বানিয়ে দে",
        // Bangla (roman roop)
        "chobi baniye dao", "chobi baniye de",
        "chobi banao", "photo banao",
        "baniye dao", "baniye de",
        // Hindi
        "तस्वीर बना दो", "तस्वीर बनाओ",
        "फोटो बना दो", "फोटो बनाओ",
        // English
        "generate an image of", "generate an image",
        "make an image of", "make an image",
        "create an image of", "create an image"
    )

    /**
     * Kaatne wale shabd — vishay se hatane ke liye:
     * banane wale kriya-roop + "ছবি/ফটো" + bharaav।
     * Sabse lambe pehle kaate jate hain।
     */
    private val GENERATE_STRIP_MARKERS = (GENERATE_MARKERS + listOf(
        "ছবিগুলো", "ছবিটা", "ছবি", "ফটো", "পিকচার",
        "photo", "photos", "images", "image", "picture"
    )).sortedByDescending { it.length }

    /** Kaatne ke baad bache hue bharav wale tukde (shabd-seema par)। */
    private val FILLER_TOKENS = setOf(
        "আমি", "আমাকে", "আমায়", "আমার", "আমার জন্য",
        "একটা", "একটি", "একটু", "একবার", "তো",
        "এর", "এই", "ওই", "সেই",
        "please", "ekta", "ektu", "amake", "ami"
    )

    /**
     * "ভিডিও বানাও" jaisi baat tasveer ka kaam NAHI
     * hai — use pakadna hijack hoga, isliye vishay me
     * ye shabd hon to GENERATE nahi (aam raasta)।
     */
    private val VIDEO_WORDS = listOf("ভিডিও", "video")

    /** Banane wala kaam hai kya (chinh ho, video wali baat na ho)? */
    fun isGenerateTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        if (!GENERATE_MARKERS.any { lower.contains(it) }) return false
        // Vishay ho ya na ho — GENERATE hi hai (na ho to
        // "কিসের ছবি বানাবো?" wala jawab); sirf video
        // wali baat ko chhodo, woh tasveer ka kaam nahi.
        if (VIDEO_WORDS.any { lower.contains(it) }) return false
        return true
    }

    /** Vishay nikaalo ("লাল শাড়ির ছবি বানাও" -> "লাল শাড়ির"), na mile to null। */
    fun extractImagePrompt(rawText: String): String? {
        if (rawText.isBlank()) return null
        var s = " " + rawText.lowercase(Locale.ROOT) + " "
        for (marker in GENERATE_STRIP_MARKERS) {
            s = s.replace(marker, " ")
        }
        val kept = s.split(Regex("\\s+"))
            .map { it.trim('।', '.', ',', '!', '?', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() && it !in FILLER_TOKENS }
        if (kept.isEmpty()) return null
        val prompt = kept.joinToString(" ").trim()
        if (VIDEO_WORDS.any { prompt.contains(it) }) return null
        return prompt.takeIf { it.isNotEmpty() }
    }

    // ---------------------------------------------------------------
    // 2. REGENERATE — "আরেকটা বানাও"
    // ---------------------------------------------------------------

    /**
     * Dobara banane ke chinh — GENERATE se PEHLE
     * jaanche jate hain ("আরেকটা ছবি বানাও" me
     * "ছবি বানাও" bhi hai)।
     */
    private val REGENERATE_MARKERS = listOf(
        // Bangla (script)
        "আরেকটা ছবি বানাও", "আরেকটা বানিয়ে দাও",
        "আরেকটা বানিয়ে দে", "আরেকটা বানাও",
        "আর একটা ছবি বানাও", "আর একটা বানাও",
        "আবার ছবি বানাও", "আবার বানিয়ে দাও",
        "আবার বানাও",
        // Bangla (roman roop)
        "arekta chobi banao", "arekta baniye dao",
        "arekta banao", "abar chobi banao",
        "abar baniye dao", "abar banao",
        // Hindi
        "एक और तस्वीर बनाओ", "एक और बनाओ",
        "फिर से बनाओ", "दोबारा बनाओ",
        // English
        "make another image", "generate another image",
        "make another", "generate another",
        "make another one"
    )

    /** Dobara banane wala hukm hai kya? */
    fun isRegenerateTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return REGENERATE_MARKERS.any { lower.contains(it) }
    }

    // ---------------------------------------------------------------
    // 3. SHARE — "শেয়ার করো"
    // ---------------------------------------------------------------

    /**
     * Share karne ke chinh — aakhiri bani tasveer ka
     * share screen kholna. Tasveer na ho to imaandaar
     * "এখনো কোনো ছবি বানানো হয়নি" wala jawab.
     */
    private val SHARE_MARKERS = listOf(
        // Bangla (script)
        "এই ছবিটা শেয়ার করো", "ছবিটা শেয়ার করো",
        "ছবি শেয়ার করো", "এটা শেয়ার করো",
        "শেয়ার করে দাও", "শেয়ার করো",
        // Bangla (roman roop)
        "eta share koro", "share kore dao", "share koro",
        // Hindi
        "इसे शेयर करो", "शेयर करो",
        // English
        "share this image", "share this", "share it"
    )

    /** Share karne wala hukm hai kya? */
    fun isShareTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val lower = rawText.lowercase(Locale.ROOT)
        return SHARE_MARKERS.any { lower.contains(it) }
    }

    /**
     * Teenon me se kaunsa kaam hai — REGENERATE pehle,
     * phir SHARE, phir GENERATE; koi na ho to null।
     */
    fun classify(rawText: String): Kind? {
        if (rawText.isBlank()) return null
        if (isRegenerateTask(rawText)) return Kind.REGENERATE
        if (isShareTask(rawText)) return Kind.SHARE
        if (isGenerateTask(rawText)) return Kind.GENERATE
        return null
    }

    // ---------------------------------------------------------------
    // Tasveer ko rakhna / kholna / share karna
    // ---------------------------------------------------------------

    /** Cache me tasveer likho — file naam image_<samay>.png/jpg। */
    fun writeImageFile(context: Context, bytes: ByteArray, mimeType: String): File? {
        return try {
            val dir = File(context.cacheDir, IMAGE_DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val ext = if (mimeType.contains("jpeg", ignoreCase = true)) "jpg" else "png"
            val name = "image_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".$ext"
            val file = File(dir, name)
            val written = FileOutputStream(file).use { stream ->
                stream.write(bytes)
                true
            }
            if (!written || !file.exists() || file.length() <= 0) {
                try {
                    file.delete()
                } catch (t: Throwable) {
                    // Safai bonus hai.
                }
                return null
            }
            // Purani bani tasveerein saaf karo — sirf taazi
            // wali rehti hai (cache bharna nahi chahiye)।
            try {
                dir.listFiles()?.forEach { old ->
                    if (old.absolutePath != file.absolutePath) old.delete()
                }
            } catch (t: Throwable) {
                // Safai bonus hai.
            }
            file
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Gallery me bhi bachao — [ScreenshotStore] wala hi
     * "ASHIP JARVIS" album (MediaStore, API 29+ bina
     * permission ke)। Na bache to bhi cache wali file
     * kaam karti hai — natija jhootha nahi banta.
     */
    fun saveImageToGallery(context: Context, bytes: ByteArray): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return false
            ScreenshotStore.saveToGallery(context, bitmap)
        } catch (t: Throwable) {
            false
        }
    }

    /** FileProvider wala content:// URI — file_paths.xml ki cache-path "." ise gher-ti hai। */
    private fun fileUri(context: Context, file: File) =
        try {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
        } catch (t: Throwable) {
            null
        }

    /** Tasveer kholne wala intent (viewer) — saamne / background dono raaste iska istemal karte hain। */
    fun buildViewIntent(context: Context, file: File, mimeType: String): Intent? {
        val uri = fileUri(context, file) ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Share karne wala chooser intent — screen KHULTA hai, bhejna user ke haath me। */
    fun buildShareChooserIntent(
        context: Context,
        file: File,
        lang: SpokenLang
    ): Intent? {
        val uri = fileUri(context, file) ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeForFile(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("jarvis_image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, shareTitle(lang))
    }

    /** File ke extension se mime — model aam taur par PNG deta hai, JPEG bhi aa sakta hai। */
    private fun mimeForFile(file: File): String =
        if (file.name.endsWith(".jpg", ignoreCase = true) ||
            file.name.endsWith(".jpeg", ignoreCase = true)
        ) {
            "image/jpeg"
        } else {
            "image/png"
        }

    /** Chooser ka shirshak (user ki bhasha me)। */
    private fun shareTitle(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "শেয়ার করো"
        SpokenLang.HINDI -> "शेयर करो"
        SpokenLang.ENGLISH -> "Share"
    }

    // ---------------------------------------------------------------
    // Boli jaane wali linein (user ki bhasha me, hamesha sachhi)
    // ---------------------------------------------------------------

    /** Banate hue — saamne wala raasta turant bolta hai, background wala status me dikhata hai। */
    fun generatingLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "বানাচ্ছি, একটু দাঁড়াও..."
        SpokenLang.HINDI -> "बना रही हूँ, थोड़ा रुको..."
        SpokenLang.ENGLISH -> "Making it — just a moment..."
    }

    /** Ban gayi, khul gayi — dekhne ko kaho। */
    fun readyLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ছবিটা বানিয়ে দিলাম, বস — দেখো কেমন হয়েছে।"
        SpokenLang.HINDI -> "तस्वीर बना दी है, बॉस — देखो कैसी लगी।"
        SpokenLang.ENGLISH -> "The picture is ready, boss — take a look."
    }

    /** Vishay hi nahi mila — kya banana hai poochho। */
    fun askWhatLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "কিসের ছবি বানাবো, বস?"
        SpokenLang.HINDI -> "किसकी तस्वीर बनाऊँ, बॉस?"
        SpokenLang.ENGLISH -> "What picture should I make, boss?"
    }

    /** Offline mode chalu hai — tasveer banegi hi nahi। */
    fun offlineLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "অফলাইন মোডে ছবি বানানো যাবে না — অনলাইন মোডে গিয়ে আবার বলো।"
        SpokenLang.HINDI -> "ऑफलाइन मोड में तस्वीर नहीं बन सकती — ऑनलाइन मोड में जाकर फिर बोलो।"
        SpokenLang.ENGLISH -> "I can't make pictures in offline mode — switch to online mode and say it again."
    }

    /** Gemini key hi nahi hai — kahan se milega, wahi bolo। */
    fun noKeyLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ছবি বানাতে Gemini key লাগবে — API Keys-এ গিয়ে Gemini key বসাও।"
        SpokenLang.HINDI -> "तस्वीर बनाने के लिए Gemini key चाहिए — API Keys में जाकर Gemini key डालो।"
        SpokenLang.ENGLISH -> "I need a Gemini key to make pictures — add it in API Keys."
    }

    /** Net band hai — tasveer banane ke liye net chahiye। */
    fun noNetLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "নেট নেই — ছবি বানাতে ইন্টারনেট লাগবে।"
        SpokenLang.HINDI -> "नेट नहीं है — तस्वीर बनाने के लिए इंटरनेट चाहिए।"
        SpokenLang.ENGLISH -> "There's no internet — I need it to make a picture."
    }

    /** 429 — Gemini ki seema khatm। */
    fun quotaLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "Gemini-র সীমা এখন শেষ — একটু পরে আবার চেষ্টা করো।"
        SpokenLang.HINDI -> "Gemini की सीमा अभी खत्म है — थोड़ी देर बाद फिर कोशिश करो।"
        SpokenLang.ENGLISH -> "Gemini's limit is reached for now — try again in a bit."
    }

    /** Call fail / timeout / khaali jawab — imaandaar manaahi। */
    fun failedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ছবি বানানো যাচ্ছে না — একটু পরে চেষ্টা করো।"
        SpokenLang.HINDI -> "तस्वीर बन नहीं पा रही — थोड़ी देर बाद कोशिश करो।"
        SpokenLang.ENGLISH -> "I couldn't make the picture — try again in a bit."
    }

    /** Aakhiri tasveer hai hi nahi — SHARE / REGENERATE par imaandaar jawab। */
    fun noImageLine(kind: Kind, lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> when (kind) {
            Kind.SHARE -> "এখনো কোনো ছবি বানানো হয়নি — আগে 'ছবি বানাও' বলো।"
            else -> "আগে কী বানাবো বলো — এখনো কোনো ছবি বানানো হয়নি।"
        }
        SpokenLang.HINDI -> when (kind) {
            Kind.SHARE -> "अभी कोई तस्वीर बनी ही नहीं है — पहले 'तस्वीर बनाओ' बोलो।"
            else -> "पहले बोलो क्या बनाऊँ — अभी कोई तस्वीर बनी ही नहीं है।"
        }
        SpokenLang.ENGLISH -> when (kind) {
            Kind.SHARE -> "No picture has been made yet — first say 'make a picture'."
            else -> "First tell me what to make — no picture has been made yet."
        }
    }

    /** Tasveer ban gayi, par viewer khul nahi paya — gallery me hai। */
    fun viewFailedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ছবি বানানো হয়েছে, কিন্তু খুলতে পারলাম না — গ্যালারিতে দেখো।"
        SpokenLang.HINDI -> "तस्वीर बन गई है, पर खोल नहीं पाई — गैलरी में देखो।"
        SpokenLang.ENGLISH -> "The picture is made, but I couldn't open it — check the gallery."
    }

    /** Share screen khul gayi — bhejna user ke haath me। */
    fun shareOpenedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "শেয়ার করার পর্দা খুলে দিলাম — কাকে পাঠাবে বেছে নাও।"
        SpokenLang.HINDI -> "शेयर करने की स्क्रीन खोल दी है — किसे भेजना है चुन लो।"
        SpokenLang.ENGLISH -> "Opened the share screen — pick who to send it to."
    }

    /** Share screen khuli hi nahi। */
    fun shareFailedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "শেয়ার করতে পারলাম না — আবার বলো দেখি।"
        SpokenLang.HINDI -> "शेयर नहीं कर पाई — फिर से बोलो।"
        SpokenLang.ENGLISH -> "I couldn't open the share screen — please try again."
    }

    /** Phone lock hai — viewer/share khul nahi sakta (sirf background raasta)। */
    fun lockedLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "ফোন লক আছে — আনলক করে আবার বলো।"
        SpokenLang.HINDI -> "फ़ोन लॉक है — अनलॉक करके फिर बोलो।"
        SpokenLang.ENGLISH -> "The phone is locked — unlock it and say it again."
    }

    /** 'Display over other apps' anumati nahi (sirf background raasta)। */
    fun permissionLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI ->
            "ছবি খুলতে 'অন্য অ্যাপের ওপরে দেখানো' অনুমতি লাগবে — " +
                "অ্যাপের সেটিংসে গিয়ে চালু করো।"
        SpokenLang.HINDI ->
            "तस्वीर खोलने के लिए 'दूसरे ऐप्स पर दिखाने' की अनुमति " +
                "चाहिए — ऐप की सेटिंग्स में जाकर चालू करो।"
        SpokenLang.ENGLISH ->
            "Opening the picture needs the 'display over other apps' " +
                "permission — turn it on in the app settings."
    }

    /** Seedhi daud ki report — "jarvis_diag" ki "last_agent_run" par। */
    fun saveRouteDiag(context: Context, kind: Kind, ok: Boolean) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_agent_run",
                    "steps: imagegen(${kind.name.lowercase(Locale.ROOT)})=" +
                        if (ok) "OK" else "FAIL"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal kaam isse nahi rukta.
        }
    }

    /** Cache me bani tasveeron wala hissa। */
    private const val IMAGE_DIR_NAME = "images"
}

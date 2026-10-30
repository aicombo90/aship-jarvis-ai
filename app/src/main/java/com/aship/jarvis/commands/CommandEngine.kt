package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Result of handling one command: the reply Jarvis shows and speaks.
 *
 * [handled] is true when a recognizable command keyword was matched —
 * including when the action itself then failed with a specific reason
 * (app missing, overlay permission needed, ...). It is false only when
 * nothing was understood: empty input, or the Phase-3 fallback below.
 * The background wake service uses this to stay SILENT on stray noise
 * instead of babbling the fallback; the foreground UI always speaks.
 */
data class CommandResult(
    val responseText: String,
    val handled: Boolean
)

/**
 * Phase 1 offline, rule-based command engine.
 *
 * Understands Hindi written in Latin script (Hinglish, which is what the
 * Hindi speech recognizers usually return) plus plain English keywords.
 * Everything runs on-device; unknown commands get a spoken fallback that
 * explains online AI arrives in Phase 3.
 *
 * When [fromBackgroundService] is true (the wake service), activity
 * launches go through the overlay bridge (PhoneActions.*FromBackground)
 * so Android's background-launch block cannot silently eat them, and a
 * missing overlay permission is reported honestly instead of claiming
 * the app opened.
 *
 * v1.4 advanced pack: kisi bhi installed app ko naam bolkar kholna,
 * battery status, volume control, gaana (media) control, Google /
 * YouTube search, aur timer. Volume / media / battery ko koi activity
 * nahi chahiye — woh background se bhi seedha chalte hain; baaki
 * launch wale kaam overlay bridge se jaate hain, pehle ki tarah.
 *
 * v1.5 casual speech: halki-phulki boli bhi samajh aaye isliye
 * normalize ke baad bhare shabd (zara / bhai / please / ek / toh...)
 * khud hat jate hain, aur pakke checks ke baad token-containment
 * routing lambe vakya ke beech chhupe command ko bhi pakad leti
 * hai ("accha suno torch band kar do"). Do suraksha niyam: search
 * commands ki query is routing se pehle hi nikal chuki hoti hai
 * (kabhi nahi kat-ti), aur bina open-verb ke sirf app ka naam
 * lene se app nahi khulta.
 *
 * v17 mobile data: "mobile data on/off karo" Accessibility
 * service ke quick-settings tile se hota hai. Is raaste me koi
 * activity launch NAHI hoti, isliye v16 wala lock gate
 * (OverlayLauncher.LOCKED) is par lagu nahi hai — lock ho tab
 * bhi koshish hogi, aur panel/tile na mile to sachha jawab
 * milega. Service band ho to Maya pehle app se Accessibility
 * chalu karne ko kehti hai.
 */
class CommandEngine(
    private val context: Context,
    private val fromBackgroundService: Boolean = false
) {

    fun handle(rawText: String): CommandResult {
        val text = stripFillers(normalize(rawText.lowercase(Locale.ROOT).trim()))
        if (text.isEmpty()) {
            return CommandResult("कुछ सुनाई नहीं दिया, फिर से बोलिए।", false)
        }
        val first = dispatch(text)
        if (first.handled) return first
        // v1.9: background Vosk aksar shabd thode galat sunta hai
        // (Bengali lehja + chhota model) — exact match na mile to
        // kareeb-shabd sudhar kar dobara aazmao. Sudhra hua text
        // kisi pakke command se mile tabhi maanenge, warna pehle
        // wala fallback hi rahega (galat app khulne ka dar nahi).
        val corrected = fuzzyCorrect(text)
        if (corrected != text) {
            // v1.10: fuzzy sudhaar Devanagari roop de sakta hai
            // (jaise "इंस्टग्राम" -> "इंस्टाग्राम") — dobara
            // normalize karo taaki woh Latin keyword ban kar
            // fixed list se mile.
            val second = dispatch(stripFillers(normalize(corrected)))
            if (second.handled) return second
        }
        return first
    }

    /**
     * Har token ko jaane-pehchane command shabdon se milao; bahut
     * kareeb (1-2 akshar ka farq) ho to sahi shabd se badal do.
     * Alag script ke shabd aapas me kabhi kareeb nahi aate, isliye
     * Latin/Devanagari/Bangla ek hi set me surakshit hain.
     */
    private fun fuzzyCorrect(text: String): String {
        var changed = false
        val out = text.split(Regex("""\s+""")).map { token ->
            val clean = token.trim(',', '.', '।')
            if (clean.length < 4 || clean in FUZZY_VOCAB) {
                token
            } else {
                var best: String? = null
                var bestDist = Int.MAX_VALUE
                for (word in FUZZY_VOCAB) {
                    val d = levenshtein(clean, word)
                    if (d < bestDist) {
                        bestDist = d
                        best = word
                    }
                }
                // Command session sirf asli wake ke baad khulta hai,
                // isliye thoda udaar threshold theek hai — galat
                // pakda gaya shabd bhi zyada se zyada ek chhota
                // kaam (app kholna / time bolna) karega, koi bada
                // nuksaan nahi.
                val allowed = when {
                    clean.length >= 7 -> 3
                    clean.length >= 5 -> 2
                    else -> 1
                }
                if (best != null && bestDist in 1..allowed) {
                    changed = true
                    token.replace(clean, best)
                } else {
                    token
                }
            }
        }
        return if (changed) out.joinToString(" ") else text
    }

    /** Classic edit distance (chars = UTF-16 units; heuristic ke liye kaafi). */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    /** Normalize (aur filler-saaf) hue text par asal command matching. */
    private fun dispatch(text: String): CommandResult {

        // --- Alarm (checked first: it contains numbers) -------------------
        if (text.contains("alarm")) {
            val (hour, minute) = parseAlarmTime(text)
            return if (fromBackgroundService) {
                when (PhoneActions.setAlarmFromBackground(context, hour, minute)) {
                    LaunchOutcome.LAUNCHED -> alarmOpenedResult(hour, minute)
                    LaunchOutcome.PERMISSION_NEEDED ->
                        CommandResult(OVERLAY_PERMISSION_REPLY, true)
                    LaunchOutcome.LOCKED ->
                        CommandResult(LOCK_REPLY, true)
                    LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                        CommandResult("अलार्म ऐप नहीं खुल पाया।", true)
                }
            } else if (PhoneActions.setAlarm(context, hour, minute)) {
                alarmOpenedResult(hour, minute)
            } else {
                CommandResult("अलार्म ऐप नहीं खुल पाया।", true)
            }
        }

        // --- Timer (v1.4) ---------------------------------------------------
        // "time" wale check se PEHLE rakhna zaroori hai — "timer" shabd
        // ke andar "time" chhupa hai.
        if (text.contains("timer")) {
            return timerCommand(text)
        }

        // --- Torch / flashlight -------------------------------------------
        if (text.contains("torch") || text.contains("flashlight") || text.contains("flash light")) {
            val turnOff = text.contains("off") || text.contains("band")
            val ok = PhoneActions.setTorch(context, !turnOff)
            return if (ok) {
                CommandResult(if (turnOff) "टॉर्च बंद कर दिया।" else "टॉर्च जला दिया।", true)
            } else {
                CommandResult("इस फ़ोन पर टॉर्च कंट्रोल नहीं हो पाया।", true)
            }
        }

        // --- Battery status (v1.4) ------------------------------------------
        if (text.contains("battery")) {
            val status = PhoneActions.batteryStatus(context)
            return if (status == null) {
                CommandResult("बैटरी की जानकारी अभी नहीं मिल पाई।", true)
            } else {
                val (percent, charging) = status
                CommandResult(
                    if (charging) {
                        "बैटरी $percent% है और चार्जिंग हो रही है।"
                    } else {
                        "बैटरी $percent% है।"
                    },
                    true
                )
            }
        }

        // --- Mobile data on/off (v17) ---------------------------------------
        // Accessibility service quick-settings ka data tile dabati
        // hai — koi activity launch nahi hoti, isliye ye branch
        // OverlayLauncher se guzarti hi nahi aur v16 wala lock
        // gate (LOCKED) is par lagu nahi hai: lock ho tab bhi
        // koshish hogi (panel na khule to sachha "nahi mila"
        // jawab milega). Disha (on/off) wala shabd hona shart
        // hai — sirf "mobile data" bolne se kuch nahi hoga.
        val dataMention = text.contains("mobile data") || hasWord(text, "data")
        if (dataMention) {
            val turnOffData = hasWord(text, "off") || text.contains("band") ||
                text.contains("bondho")
            val turnOnData = hasWord(text, "on") || hasWord(text, "ऑन") ||
                hasWord(text, "অন") || text.contains("chalu") ||
                text.contains("chaalu") || text.contains("start")
            if (turnOffData || turnOnData) {
                return mobileDataCommand(turnOn = turnOnData && !turnOffData)
            }
        }

        // --- Volume (v1.4) ----------------------------------------------------
        // AudioManager se seedha hota hai — activity nahi chahiye, isliye
        // background se bhi bina overlay ke chalega.
        if (text.contains("volume") || text.contains("awaaz") || text.contains("mute")) {
            return volumeCommand(text)
        }

        // --- Google search (v1.4) ---------------------------------------------
        // Fixed-app loop se pehle: query ke andar "youtube" jaise shabd
        // bhi aa sakte hain ("google par youtube search karo").
        if (text.contains("google") &&
            (text.contains("search") || text.contains("dikhao") ||
                text.contains("khojo") || text.contains("dhundo") ||
                text.contains("google karo") || text.contains("google par"))
        ) {
            val query = extractQuery(text, "google")
            return if (query.isEmpty()) {
                // Sirf "google" bola gaya — Chrome khol do.
                openTarget(appTargets.first { it.first == "chrome" }.second)
            } else {
                openWebUrl(
                    "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"),
                    "Google पर '$query' सर्च कर दिया है।"
                )
            }
        }

        // --- YouTube search (v1.4) --------------------------------------------
        // "youtube kholo" se takraav nahi: yahan "search"/"dikhao" hona
        // zaroori hai; warna neeche wala aam app-open raasta chalega.
        if (text.contains("youtube") &&
            (text.contains("search") || text.contains("dikhao"))
        ) {
            val query = extractQuery(text, "youtube")
            if (query.isNotEmpty()) {
                return openWebUrl(
                    "https://www.youtube.com/results?search_query=" +
                        URLEncoder.encode(query, "UTF-8"),
                    "YouTube पर '$query' सर्च कर दिया है।"
                )
            }
        }

        // --- Open apps (fixed list) -----------------------------------------
        for ((keyword, target) in appTargets) {
            if (text.contains(keyword)) {
                return openTarget(target)
            }
        }

        // --- Media / gaana control (v1.4) -------------------------------------
        // AudioManager media keys — activity nahi chahiye, background se
        // bhi chalega. "play"/"pause" POORE shabd ki tarah dhoondho,
        // warna "MX player kholo" bhi yahan phas jayega.
        val nextPrev = text.contains("agla") || text.contains("pichla") ||
            text.contains("next") || text.contains("previous")
        val mediaContext = text.contains("gaana") || text.contains("music") ||
            text.contains("song") || hasWord(text, "play") || hasWord(text, "pause")
        if (nextPrev || mediaContext) {
            return when {
                text.contains("agla") || text.contains("next") ->
                    mediaResult(
                        PhoneActions.mediaNext(context),
                        "अगला गाना चला दिया है।"
                    )
                text.contains("pichla") || text.contains("previous") ->
                    mediaResult(
                        PhoneActions.mediaPrevious(context),
                        "पिछला गाना चला दिया है।"
                    )
                text.contains("pause") || text.contains("roko") || text.contains("band") ->
                    mediaResult(
                        PhoneActions.mediaPause(context),
                        "गाना रोक दिया है।"
                    )
                else ->
                    mediaResult(
                        PhoneActions.mediaPlay(context),
                        "गाना चला दिया है।"
                    )
            }
        }

        // --- Time -----------------------------------------------------------
        if (text.contains("time") || text.contains("samay") ||
            text.contains("baje hain") || text.contains("baj rahe")
        ) {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            return CommandResult("अभी समय है $time।", true)
        }

        // --- Date -----------------------------------------------------------
        if (text.contains("date") || text.contains("tarikh") || text.contains("taarikh")) {
            val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date())
            return CommandResult("आज की तारीख़ है $date।", true)
        }

        // --- Koi bhi app naam bolkar kholo (v1.4) ------------------------------
        // Fixed list ke BAAD: YouTube/Facebook jaise pakke naam hamesha
        // pehle wale raaste se khulenge. Yahan launcher ki saari apps ke
        // label se mila kar dhoondhte hain ("telegram kholo",
        // "mx player kholo").
        // v1.10: nanga "khol" bhi chalega ("instagram khol") —
        // contains("khol") me kholo/khol/khol do sab aa jate hain.
        if (text.contains("khol") || hasWord(text, "open")) {
            val spokenName = extractAppName(text)
            if (spokenName.isNotEmpty()) {
                // v1.10: seedha label na mile to galat-suna naam
                // sudhar kar dobara dhoondho ("इंस्टग्राम" ->
                // "इंस्टाग्राम" -> "instagram" -> label match).
                val match = findLauncherApp(spokenName)
                    ?: findLauncherApp(stripFillers(normalize(fuzzyCorrect(spokenName))))
                return if (match != null) {
                    openTarget(
                        AppTarget(match.label, TargetKind.PACKAGE, match.packageName)
                    )
                } else {
                    CommandResult(
                        "'$spokenName' नाम की ऐप इस फ़ोन में नहीं मिली।",
                        true
                    )
                }
            }
        }

        // --- Identity -------------------------------------------------------
        // v13: persona ka naam Maya hai (app/brand naam ASHIP JARVIS
        // AI hi rehta hai). Script wale naam-wale sawaal bhi yahin
        // pakdo — normalize unhe Latin me nahi badalta.
        if (text.contains("tum kaun ho") || text.contains("who are you") ||
            text.contains("tumhara naam") || text.contains("your name") ||
            text.contains("तुम कौन हो") || text.contains("तुम्हारा नाम") ||
            text.contains("তুমি কে") || text.contains("তোমার নাম")
        ) {
            return CommandResult("मेरा नाम माया है — आपकी अपनी माया।", true)
        }

        // --- Small talk -----------------------------------------------------
        if (text.contains("kaise ho")) {
            return CommandResult("मैं बिल्कुल ठीक हूँ। आप बोलिए, क्या सेवा करूँ?", true)
        }
        if (text.contains("thank")) {
            return CommandResult("आपका स्वागत है!", true)
        }
        if (text.contains("namaste") || text.contains("hello") ||
            text.contains("hey jarvis") || text.contains("hi jarvis") || text == "hi"
        ) {
            return CommandResult("नमस्ते! मैं सुन रहा हूँ। बोलिए, क्या करना है?", true)
        }

        // --- Token-containment routing (v1.5) --------------------------------
        // Pakke checks choot gaye, lekin koi jaana-pehchana command
        // shabd lambe vakya ke beech me chhupa ho ("accha suno torch
        // band kar do") to wahan bhej do. Search commands isse pehle
        // hi nikal chuki hain — unki query yahan kabhi nahi kat-ti.
        routeByTokens(text)?.let { return it }

        // --- Fallback ---------------------------------------------------------
        return CommandResult(
            "यह काम मैं अभी नहीं कर सकता। " +
                "हर अपडेट के साथ मैं नए काम सीखता रहूँगा।",
            false
        )
    }

    /**
     * Speech recognizers often return Devanagari or Bengali script
     * (e.g. "यूट्यूब खोलो" or "ইউটিউব খোলো") instead of Latin letters.
     * Map the known words onto the Latin keywords the matcher below uses,
     * and convert Bengali/Devanagari digits to ASCII for time parsing.
     */
    private fun normalize(input: String): String {
        var t = input
        val digitMap = mapOf(
            '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
            '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
            '০' to '0', '১' to '1', '২' to '2', '৩' to '3', '৪' to '4',
            '৫' to '5', '৬' to '6', '৭' to '7', '৮' to '8', '৯' to '9'
        )
        t = t.map { ch -> digitMap[ch] ?: ch }.joinToString("")
        val words = listOf(
            // App names (incl. common mis-hearings)
            "यूट्यूब" to "youtube", "युट्यूब" to "youtube", "यूटुब" to "youtube",
            "युटुब" to "youtube", "यूटूब" to "youtube", "यु टुब" to "youtube",
            "ইউটিউব" to "youtube", "যুটব" to "youtube", "য়ুটুব" to "youtube",
            "ইউটুব" to "youtube", "যুটুব" to "youtube", "ইউ টুব" to "youtube",
            "फेसबुक" to "facebook", "ফেসবুক" to "facebook",
            "व्हाट्सएप" to "whatsapp", "वाट्सएप" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp",
            "कैमरा" to "camera", "ক্যামেরা" to "camera",
            "क्रोम" to "chrome", "ক্রোম" to "chrome",
            "सेटिंग्स" to "settings", "सेटिंग" to "settings", "সেটিংস" to "settings",
            // Actions / objects
            "टॉर्च" to "torch", "টর্চ" to "torch",
            "फ्लैशलाइट" to "flashlight", "ফ্ল্যাশলাইট" to "flashlight",
            "अलार्म" to "alarm", "অ্যালার্ম" to "alarm",
            "बंद" to "band", "বন্ধ" to "band",
            // Time / date
            "समय" to "samay", "সময়" to "samay",
            "बजे" to "baje", "বাজে" to "baje",
            "तारीख" to "tarikh", "তারিখ" to "tarikh",
            // Small talk
            "नमस्ते" to "namaste", "নমস্কার" to "namaste",
            "धन्यवाद" to "thank", "ধন্যবাদ" to "thank",
            // v1.4: naye commands ke script roop
            "टेलीग्राम" to "telegram", "টেলিগ্রাম" to "telegram",
            "बैटरी" to "battery", "ব্যাটারি" to "battery",
            "वॉल्यूम" to "volume", "ভলিউম" to "volume",
            "आवाज़" to "awaaz", "आवाज" to "awaaz", "আওয়াজ" to "awaaz",
            "गाना" to "gaana", "গান" to "gaana",
            "चलाओ" to "chalao", "চালাও" to "chalao",
            "रोको" to "roko", "থামাও" to "roko",
            "खोलो" to "kholo", "খোলো" to "kholo",
            "मिनट" to "minute", "মিনিট" to "minute",
            "सेकंड" to "second", "সেকেন্ড" to "second",
            "घंटा" to "ghanta", "ঘণ্টা" to "ghanta",
            "टाइमर" to "timer", "টাইমার" to "timer",
            "सर्च" to "search", "সার্চ" to "search",
            "गूगल" to "google", "গুগল" to "google",
            "दिखाओ" to "dikhao", "দেখাও" to "dikhao",
            "बढ़ाओ" to "badhao", "বাড়াও" to "badhao",
            "अगला" to "agla", "পরের" to "agla",
            "पिछला" to "pichla",
            "म्यूट" to "mute", "মিউট" to "mute",
            "तेज़" to "tez", "तेज" to "tez",
            // v17: mobile data ke script roop. Vakya wala roop
            // pehle, phir alag-alag shabd — dono raaston se
            // "mobile data" hi banna chahiye.
            "मोबाइल डेटा" to "mobile data", "মোবাইল ডেটা" to "mobile data",
            "মোবাইল ডাটা" to "mobile data",
            "मोबाइल" to "mobile", "মোবাইল" to "mobile",
            "डेटा" to "data", "ডেটা" to "data", "ডাটা" to "data",
            "चालू" to "chalu", "চালু" to "chalu",
            // v1.5: torch / alarm / battery / volume ke aur galat-sune roop
            "टार्च" to "torch", "टोर्च" to "torch",
            "টার্চ" to "torch", "টোর্চ" to "torch",
            "अलारम" to "alarm", "एलार्म" to "alarm",
            "আলারম" to "alarm", "আলার্ম" to "alarm",
            "बैट्री" to "battery", "बेटरी" to "battery",
            "ব্যাট্রি" to "battery", "বেটারি" to "battery",
            "वॉल्युम" to "volume", "वाल्यूम" to "volume", "ভলিয়ুম" to "volume",
            // v1.8 fix: time poochhne par jawab nahi milta tha — Hindi
            // recognizer aksar Devanagari me "टाइम" / "बजे हैं" /
            // "बज रहे" deta hai, jo upar kahin map nahi hote the.
            // (List ke ANT me isliye: "टाइमर" -> "timer" aur
            // "बजे" -> "baje" jaise pehle wale map pehle chal jayein,
            // warna "टाइम" ke andar chhupa "टाइमर" toot jayega.)
            "टाइम" to "time", "टाईम" to "time",
            "हैं" to "hain",
            "बज" to "baj", "रहे" to "rahe",
            // v1.8 fix: recognizer kabhi app ke naam ko tod kar sunta
            // hai ("फेस बुक") — bina space wale roop upar hain, ye
            // space wale roop yahan mil jayein.
            "फेस बुक" to "facebook", "यू ट्यूब" to "youtube",
            "यू टुब" to "youtube",
            // v1.10: naye apps ke Devanagari/Bangla roop + nanga "khol".
            // "खोल" ANT me isliye: "खोलो" pehle kha jaye, warna
            // "खोलो" ka "खोल" hissa badal kar "kholो" ban jayega.
            "इंस्टाग्राम" to "instagram", "ইনস্টাগ্রাম" to "instagram",
            "इंस्टा" to "insta", "ইনস্টা" to "insta",
            "जीमेल" to "gmail", "জিমেইল" to "gmail",
            "मैप्स" to "maps", "ম্যাপস" to "maps",
            "प्ले स्टोर" to "play store", "প্লে স্টোর" to "play store",
            "फोटो" to "photos", "ফটো" to "photos",
            "फ़ोनपे" to "phonepe", "ফোনপে" to "phonepe",
            "पेटीएम" to "paytm", "পেটিএম" to "paytm",
            "स्पॉटिफाई" to "spotify", "স্পটিফাই" to "spotify",
            "स्नैपचैट" to "snapchat", "স্ন্যাপচ্যাট" to "snapchat",
            "मैसेज" to "messages", "মেসেজ" to "messages",
            "कैलकुलेटर" to "calculator", "ক্যালকুলেটর" to "calculator",
            "घड़ी" to "clock", "ঘড়ি" to "clock",
            "गूगल पे" to "gpay", "গুগল পে" to "gpay",
            "खोल" to "khol", "খোল" to "khol"
        )
        for ((from, to) in words) {
            t = t.replace(from, to)
        }
        // v12: "pesbuk" / "pes book" — Facebook ke Latin galat-sune
        // roop. Script wale roop (पेसबुक aadi) fuzzy correction se
        // sambhal jate hain, par ye Latin roop usse bahut door hain
        // (distance 5), isliye yahan spasht map zaroori hai.
        t = t.replace("you tube", "youtube")
            .replace("face book", "facebook")
            .replace("pes book", "facebook")
            .replace("pesbuk", "facebook")
            .replace("whats app", "whatsapp")
        return t
    }

    /**
     * v1.5 casual speech: bina-matlab ke bhare shabd hatao, taaki
     * "zara torch jala do na" jaisi halki-phulki boli bhi pakke
     * commands se mil jaye. Sirf POORE shabd (token) hat-te hain —
     * "gaana" ke andar chhupa "na" kabhi nahi katega. Set
     * jaan-boojh kar chhota aur surakshit rakha hai.
     */
    private fun stripFillers(text: String): String =
        text.split(Regex("""\s+"""))
            .filter { token ->
                token.trim(',', '.', '।').lowercase(Locale.ROOT) !in FILLER_TOKENS
            }
            .joinToString(" ")

    /**
     * v1.5 token-containment routing: dispatch ke pakke checks
     * choot jayein to dekho koi jaana-pehchana command shabd token
     * ki tarah kahin hai ya nahi — sabse lambe / sabse pakke shabd
     * pehle. Mile to usi command ka pakka (canonical) roop
     * dispatch ko de do; woh roop hamesha kisi prathmik branch me
     * phansta hai, isliye recursion ek hi seedhi ka hai.
     *
     * App kholne ke liye open-verb ("kholo / khol / open") hona
     * shart hai — sirf app ka naam lene se app nahi khulega.
     */
    private fun routeByTokens(text: String): CommandResult? {
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val tokenSet = tokens.toSet()

        if ("flashlight" in tokenSet) return dispatch(deviceSynth("flashlight", tokenSet))
        if ("battery" in tokenSet) return dispatch("battery")
        if ("tarikh" in tokenSet) return dispatch("date")
        if ("volume" in tokenSet || "awaaz" in tokenSet) {
            return dispatch(volumeSynth(tokenSet))
        }
        if ("torch" in tokenSet) return dispatch(deviceSynth("torch", tokenSet))
        if ("alarm" in tokenSet) return dispatch("alarm")
        if ("timer" in tokenSet) return dispatch("timer")
        if ("pause" in tokenSet) return dispatch("pause karo")
        if ("samay" in tokenSet || "baja" in tokenSet || "baje" in tokenSet ||
            "time" in tokenSet
        ) {
            return dispatch("samay")
        }
        if ("gaana" in tokenSet || "music" in tokenSet || "song" in tokenSet ||
            "agla" in tokenSet || "pichla" in tokenSet
        ) {
            return dispatch(mediaSynth(tokenSet))
        }
        if ("mute" in tokenSet) return dispatch("volume mute")
        if ("play" in tokenSet) return dispatch("play karo")
        if ("date" in tokenSet) return dispatch("date")

        // App kholna: open-verb ke saath hi chalega.
        if (tokenSet.any { it in OPEN_VERB_TOKENS }) {
            for ((keyword, _) in appTargets) {
                if (keyword in tokenSet) return dispatch("$keyword kholo")
            }
            val spokenName = tokens
                .filter { it !in OPEN_VERB_TOKENS && it !in APP_NAME_STOP_TOKENS }
                .joinToString(" ")
            if (spokenName.isNotEmpty() && findLauncherApp(spokenName) != null) {
                return dispatch("$spokenName kholo")
            }
        }
        return null
    }

    /** Torch/flashlight ka pakka roop — "band/off" ho to band, warna jalao. */
    private fun deviceSynth(device: String, tokenSet: Set<String>): String =
        if ("band" in tokenSet || "off" in tokenSet) {
            "$device band"
        } else {
            "$device jalao"
        }

    /** Volume ka pakka roop — disha wale shabd hon to wahi, warna sirf haal. */
    private fun volumeSynth(tokenSet: Set<String>): String = when {
        "mute" in tokenSet || "band" in tokenSet || "off" in tokenSet -> "volume band"
        "full" in tokenSet || "max" in tokenSet -> "volume full karo"
        "kam" in tokenSet || "ghata" in tokenSet || "dheere" in tokenSet ->
            "volume kam karo"
        "badha" in tokenSet || "tez" in tokenSet || "zyada" in tokenSet ->
            "volume badhao"
        else -> "volume"
    }

    /** Gaana/media ka pakka roop. */
    private fun mediaSynth(tokenSet: Set<String>): String = when {
        "agla" in tokenSet || "next" in tokenSet -> "agla gaana"
        "pichla" in tokenSet || "previous" in tokenSet -> "pichla gaana"
        "pause" in tokenSet || "roko" in tokenSet || "band" in tokenSet -> "gaana roko"
        else -> "gaana chalao"
    }

    private fun alarmOpenedResult(hour: Int?, minute: Int?): CommandResult =
        if (hour != null) {
            CommandResult(
                "अलार्म स्क्रीन खोल दिया है, $hour बजकर ${minute ?: 0} मिनट पर। कन्फ़र्म कर दीजिए।",
                true
            )
        } else {
            CommandResult("अलार्म स्क्रीन खोल दिया है, समय सेट करके कन्फ़र्म कर दीजिए।", true)
        }

    // ------------------------------------------------------------------
    // v17: mobile data
    // ------------------------------------------------------------------

    /**
     * Mobile data on/off — Accessibility service se. Service
     * band ho to koshish hi nahi hoti: Maya pehle app kholkar
     * Accessibility chalu karne ko kehti hai.
     *
     * Service ka kaam background thread par hota hai aur natija
     * callback se aata hai; CommandEngine ka jawab sync chahiye,
     * isliye yahan ek bandha hua intezaar (latch, ~4 s) hai.
     * Bulane wale dono raaste (wake service ka executor,
     * saamne wali screen) itna intezaar jhel sakte hain — tile
     * dhoondhne ki kul seema ~2.5 s hi hai. Intezaar poora na
     * ho to "nahi mila" wala sach ke sabse kareeb jawab milta
     * hai.
     */
    private fun mobileDataCommand(turnOn: Boolean): CommandResult {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return CommandResult(DATA_NEEDS_ACCESSIBILITY_REPLY, true)
        }
        val latch = CountDownLatch(1)
        // Latch ki wajah se callback wale thread ki likhai
        // await() ke baad dikhna pakka hai — alag jhanda nahi
        // chahiye.
        var outcome: JarvisAccessibilityService.DataResult? = null
        try {
            JarvisAccessibilityService.toggleMobileData(context, turnOn) { result ->
                outcome = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return CommandResult(DATA_NOT_FOUND_REPLY, true)
        }
        val completed = try {
            latch.await(DATA_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed) {
            return CommandResult(DATA_NOT_FOUND_REPLY, true)
        }
        return when (outcome) {
            JarvisAccessibilityService.DataResult.SUCCESS -> CommandResult(
                if (turnOn) "मोबाइल डेटा ऑन कर दिया।" else "मोबाइल डेटा बंद कर दिया।",
                true
            )
            JarvisAccessibilityService.DataResult.SERVICE_OFF ->
                CommandResult(DATA_NEEDS_ACCESSIBILITY_REPLY, true)
            JarvisAccessibilityService.DataResult.TILE_NOT_FOUND,
            JarvisAccessibilityService.DataResult.PANEL_NOT_OPENED,
            null -> CommandResult(DATA_NOT_FOUND_REPLY, true)
        }
    }

    // ------------------------------------------------------------------
    // v1.4 helpers
    // ------------------------------------------------------------------

    /** "5 minute ka timer lagao" / "30 second timer" / "1 ghante ka timer". */
    private fun timerCommand(text: String): CommandResult {
        val match = Regex(
            """(\d+)\s*(ghante|ghanta|hours|hour|minutes|minute|min|seconds|second|sec)"""
        ).find(text)
            ?: return CommandResult(
                "कितने समय का टाइमर लगाऊँ? ऐसे बोलिए: 5 मिनट का टाइमर लगाओ।",
                true
            )
        val amount = match.groupValues[1].toInt()
        val unit = match.groupValues[2]
        val totalSeconds = when {
            unit.startsWith("ghant") || unit.startsWith("hour") -> amount * 3600
            unit.startsWith("min") -> amount * 60
            else -> amount
        }
        if (totalSeconds <= 0) {
            return CommandResult("टाइमर का समय समझ नहीं आया।", true)
        }
        val label = when {
            totalSeconds % 3600 == 0 -> "${totalSeconds / 3600} घंटे"
            totalSeconds % 60 == 0 -> "${totalSeconds / 60} मिनट"
            else -> "$totalSeconds सेकंड"
        }
        val successReply = "$label का टाइमर लगा दिया है।"
        return if (fromBackgroundService) {
            when (PhoneActions.setTimerFromBackground(context, totalSeconds)) {
                LaunchOutcome.LAUNCHED -> CommandResult(successReply, true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.LOCKED ->
                    CommandResult(LOCK_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult("टाइमर नहीं लग पाया।", true)
            }
        } else if (PhoneActions.setTimer(context, totalSeconds)) {
            CommandResult(successReply, true)
        } else {
            CommandResult("टाइमर नहीं लग पाया।", true)
        }
    }

    /** Volume badhao / kam / full / mute — ya sirf poochha ho to haal batao. */
    private fun volumeCommand(text: String): CommandResult {
        val done: Boolean
        val actionText: String
        when {
            text.contains("full") || text.contains("max") -> {
                done = PhoneActions.maximizeMusicVolume(context)
                actionText = "वॉल्यूम फुल कर दिया है।"
            }
            text.contains("mute") || text.contains("band") || text.contains("off") -> {
                done = PhoneActions.muteMusicVolume(context)
                actionText = "वॉल्यूम बंद (म्यूट) कर दिया है।"
            }
            text.contains("badha") || text.contains("tez") ||
                text.contains("zyada") || text.contains("increase") -> {
                done = PhoneActions.raiseMusicVolume(context)
                actionText = "वॉल्यूम बढ़ा दिया है।"
            }
            text.contains("kam") || text.contains("ghata") ||
                text.contains("dheere") || text.contains("decrease") -> {
                done = PhoneActions.lowerMusicVolume(context)
                actionText = "वॉल्यूम कम कर दिया है।"
            }
            else -> {
                // Sirf "volume" / "awaaz kitni hai" — haal bata do.
                val percent = PhoneActions.musicVolumePercent(context)
                return if (percent >= 0) {
                    CommandResult("वॉल्यूम अभी $percent% है।", true)
                } else {
                    CommandResult("वॉल्यूम की जानकारी नहीं मिल पाई।", true)
                }
            }
        }
        if (!done) {
            return CommandResult("वॉल्यूम कंट्रोल नहीं हो पाया।", true)
        }
        val percent = PhoneActions.musicVolumePercent(context)
        return if (percent >= 0) {
            CommandResult("$actionText अब वॉल्यूम $percent% है।", true)
        } else {
            CommandResult(actionText, true)
        }
    }

    private fun mediaResult(ok: Boolean, successReply: String): CommandResult =
        if (ok) {
            CommandResult(successReply, true)
        } else {
            CommandResult("मीडिया कंट्रोल नहीं हो पाया।", true)
        }

    /** Ek URL browser me kholo — background ho to overlay bridge se. */
    private fun openWebUrl(url: String, successReply: String): CommandResult {
        return if (fromBackgroundService) {
            when (PhoneActions.openUrlFromBackground(context, url)) {
                LaunchOutcome.LAUNCHED -> CommandResult(successReply, true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.LOCKED ->
                    CommandResult(LOCK_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult("ब्राउज़र नहीं खुल पाया।", true)
            }
        } else if (PhoneActions.openUrl(context, url)) {
            CommandResult(successReply, true)
        } else {
            CommandResult("ब्राउज़र नहीं खुल पाया।", true)
        }
    }

    /**
     * Search command se asli query nikalo: platform ka naam aur
     * "search karo / par / me / dikhao" jaise bandhe shabd hata do,
     * baaki jo bacha wahi query hai.
     * "google par mausam search karo" -> "mausam"
     * "youtube me gaana dikhao" -> "gaana"
     */
    private fun extractQuery(text: String, platform: String): String {
        val stop = setOf(
            platform, "par", "me", "search", "karo", "kar", "karke",
            "dikhao", "dikha", "dhundo", "khojo"
        )
        return text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।') }
            .filter { it.isNotEmpty() && it !in stop }
            .joinToString(" ")
    }

    /** "<name> kholo" se app ka naam nikalo — bandhe shabd hata kar. */
    private fun extractAppName(text: String): String {
        val stop = setOf(
            "kholo", "khol", "open", "karo", "kar", "do",
            "please", "app", "ko", "yeh", "ye", "zara"
        )
        return text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।') }
            .filter { it.isNotEmpty() && it !in stop }
            .joinToString(" ")
    }

    private var launcherAppsCache: List<PhoneActions.LauncherApp>? = null

    /** Launcher apps ki list — is engine instance ke liye ek baar hi. */
    private fun launcherApps(): List<PhoneActions.LauncherApp> =
        launcherAppsCache
            ?: PhoneActions.listLaunchableApps(context).also { launcherAppsCache = it }

    /**
     * Bole gaye naam ko kisi installed app ke label se milao.
     * Pehle exact (jagah/space hatakar), phir package ke aakhiri hisse
     * se, phir andar-bahar (contains) — sabse chhota label jeetega,
     * taaki "telegram" seedha Telegram par jaye, kisi lambe naam
     * wali app par nahi.
     */
    private fun findLauncherApp(spokenName: String): PhoneActions.LauncherApp? {
        val needle = squash(spokenName)
        if (needle.length < 2) return null
        val apps = launcherApps()
        apps.firstOrNull { squash(it.label) == needle }?.let { return it }
        apps.firstOrNull {
            squash(it.packageName.substringAfterLast('.')) == needle
        }?.let { return it }
        if (needle.length < 3) return null
        return apps.filter {
            val label = squash(it.label)
            label.contains(needle) || needle.contains(label)
        }.minByOrNull { squash(it.label).length }
    }

    /** Lowercase karke sirf akshar/ank rakho — milaane ke liye. */
    private fun squash(s: String): String =
        s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Poora shabd mila ya nahi ("play" mile, "player" na mile). */
    private fun hasWord(text: String, word: String): Boolean =
        text.split(Regex("""\s+""")).any { it.trim(',', '.', '।') == word }

    private fun openTarget(target: AppTarget): CommandResult {
        if (fromBackgroundService) {
            // Background launches must cross the overlay bridge, and a
            // missing permission is said out loud — never faked.
            val outcome = when (target.kind) {
                TargetKind.PACKAGE ->
                    PhoneActions.openAppFromBackground(context, target.packageName!!)
                TargetKind.CAMERA -> PhoneActions.openCameraFromBackground(context)
                TargetKind.SETTINGS -> PhoneActions.openSettingsFromBackground(context)
            }
            return when (outcome) {
                LaunchOutcome.LAUNCHED ->
                    CommandResult("${target.displayName} खोल दिया है।", true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.LOCKED ->
                    CommandResult(LOCK_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult(
                        "${target.displayName} इस फ़ोन में नहीं मिला या खुल नहीं पाया।",
                        true
                    )
            }
        }
        val opened = when (target.kind) {
            TargetKind.PACKAGE -> PhoneActions.openApp(context, target.packageName!!)
            TargetKind.CAMERA -> PhoneActions.openCamera(context)
            TargetKind.SETTINGS -> PhoneActions.openSettings(context)
        }
        return if (opened) {
            CommandResult("${target.displayName} खोल दिया है।", true)
        } else {
            CommandResult("${target.displayName} इस फ़ोन में नहीं मिला या खुल नहीं पाया।", true)
        }
    }

    /**
     * Pulls a time out of commands like "alarm set karo 7:30 par",
     * "alarm lagao 7 baje" or "set alarm for 7 pm". Returns (null, null)
     * when no time is mentioned.
     */
    private fun parseAlarmTime(text: String): Pair<Int?, Int?> {
        val colonMatch = Regex("""(\d{1,2})[:.](\d{2})""").find(text)
        if (colonMatch != null) {
            val hour = colonMatch.groupValues[1].toInt()
            val minute = colonMatch.groupValues[2].toInt()
            if (hour in 0..23 && minute in 0..59) return hour to minute
        }
        val bajeMatch = Regex("""(\d{1,2})\s*baje""").find(text)
        if (bajeMatch != null) {
            val hour = bajeMatch.groupValues[1].toInt()
            if (hour in 0..23) return hour to 0
        }
        val amPmMatch = Regex("""(\d{1,2})\s*(am|pm)""").find(text)
        if (amPmMatch != null) {
            var hour = amPmMatch.groupValues[1].toInt()
            val isPm = amPmMatch.groupValues[2] == "pm"
            if (hour in 1..12) {
                if (isPm && hour != 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                return hour to 0
            }
        }
        return null to null
    }

    private enum class TargetKind { PACKAGE, CAMERA, SETTINGS }

    private data class AppTarget(
        val displayName: String,
        val kind: TargetKind,
        val packageName: String? = null
    )

    /** Keyword (as spoken) -> what to open. Order matters: "settings" before "setting". */
    private val appTargets: List<Pair<String, AppTarget>> = listOf(
        "youtube" to AppTarget("YouTube", TargetKind.PACKAGE, "com.google.android.youtube"),
        "facebook" to AppTarget("Facebook", TargetKind.PACKAGE, "com.facebook.katana"),
        "whatsapp" to AppTarget("WhatsApp", TargetKind.PACKAGE, "com.whatsapp"),
        // v1.10: Instagram + aam apps — sirf naam bolne se bhi
        // khulenge, jaise Facebook/YouTube khulte hain. ORDER:
        // "phonepe" "phone" se PEHLE, warna "phonepe" me chhupa
        // "phone" dialer khol dega.
        "instagram" to AppTarget("Instagram", TargetKind.PACKAGE, "com.instagram.android"),
        "insta" to AppTarget("Instagram", TargetKind.PACKAGE, "com.instagram.android"),
        "telegram" to AppTarget("Telegram", TargetKind.PACKAGE, "org.telegram.messenger"),
        "gmail" to AppTarget("Gmail", TargetKind.PACKAGE, "com.google.android.gm"),
        "maps" to AppTarget("Google Maps", TargetKind.PACKAGE, "com.google.android.apps.maps"),
        "play store" to AppTarget("Play Store", TargetKind.PACKAGE, "com.android.vending"),
        "photos" to AppTarget("Photos", TargetKind.PACKAGE, "com.google.android.apps.photos"),
        "gallery" to AppTarget("Photos", TargetKind.PACKAGE, "com.google.android.apps.photos"),
        "phonepe" to AppTarget("PhonePe", TargetKind.PACKAGE, "com.phonepe.app"),
        "phone" to AppTarget("Phone", TargetKind.PACKAGE, "com.google.android.dialer"),
        "messages" to AppTarget("Messages", TargetKind.PACKAGE, "com.google.android.apps.messaging"),
        "clock" to AppTarget("Clock", TargetKind.PACKAGE, "com.google.android.deskclock"),
        "calculator" to AppTarget("Calculator", TargetKind.PACKAGE, "com.google.android.calculator"),
        "spotify" to AppTarget("Spotify", TargetKind.PACKAGE, "com.spotify.music"),
        "snapchat" to AppTarget("Snapchat", TargetKind.PACKAGE, "com.snapchat.android"),
        "mx player" to AppTarget("MX Player", TargetKind.PACKAGE, "com.mxtech.videoplayer.ad"),
        "paytm" to AppTarget("Paytm", TargetKind.PACKAGE, "net.one97.paytm"),
        "gpay" to AppTarget("Google Pay", TargetKind.PACKAGE, "com.google.android.apps.nbu.paisa.user"),
        "camera" to AppTarget("Camera", TargetKind.CAMERA),
        "chrome" to AppTarget("Chrome", TargetKind.PACKAGE, "com.android.chrome"),
        "settings" to AppTarget("Settings", TargetKind.SETTINGS),
        "setting" to AppTarget("Settings", TargetKind.SETTINGS)
    )

    private companion object {
        /** Spoken when a background launch needs the overlay permission. */
        const val OVERLAY_PERMISSION_REPLY =
            "इसके लिए 'Display over other apps' अनुमति चाहिए — " +
                "ऐप खोलकर नीचे वाला बटन दबाइए।"

        /**
         * v16: phone lock ho to background se koi app nahi khulta —
         * Maya pehle unlock karne ko kehti hai (v15 wali Devanagari
         * shaili). Ye jawab handled=true ke saath jata hai, isliye
         * wake service me na retry lagta hai, na chup vida.
         */
        const val LOCK_REPLY =
            "फ़ोन लॉक है, पहले अनलॉक कर लो, फिर खोल देती हूँ।"

        /**
         * v17: mobile data ke liye Accessibility service chalu
         * na ho to Maya ka jawab (Devanagari, v15 wali shaili).
         */
        const val DATA_NEEDS_ACCESSIBILITY_REPLY =
            "पहले ऐप खोलकर Accessibility ऑन कर लो, " +
                "फिर मैं डेटा ऑन कर पाऊँगी।"

        /** v17: data tile na mile / panel na khule to sachha jawab. */
        const val DATA_NOT_FOUND_REPLY =
            "डेटा वाला बटन मुझे नहीं मिला, एक बार ख़ुद देख लो।"

        /** v17: Accessibility toggle ke natije ka adhiktam intezaar. */
        const val DATA_COMMAND_WAIT_MS = 4_000L

        /**
         * v1.5: casual boli ke bhare shabd (sirf poore token ki
         * tarah milne par hat-te hain). Set jaan-boojh kar chhota
         * hai — zyada bada set asli shabdon ko kha sakta hai.
         */
        val FILLER_TOKENS = setOf(
            "zara", "bhai", "yar", "please", "ek", "toh", "na",
            "just", "hey",
            "একটু", "তো"
        )

        /** v1.5: token-routing me app kholne ke liye zaroori kriya shabd. */
        val OPEN_VERB_TOKENS = setOf("kholo", "khol", "open")

        /**
         * v1.9: fuzzy sudhaar ke liye jaane-pehchane command shabd.
         * Normalize ke baad bachne wale galat-sune roop (Latin
         * misspelling ya Devanagari/Bangla variant) inhi se milaye
         * jate hain — sirf bahut kareeb wale hi badle jate hain.
         */
        val FUZZY_VOCAB = setOf(
            // Latin (Vosk / system recognizer ki galat spelling)
            "youtube", "facebook", "whatsapp", "telegram", "camera",
            "chrome", "settings", "torch", "flashlight", "battery",
            "volume", "gaana", "music", "song", "alarm", "timer",
            "google", "search", "dikhao", "kholo", "khol", "open",
            "time", "samay", "baje", "baja", "date", "tarikh",
            "play", "pause", "mute", "roko", "band", "chalao",
            "tez", "kam", "badhao", "agla", "pichla", "next",
            "previous", "awaaz", "full", "max",
            // v17: mobile data ("deta" -> "data" jaise sudhaar)
            "data", "mobile",
            // Devanagari (normalize me na hone wale galat-sune roop)
            "यूट्यूब", "फेसबुक", "व्हाट्सएप", "टेलीग्राम", "कैमरा",
            "क्रोम", "सेटिंग्स", "टॉर्च", "बैटरी", "वॉल्यूम",
            "गाना", "अलार्म", "टाइमर", "गूगल", "सर्च", "दिखाओ",
            "खोलो", "समय", "बजे", "तारीख", "चलाओ", "रोको",
            "बंद", "अगला", "पिछला", "म्यूट", "आवाज़", "टाइम",
            // Bangla
            "ইউটিউব", "ফেসবুক", "হোয়াটসঅ্যাপ", "টেলিগ্রাম",
            "ক্যামেরা", "ক্রোম", "সেটিংস", "টর্চ", "ব্যাটারি",
            "ভলিউম", "গান", "অ্যালার্ম", "টাইমার", "গুগল",
            "সার্চ", "দেখাও", "খোলো", "সময়", "বাজে", "তারিখ",
            "চালাও", "থামাও", "বন্ধ", "পরের", "মিউট", "আওয়াজ",
            // v1.10: naye apps (Latin + Devanagari/Bangla roop)
            "instagram", "insta", "telegram", "gmail", "maps",
            "store", "photos", "gallery", "phonepe", "phone",
            "messages", "clock", "calculator", "spotify", "snapchat",
            "paytm", "gpay",
            "इंस्टाग्राम", "ইনস্টাগ্রাম", "इंस्टा", "ইনস্টা",
            "टेलीग्राम", "টেলিগ্রাম", "जीमेल", "জিমেইল",
            "मैप्स", "ম্যাপস", "फोटो", "ফটো",
            "फ़ोनपे", "ফোনপে", "पेटीएम", "পেটিএম",
            "स्पॉटिफाई", "স্পটিফাই", "स्नैपचैट", "স্ন্যাপচ্যাট",
            "मैसेज", "মেসেজ", "कैलकुलेटर", "ক্যালকুলেটর",
            "घड़ी", "ঘড়ি"
        )

        /** v1.5: app ke naam se pehle/peeche aane wale bandhe shabd. */
        val APP_NAME_STOP_TOKENS = setOf("karo", "kar", "do", "app", "ko", "yeh", "ye")
    }
}

package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 */
class CommandEngine(
    private val context: Context,
    private val fromBackgroundService: Boolean = false
) {

    fun handle(rawText: String): CommandResult {
        val text = stripFillers(normalize(rawText.lowercase(Locale.ROOT).trim()))
        if (text.isEmpty()) {
            return CommandResult("Kuch sunai nahi diya, phir se boliye.", false)
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
            val second = dispatch(corrected)
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
                    LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                        CommandResult("Alarm app nahi khul paya.", true)
                }
            } else if (PhoneActions.setAlarm(context, hour, minute)) {
                alarmOpenedResult(hour, minute)
            } else {
                CommandResult("Alarm app nahi khul paya.", true)
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
                CommandResult(if (turnOff) "Torch band kar diya." else "Torch jala diya.", true)
            } else {
                CommandResult("Is phone par torch control nahi ho paya.", true)
            }
        }

        // --- Battery status (v1.4) ------------------------------------------
        if (text.contains("battery")) {
            val status = PhoneActions.batteryStatus(context)
            return if (status == null) {
                CommandResult("Battery ki jaankari abhi nahi mil payi.", true)
            } else {
                val (percent, charging) = status
                CommandResult(
                    if (charging) {
                        "Battery $percent% hai aur charging ho rahi hai."
                    } else {
                        "Battery $percent% hai."
                    },
                    true
                )
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
                    "Google par '$query' search kar diya hai."
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
                    "YouTube par '$query' search kar diya hai."
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
                        "Agla gaana chala diya hai."
                    )
                text.contains("pichla") || text.contains("previous") ->
                    mediaResult(
                        PhoneActions.mediaPrevious(context),
                        "Pichla gaana chala diya hai."
                    )
                text.contains("pause") || text.contains("roko") || text.contains("band") ->
                    mediaResult(
                        PhoneActions.mediaPause(context),
                        "Gaana rok diya hai."
                    )
                else ->
                    mediaResult(
                        PhoneActions.mediaPlay(context),
                        "Gaana chala diya hai."
                    )
            }
        }

        // --- Time -----------------------------------------------------------
        if (text.contains("time") || text.contains("samay") ||
            text.contains("baje hain") || text.contains("baj rahe")
        ) {
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            return CommandResult("Abhi samay hai $time.", true)
        }

        // --- Date -----------------------------------------------------------
        if (text.contains("date") || text.contains("tarikh") || text.contains("taarikh")) {
            val date = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date())
            return CommandResult("Aaj ki date hai $date.", true)
        }

        // --- Koi bhi app naam bolkar kholo (v1.4) ------------------------------
        // Fixed list ke BAAD: YouTube/Facebook jaise pakke naam hamesha
        // pehle wale raaste se khulenge. Yahan launcher ki saari apps ke
        // label se mila kar dhoondhte hain ("telegram kholo",
        // "mx player kholo").
        if (text.contains("kholo") || text.contains("khol do") || hasWord(text, "open")) {
            val spokenName = extractAppName(text)
            if (spokenName.isNotEmpty()) {
                val match = findLauncherApp(spokenName)
                return if (match != null) {
                    openTarget(
                        AppTarget(match.label, TargetKind.PACKAGE, match.packageName)
                    )
                } else {
                    CommandResult(
                        "'$spokenName' naam ki app is phone me nahi mili.",
                        true
                    )
                }
            }
        }

        // --- Identity -------------------------------------------------------
        if (text.contains("tum kaun ho") || text.contains("who are you") ||
            text.contains("tumhara naam") || text.contains("your name")
        ) {
            return CommandResult("Main Aship Jarvis hoon, aapka personal voice assistant.", true)
        }

        // --- Small talk -----------------------------------------------------
        if (text.contains("kaise ho")) {
            return CommandResult("Main bilkul theek hoon. Aap boliye, kya seva karun?", true)
        }
        if (text.contains("thank")) {
            return CommandResult("Aapka swagat hai!", true)
        }
        if (text.contains("namaste") || text.contains("hello") ||
            text.contains("hey jarvis") || text.contains("hi jarvis") || text == "hi"
        ) {
            return CommandResult("Namaste! Main sun raha hoon. Boliye, kya karna hai?", true)
        }

        // --- Token-containment routing (v1.5) --------------------------------
        // Pakke checks choot gaye, lekin koi jaana-pehchana command
        // shabd lambe vakya ke beech me chhupa ho ("accha suno torch
        // band kar do") to wahan bhej do. Search commands isse pehle
        // hi nikal chuki hain — unki query yahan kabhi nahi kat-ti.
        routeByTokens(text)?.let { return it }

        // --- Fallback ---------------------------------------------------------
        return CommandResult(
            "Ye kaam main abhi nahi kar sakta. " +
                "Har update ke saath main naye kaam seekhta rahunga.",
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
            "यू टुब" to "youtube"
        )
        for ((from, to) in words) {
            t = t.replace(from, to)
        }
        t = t.replace("you tube", "youtube")
            .replace("face book", "facebook")
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
                "Alarm screen khol diya hai, $hour bajkar ${minute ?: 0} minute par. Confirm kar dijiye.",
                true
            )
        } else {
            CommandResult("Alarm screen khol diya hai, time set karke confirm kar dijiye.", true)
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
                "Kitne samay ka timer lagaun? Aise boliye: 5 minute ka timer lagao.",
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
            return CommandResult("Timer ka samay samajh nahi aaya.", true)
        }
        val label = when {
            totalSeconds % 3600 == 0 -> "${totalSeconds / 3600} ghante"
            totalSeconds % 60 == 0 -> "${totalSeconds / 60} minute"
            else -> "$totalSeconds second"
        }
        val successReply = "$label ka timer laga diya hai."
        return if (fromBackgroundService) {
            when (PhoneActions.setTimerFromBackground(context, totalSeconds)) {
                LaunchOutcome.LAUNCHED -> CommandResult(successReply, true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult("Timer nahi lag paya.", true)
            }
        } else if (PhoneActions.setTimer(context, totalSeconds)) {
            CommandResult(successReply, true)
        } else {
            CommandResult("Timer nahi lag paya.", true)
        }
    }

    /** Volume badhao / kam / full / mute — ya sirf poochha ho to haal batao. */
    private fun volumeCommand(text: String): CommandResult {
        val done: Boolean
        val actionText: String
        when {
            text.contains("full") || text.contains("max") -> {
                done = PhoneActions.maximizeMusicVolume(context)
                actionText = "Volume full kar diya hai."
            }
            text.contains("mute") || text.contains("band") || text.contains("off") -> {
                done = PhoneActions.muteMusicVolume(context)
                actionText = "Volume band (mute) kar diya hai."
            }
            text.contains("badha") || text.contains("tez") ||
                text.contains("zyada") || text.contains("increase") -> {
                done = PhoneActions.raiseMusicVolume(context)
                actionText = "Volume badha diya hai."
            }
            text.contains("kam") || text.contains("ghata") ||
                text.contains("dheere") || text.contains("decrease") -> {
                done = PhoneActions.lowerMusicVolume(context)
                actionText = "Volume kam kar diya hai."
            }
            else -> {
                // Sirf "volume" / "awaaz kitni hai" — haal bata do.
                val percent = PhoneActions.musicVolumePercent(context)
                return if (percent >= 0) {
                    CommandResult("Volume abhi $percent% hai.", true)
                } else {
                    CommandResult("Volume ki jaankari nahi mil payi.", true)
                }
            }
        }
        if (!done) {
            return CommandResult("Volume control nahi ho paya.", true)
        }
        val percent = PhoneActions.musicVolumePercent(context)
        return if (percent >= 0) {
            CommandResult("$actionText Ab volume $percent% hai.", true)
        } else {
            CommandResult(actionText, true)
        }
    }

    private fun mediaResult(ok: Boolean, successReply: String): CommandResult =
        if (ok) {
            CommandResult(successReply, true)
        } else {
            CommandResult("Media control nahi ho paya.", true)
        }

    /** Ek URL browser me kholo — background ho to overlay bridge se. */
    private fun openWebUrl(url: String, successReply: String): CommandResult {
        return if (fromBackgroundService) {
            when (PhoneActions.openUrlFromBackground(context, url)) {
                LaunchOutcome.LAUNCHED -> CommandResult(successReply, true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult("Browser nahi khul paya.", true)
            }
        } else if (PhoneActions.openUrl(context, url)) {
            CommandResult(successReply, true)
        } else {
            CommandResult("Browser nahi khul paya.", true)
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
                    CommandResult("${target.displayName} khol diya hai.", true)
                LaunchOutcome.PERMISSION_NEEDED ->
                    CommandResult(OVERLAY_PERMISSION_REPLY, true)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult(
                        "${target.displayName} is phone me nahi mila ya khul nahi paya.",
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
            CommandResult("${target.displayName} khol diya hai.", true)
        } else {
            CommandResult("${target.displayName} is phone me nahi mila ya khul nahi paya.", true)
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
        "camera" to AppTarget("Camera", TargetKind.CAMERA),
        "chrome" to AppTarget("Chrome", TargetKind.PACKAGE, "com.android.chrome"),
        "settings" to AppTarget("Settings", TargetKind.SETTINGS),
        "setting" to AppTarget("Settings", TargetKind.SETTINGS)
    )

    private companion object {
        /** Spoken when a background launch needs the overlay permission. */
        const val OVERLAY_PERMISSION_REPLY =
            "Iske liye 'Display over other apps' permission chahiye — " +
                "app kholkar neeche wala button dabaiye."

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
            "চালাও", "থামাও", "বন্ধ", "পরের", "মিউট", "আওয়াজ"
        )

        /** v1.5: app ke naam se pehle/peeche aane wale bandhe shabd. */
        val APP_NAME_STOP_TOKENS = setOf("karo", "kar", "do", "app", "ko", "yeh", "ye")
    }
}

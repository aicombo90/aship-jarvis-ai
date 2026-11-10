package com.aship.jarvis.commands

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.ai.ScreenEyes
import com.aship.jarvis.ai.ScreenSafety
import com.aship.jarvis.ai.ScreenshotStore
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
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
        // v50: akele "লাইট" / "light" / "লাইট জ্বালাও"
        // = torch ON — user ka pakka hukm: ek shabd
        // bolte hi light jale, poora vakya nahi
        // chahiye। Ye sikhaye hue bandhan
        // (TaughtCommands) ka judwaa bandobast hai —
        // wahan se mita bhi diya jaye to ye buniyaadi
        // roop har raaste (handle) par bana rehta hai।
        lightOnlyCommand(text)?.let { return it }
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
        // v51 (item 7): Hindi garble wala aakhiri raasta —
        // Devanagari sune hue shabd aksar swar-chinh /
        // sanyuktakshar ke halke badlav ke saath aate
        // hain ("टार्च", "सकरोल", "जलावो"), jinhe na
        // normalize ki bandhi suchi pakadti hai na fuzzy
        // sudhaar (woh Latin-mukhi hai)। Shabd-dar-shabd
        // skeleton-milan se pakke roop banao aur dispatch
        // dobara aazmao। Jhoothi pehchan ki rok wahi
        // hai: sirf jaane-pehchane pakke shabd badalte
        // hain, aur natija dispatch ke pakke niyamon se
        // hi guzarta hai।
        val repaired = canonicaliseBySkeleton(rawText)
        if (repaired != null) {
            val third = dispatch(stripFillers(normalize(repaired)))
            if (third.handled) return third
        }
        return first
    }

    /**
     * v51 (item 7): Devanagari-mukhi skeleton — suna
     * text aur pakke shabd dono par ek hi bandha roop
     * lagta hai, taaki likhavat ke halke farq milan na
     * todein: chandrabindu -> anusvara, nukta hatao,
     * halant (sanyuktakshar) ghatao, bade/chhote swar-
     * chinh ek karo (ी->ि, ू->ु, ै->े, ौ->ो, ॉ->ो)।
     * Buniyaad me v50 wala [scrollSkeleton] hai
     * (ZWNJ/ZWJ mukt + Bangla swar-jod)।
     */
    fun hindiSkeleton(input: String): String {
        var t = scrollSkeleton(input)
        t = t.replace('\u0901', '\u0902') // chandrabindu -> anusvara
        t = t.replace("\u093C", "") // nukta
        t = t.replace("\u094D", "") // halant / virama
        t = t.replace('\u0940', '\u093F') // ी -> ि
        t = t.replace('\u0942', '\u0941') // ू -> ु
        t = t.replace('\u0948', '\u0947') // ै -> े
        t = t.replace('\u094C', '\u094B') // ौ -> ो
        t = t.replace('\u0949', '\u094B') // ॉ -> ो
        // Swatantra swar akshar bhi ek karo (shabd ke
        // beech/aakhir me aane wale roop — "मोबाईल" ka
        // ई chinh nahi, akshar hai)।
        t = t.replace('\u0908', '\u0907') // ई -> इ
        t = t.replace('\u090A', '\u0909') // ऊ -> उ
        t = t.replace('\u0910', '\u090F') // ऐ -> ए
        t = t.replace('\u0914', '\u0913') // औ -> ओ
        return t
    }

    /** Skeleton bandhan ki jodi-suchi se bana shabd-naksha (skeleton -> pakka roop)। */
    private val skeletonCanon: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for ((surface, canonical) in SKELETON_CANON_PAIRS) {
            map[hindiSkeleton(surface)] = canonical
        }
        map
    }

    /**
     * v51 (item 7): suna hua vakya shabd-dar-shabd
     * skeleton-milan se sudhaaro — jo shabd pakke
     * shabd ke skeleton se mile, use pakke roop se
     * badal do; baaki shabd jaise hain waise rahen।
     * Kuch badle hi na to null (bulane wala aam
     * raasta hi rakhega)।
     */
    fun canonicaliseBySkeleton(rawText: String): String? {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return null
        var changed = false
        val out = raw.split(Regex("""\s+""")).map { token ->
            val clean = token.trim(',', '.', '।', '!', '?', '"', '\'')
            if (clean.length < 2) {
                token
            } else {
                val canonical = skeletonCanon[hindiSkeleton(clean)]
                if (canonical != null && canonical != clean) {
                    changed = true
                    canonical
                } else {
                    token
                }
            }
        }
        return if (changed) out.joinToString(" ") else null
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

    /**
     * v50: baat AKELE light wala hukm hai kya —
     * poori baat light-shabd + bandhe kriya-shabdon
     * (জ্বালাও/অন/করো...) tak hi simit ho, OFF wala
     * shabd bilkul na ho (OFF ka pakka raasta
     * [deterministicOffCommand] pehle chalta hai,
     * aur yahan bhi wahi jeetega)। Ho to torch ON
     * ka natija; warna null — aam baat ("ঘরে লাইট
     * আছে") kabhi isme nahi phansti, kyunki usme
     * bandhe shabdon ke bahar ke shabd hote hain।
     */
    private fun lightOnlyCommand(text: String): CommandResult? {
        if (text.isEmpty() || hasOffWord(text)) return null
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 4) return null
        if (tokens.none { it in LIGHT_TOKENS }) return null
        if (!tokens.all { it in LIGHT_ALLOWED_TOKENS }) return null
        val ok = PhoneActions.setTorch(context, true)
        return if (ok) {
            CommandResult("टॉर्च जला दिया।", true)
        } else {
            CommandResult("इस फ़ोन पर टॉर्च कंट्रोल नहीं हो पाया।", true)
        }
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
            // v36: OFF hamesha jeetega — spasht "band" (uske
            // saare roop) text me kahin bhi ho to band hi
            // hoga, "chalu/jalao" jaise ON wale shabd garble
            // me saath ghus aaye hon tab bhi. Bina disha wala
            // hukm pehle ki tarah ON ki taraf hi jata hai
            // (is branch ka purana vyavhaar).
            val turnOff = hasOffWord(text)
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
        // jawab milega).
        val dataMention = text.contains("mobile data") || hasWord(text, "data")
        if (dataMention) {
            val turnOffData = hasWord(text, "off") || text.contains("band") ||
                text.contains("bondho")
            // v18: "on" ke aam galat-sune roop bhi on hi maano
            // (Vosk chhota "অন" aksar "আন"/"অণ"/"an" sunta hai) —
            // ye vistaar sirf is data branch ke andar hai,
            // global normalize par koi farq nahi.
            val turnOnData = hasWord(text, "on") || hasWord(text, "onn") ||
                hasWord(text, "an") || hasWord(text, "ऑन") ||
                hasWord(text, "অন") || hasWord(text, "আন") ||
                hasWord(text, "অণ") || text.contains("chalu") ||
                text.contains("chaalu") || text.contains("start")
            if (turnOffData || turnOnData) {
                return mobileDataCommand(turnOn = turnOnData && !turnOffData)
            }
            // v18: disha wala shabd mila hi nahi (asli zindagi me
            // Vosk "মোবাইল ডাটা অন করো" ko "মোবাইল ডাটা করো" sun
            // leta hai — chhota "on" gayab), par karne ka iraada
            // saaf hai, to toggle: on hai to off, off hai to on.
            val dataActionIntent = hasWord(text, "karo") ||
                hasWord(text, "करो") || hasWord(text, "করো") ||
                hasWord(text, "করুন") || hasWord(text, "do") ||
                text.contains("toggle") ||
                // v19: encoding-surakshit stem — "কর" (ক+র) har
                // roop me milta hai (করো/করুন/কর — ো jis roop
                // me bhi aaye, stem waisa ka waisa rehta hai),
                // isliye hasWord kabhi fail ho tab bhi iraada
                // pakda jayega. Devanagari "कर" bhi aise hi.
                text.contains("কর") || text.contains("कर")
            if (dataActionIntent) {
                return mobileDataToggleCommand()
            }
            // v19: aakhri jaal — disha bhi nahi mili, iraade
            // wala shabd bhi nahi (asli garble "মোবাইল ডাটা
            // অর": "অর" na disha hai na iraada), par command
            // session ke andar ek chhota (5 shabd tak) vakya
            // jisme mobile data ka zikr hai, woh asal zindagi
            // me data command hi hota hai — toggle kar do.
            // Lambe vakya is jaal me nahi aate; woh pehle
            // jaise hi bina chhue aage badhte hain.
            val dataTokenCount = text.split(Regex("""\s+""")).count { it.isNotEmpty() }
            if (dataTokenCount <= 5) {
                return mobileDataToggleCommand()
            }
        }

        // --- v59: naam bolkar call / message -------------------------------
        // Fixed app list se PEHLE — "messages"/"phone" app ke
        // keyword hain, isliye call/message wala hukm yahin
        // pakda jata hai, warna Messages/Phone app khul jata।
        ContactCallCommands.tryHandle(context, text, fromBackgroundService)
            ?.let { return it }

        // --- v59: voice reminders ------------------------------------------
        ReminderCommands.tryHandle(context, text)?.let { return it }

        // --- v59: phone lock -------------------------------------------------
        lockCommand(text)?.let { return it }

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
        // v59: Bangla me bhi — "এখন কটা বাজে" / "এখন সময় কত"।
        // "সময়" normalize hokar "samay" banta hai, isliye woh
        // purani shart me hi aata hai; "কটা বাজে" ke liye nayi
        // shart chahiye (usme "baje hain" nahi hota)।
        if (text.contains("time") || text.contains("samay") ||
            text.contains("baje hain") || text.contains("baj rahe") ||
            isBanglaTimeRequest(text)
        ) {
            if (isBanglaText(text)) {
                return CommandResult("এখন ${banglaTimePhrase()}।", true)
            }
            val time = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            return CommandResult("अभी समय है $time।", true)
        }

        // --- Date -----------------------------------------------------------
        // v59: Bangla me bhi — "আজ কত তারিখ" / "আজ কী বার"।
        // "বার" POORA token hona chahiye — "আবার" ke andar
        // chhupa "বার" tarikh nahi hai।
        if (text.contains("date") || text.contains("tarikh") || text.contains("taarikh") ||
            hasBanglaWeekdayWord(text)
        ) {
            if (isBanglaText(text)) {
                return CommandResult(banglaDateReply(text), true)
            }
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
        // v19: Bangla recognizer "করো" ka ো (U+09CB) aksar uske
        // bikhre roop ে+া (U+09C7+U+09BE) me bhejta hai, jabki
        // is file ke saare Bangla shabd ek-codepoint roop me
        // likhe hain — exact milaane (hasWord / table replace)
        // chup-chaap fail ho jata tha ("মোবাইল ডাটা করো" sun
        // kar bhi data command nahi chalta tha). Sabse pehle
        // bikhre roopon ko ek roop me lao: ে+া -> ো, ে+ৗ -> ৌ.
        t = t.replace("\u09C7\u09BE", "\u09CB")
            .replace("\u09C7\u09D7", "\u09CC")
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
            // v36: OFF ke aur sune/garble hue roop — Vosk Hindi
            // Bangla "বন্ধ" ko aksar बन्द/बंध sunta hai, aur
            // Bangla recognizer বন্দ/বাঁধ bhi de sakta hai.
            // Ye na hon to "টর্চ বন্ধ করো" ka "band" banta hi
            // nahi tha aur hukm ON me badal jata tha.
            "बन्द" to "band", "बंध" to "band",
            "বন্দ" to "band", "বাঁধ" to "band",
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
            JarvisAccessibilityService.DataResult.TAPPED_UNKNOWN ->
                CommandResult(DATA_VISION_UNKNOWN_REPLY, true)
            JarvisAccessibilityService.DataResult.NO_GEMINI_KEY ->
                CommandResult(DATA_NO_KEY_REPLY, true)
            JarvisAccessibilityService.DataResult.TILE_NOT_FOUND,
            JarvisAccessibilityService.DataResult.PANEL_NOT_OPENED,
            null -> CommandResult(DATA_NOT_FOUND_REPLY, true)
        }
    }

    /**
     * v18: bina disha ka mobile-data toggle ("মোবাইল ডাটা করো") —
     * Accessibility service tile dabakar haalat ulta karti hai
     * aur dabane ke baad ki haalat batati hai, Maya wahi bolti
     * hai. Service band ho to koshish hi nahi hoti. Sync jawab
     * ke liye yahan bhi wahi bandha hua latch wala intezaar hai
     * jo [mobileDataCommand] me hai (khoj ~2.5 s + dabane ke
     * baad ki padhai — kul seema ke andar).
     */
    private fun mobileDataToggleCommand(): CommandResult {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return CommandResult(DATA_NEEDS_ACCESSIBILITY_REPLY, true)
        }
        val latch = CountDownLatch(1)
        var outcome: JarvisAccessibilityService.DataOutcome? = null
        try {
            JarvisAccessibilityService.flipMobileData(context) { result ->
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
            JarvisAccessibilityService.DataOutcome.TURNED_ON -> CommandResult(
                "मोबाइल डेटा ऑन कर दिया।",
                true
            )
            JarvisAccessibilityService.DataOutcome.TURNED_OFF -> CommandResult(
                "मोबाइल डेटा बंद कर दिया।",
                true
            )
            JarvisAccessibilityService.DataOutcome.TOGGLED_UNKNOWN ->
                CommandResult(DATA_TOGGLED_REPLY, true)
            JarvisAccessibilityService.DataOutcome.NO_GEMINI_KEY ->
                CommandResult(DATA_NO_KEY_REPLY, true)
            JarvisAccessibilityService.DataOutcome.SERVICE_OFF ->
                CommandResult(DATA_NEEDS_ACCESSIBILITY_REPLY, true)
            JarvisAccessibilityService.DataOutcome.TILE_NOT_FOUND,
            JarvisAccessibilityService.DataOutcome.PANEL_NOT_OPENED,
            null -> CommandResult(DATA_NOT_FOUND_REPLY, true)
        }
    }

    // ------------------------------------------------------------------
    // v59: phone lock + Bangla samay/tarikh
    // ------------------------------------------------------------------

    /**
     * "ফোন লক করো" / "phone lock karo" — Accessibility
     * service ka GLOBAL_ACTION_LOCK_SCREEN। Service band
     * ho to imaandaar manaahi, koshish hi nahi।
     *
     * "clock" ke andar chhupa "lock" pakda na jaye isliye
     * lock shabd POORE token ki tarah milna chahiye; aur
     * "unlock"/"আনলক" ho to ye raasta bilkul nahi (unlock
     * awaaz se hota hi nahi)।
     */
    private fun lockCommand(text: String): CommandResult? {
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.isNotEmpty() }
        val hasLockWord = tokens.any {
            it == "lock" || it == "লক" || it == "लॉक"
        }
        if (!hasLockWord) return null
        if (tokens.any { it == "unlock" || it == "আনলক" || it == "अनलॉक" }) {
            return null
        }
        val hasVerb = tokens.any { it in LOCK_VERBS } ||
            tokens.any { it.contains("কর") || it.contains("कर") } ||
            tokens.any { it.contains("ফোন") || it == "phone" }
        if (!hasVerb && tokens.size > 2) return null
        val lang = PlayIntent.requestLanguage(text)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "লক করতে পারলাম না — Accessibility চালু নেই। " +
                            "অ্যাপ খুলে Accessibility অন করো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "लॉक नहीं कर पाई — Accessibility ऑन नहीं है। " +
                            "ऐप खोलकर Accessibility ऑन कर लो।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Couldn't lock — Accessibility is off. " +
                            "Turn it on from the app first."
                },
                true
            )
        }
        val ok = try {
            JarvisAccessibilityService.lockScreen(context)
        } catch (t: Throwable) {
            false
        }
        return if (ok) {
            CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI -> "ফোন লক করে দিলাম।"
                    PlayIntent.SpokenLang.HINDI -> "फ़ोन लॉक कर दिया।"
                    PlayIntent.SpokenLang.ENGLISH -> "Phone locked."
                },
                true
            )
        } else {
            CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "লক করতে পারলাম না — একবার নিজে লক করে দেখো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "लॉक नहीं हो पाया — एक बार खुद लॉक करके देखो।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Couldn't lock the phone — please lock it manually once."
                },
                true
            )
        }
    }

    /** Bangla akshar hai kya (bhasha pehchanne ke liye)। */
    private fun isBanglaText(t: String): Boolean =
        t.any { it in '\u0980'..'\u09FF' }

    /** "এখন কটা বাজে" jaise Bangla samay-sawal। */
    private fun isBanglaTimeRequest(text: String): Boolean {
        if (!isBanglaText(text)) return false
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
        return text.contains("baje") ||
            tokens.any { it == "কটা" || it == "কয়টা" }
    }

    /** "আজ কী বার" — "বার" poora token hona chahiye ("আবার" nahi)। */
    private fun hasBanglaWeekdayWord(text: String): Boolean {
        if (!isBanglaText(text)) return false
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
        return tokens.any { it == "বার" }
    }

    /** "০"-"৯" wale ank — Bangla jawab ke liye। */
    private fun toBanglaDigits(s: String): String =
        s.map { ch ->
            when (ch) {
                '0' -> '০'; '1' -> '১'; '2' -> '২'; '3' -> '৩'; '4' -> '৪'
                '5' -> '৫'; '6' -> '৬'; '7' -> '৭'; '8' -> '৮'; '9' -> '৯'
                else -> ch
            }
        }.joinToString("")

    /** "রাত ১০টা ২১ মিনিট বাজে" — din ke hisse ke saath। */
    private fun banglaTimePhrase(): String {
        val cal = Calendar.getInstance()
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val h12 = when (val h = h24 % 12) { 0 -> 12; else -> h }
        val part = when (h24) {
            in 0..3 -> "রাত"
            in 4..11 -> "সকাল"
            in 12..15 -> "দুপুর"
            in 16..18 -> "বিকেল"
            else -> "রাত"
        }
        val h = toBanglaDigits(h12.toString())
        return if (minute == 0) {
            "$part ${h}টা বাজে"
        } else {
            "$part ${h}টা ${toBanglaDigits(minute.toString())} মিনিট বাজে"
        }
    }

    /**
     * "আজ শনিবার, ৭ নভেম্বর ২০২৬" — sirf "কী বার" poochha
     * ho to "আজ শনিবার" hi।
     */
    private fun banglaDateReply(text: String): String {
        val cal = Calendar.getInstance()
        val weekday = BANGLA_WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        if (hasBanglaWeekdayWord(text) && !text.contains("tarikh")) {
            return "আজ $weekday।"
        }
        val day = toBanglaDigits(cal.get(Calendar.DAY_OF_MONTH).toString())
        val month = BANGLA_MONTHS[cal.get(Calendar.MONTH)]
        val year = toBanglaDigits(cal.get(Calendar.YEAR).toString())
        return "আজ $weekday, $day $month $year।"
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

    /**
     * v36: OFF disha ka pakka sanket hai kya. "band" wala
     * milaap jaan-boojh kar contains hai — normalize ke
     * baad OFF ke saare script roop (বন্ধ/বন্দ/বাঁধ/बंद/
     * बन्द/बंध) "band" bankar aate hain aur Latin garble
     * "bandh" bhi usi me aa jata hai; "bondho" Bangla
     * Latin wala aam roop hai. "off" ke liye poora shabd
     * chahiye, taaki "offline" jaise shabdon ke andar
     * chhupa "off" galti se disha na ban jaye.
     */
    private fun hasOffWord(text: String): Boolean =
        text.contains("band") || text.contains("bondho") ||
            hasWord(text, "off")

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
     * v36: spasht OFF wala device hukm — seedha yahin se,
     * pakke offline raaste se chalao aur sachha natija do.
     * Background (orb) wale raaste par Vosk ka garble text
     * agent ke paas jata tha aur wahan "বন্ধ করো" bhi kabhi
     * kabhi "chalu" bankar laut aata tha; spasht OFF kabhi
     * ON nahi banna chahiye, isliye wake service agent se
     * PEHLE is jaanch ko bulati hai. Torch aur mobile data
     * — dono wahi disha-niyam jo [dispatch] leta hai (OFF
     * mile to OFF hi). Aisa hukm na ho to null — bulane
     * wala apna aam raasta aaram se aage badhaye.
     */
    fun deterministicOffCommand(rawText: String): CommandResult? {
        val text = stripFillers(normalize(rawText.lowercase(Locale.ROOT).trim()))
        if (text.isEmpty() || !hasOffWord(text)) return null
        // v50: "লাইট" bhi torch ka hi naam hai user ki
        // boli me — "লাইট বন্ধ করো" bhi pakka OFF hai,
        // bilkul "টর্চ বন্ধ" ki tarah।
        if (text.contains("torch") || text.contains("flashlight") ||
            text.contains("flash light") || text.contains("লাইট") ||
            text.contains("लाइट") || hasWord(text, "light")
        ) {
            val ok = PhoneActions.setTorch(context, false)
            return if (ok) {
                CommandResult("टॉर्च बंद कर दिया।", true)
            } else {
                CommandResult("इस फ़ोन पर टॉर्च कंट्रोल नहीं हो पाया।", true)
            }
        }
        val dataMention = text.contains("mobile data") || hasWord(text, "data")
        if (dataMention) {
            return mobileDataCommand(turnOn = false)
        }
        return null
    }

    /**
     * v38: pakka scroll hukm — "স্ক্রল ডাউন করো" / "নিচে
     * নামাও" / "স্ক্রল আপ" / "ऊपर करो" / "scroll down"
     * jaise seedhe hukm agent ke paas kabhi nahi jayenge:
     * [deterministicOffCommand] ki tarah pakke offline
     * raaste se Accessibility service screen ke beech me
     * ~60% unchai ka swipe karti hai. Pehchan KACHHE
     * (raw) text par hai — teeno scripton ke pakke sanket
     * companion wali suchiyon me hain. Disha ke sanket
     * dono taraf ke mil jayein to uljha hua hukm hai —
     * null, aam raasta faisla karega. Scroll ka koi
     * sanket hi na ho to bhi null. Service band ho to
     * imaandaar jawab user ki bhasha me — jhootha "ho
     * gaya" kabhi nahi. Natija sync chahiye, isliye
     * mobile data wala hi bandha latch intezaar.
     *
     * v42: pehchan aur mashinari dono mazboot. Bangla
     * recognizer "স্ক্রল" ko aksar স্কুল / স্ক্রোল /
     * ইস্ক্রল / স্‌ক্রল sunta hai — woh saare garble roop
     * ab sanketon me hain, milaana KACHHE text ke saath
     * [normalize] hue roop par bhi hota hai (bikhre
     * swar-chinh ek roop me aate hain), aur Latin "school"
     * shabd jaan-boojh kar scroll NAHI hai (guard suchi)।
     * Asal scroll ab service ka do-mashinari wala
     * [JarvisAccessibilityService.scrollCommandEx] hai —
     * node wala ACTION_SCROLL pehle, swipe baad me — aur
     * report me suna hua hukm + disha + mashinari + natija
     * chaaron bandhte hain.
     */
    /**
     * v50: scroll ki DISHA ki bandhi pehchan — sirf
     * pehchanna, chalana nahi (true = neeche, false =
     * upar, null = scroll hukm nahi / dono disha
     * ghusi hui)।
     *
     * [deterministicScrollCommand] aur sikhaye hue
     * kaamon ki pehchan ([com.aship.jarvis.ai.TaughtCommands])
     * dono ke liye yahi ek pehchan hai — sikhate
     * samay jo scroll mana gaya, chalte samay bhi
     * wahi manega।
     *
     * v50 ki jad-ilaaj (live shikayat: "স্ক্রল ডাউন"
     * bolne par bhi scroll nahi hota tha):
     *  - Milaane ki satahon me ab [scrollSkeleton]
     *    bhi hai: ZWNJ/ZWJ hatakar milaana। Bangla
     *    recognizer "স্ক্রল" ko aksar স্‌ক্রল
     *    (beech me ZWNJ) likhta hai — v42 ke exact
     *    contains wahan chup-chaap haar jate the।
     *  - Sanket-suchiyan bahut badha di gayin
     *    (naye garble roop, Devanagari "स्कूल
     *    डाउन", Roman "scroll daun", "পেজ ডাউন")।
     *  - Scroll shabd mile par bandha wakya na
     *    mile to disha wale alag shabd (নিচে/উপরে)
     *    bhi disha bata sakte hain — "নিচে স্ক্রল
     *    করো" jaisa wakya ab fisalta nahi।
     */
    fun classifyScrollDirection(rawText: String): Boolean? {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return null
        // Milaane ki satahein — kachha text, normalize
        // hua roop, aur dono ke skeleton (ZWNJ/ZWJ-
        // mukt) roop। Sanket bhi skeleton bankar
        // milte hain, isliye likhavat ka halka farq
        // (স্‌ক্রল / স্ক্রল) milan nahi todta।
        // v51 (item 7): Devanagari skeleton wali
        // satahein bhi — "सकरोल" jaise sanyuktakshar-
        // tode roop ab marker "स्क्रॉल" ke skeleton
        // se mil jate hain।
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        fun matched(markers: List<String>): Boolean =
            markers.any { marker ->
                // Sanket ki sui bhi dono skeleton roopon
                // me — Devanagari sanket (halant wala)
                // halant-mukt satah se tabhi milega jab
                // sui bhi waisi hi ghatai hui ho (v51
                // marammat: pehle sui sirf scrollSkeleton
                // ki banti thi, isliye "सकरोल ऊपर" jaise
                // roop disha-sanket se fisal jate the)।
                val needles = listOf(
                    scrollSkeleton(marker), hindiSkeleton(marker)
                ).distinct()
                surfaces.any { surface ->
                    needles.any { surface.contains(it) }
                }
            }
        // v42: "school" wala vakya scroll NAHI hai — Latin
        // me school aaye aur scroll ka koi aur pakka sanket
        // na ho to pakka hukm bilkul nahi banta.
        if (SCROLL_SCHOOL_GUARD_MARKERS.any { raw.contains(it) } &&
            !matched(SCROLL_WORD_MARKERS)
        ) {
            return null
        }
        val up = matched(SCROLL_UP_MARKERS)
        val down = matched(SCROLL_DOWN_MARKERS)
        return when {
            up && down -> null
            up -> false
            down -> true
            else -> {
                if (!matched(SCROLL_WORD_MARKERS)) return null
                // Scroll shabd hai, bandha disha-wakya
                // nahi — alag disha-shabd se disha bano
                // ("নিচে স্ক্রল করো" / "scroll upar")।
                // Dono taraf ke shabd hon to uljha hua
                // hukm hai — aam raasta faisla karega।
                val downToken = matched(SCROLL_DOWN_TOKENS)
                val upToken = matched(SCROLL_UP_TOKENS)
                when {
                    downToken && upToken -> null
                    downToken -> true
                    upToken -> false
                    else -> {
                        // Nanga scroll shabd ("স্ক্রল করো" /
                        // "scroll karo") — chhota hukm-jaisa vakya
                        // ho ya "karo/করো" wali kriya saath ho to
                        // disha NEECHE (aam iraada wahi hota hai).
                        // Lamba vakya bina kriya ke scroll par
                        // sawaal/baatchit ho sakta hai — aam
                        // raaste par chhod do.
                        // v50: nanga shabd POORA token hona
                        // chahiye — "স্কুলে যাব" (school jaana)
                        // ke "স্কুলে" me ghusa "স্কুল" scroll
                        // NAHI hai; disha wale raaste par ye
                        // sakhti nahi hai (wahan disha-shabd
                        // iraada pakka karta hai)।
                        if (!hasBareScrollToken(raw)) return null
                        val tokenCount = raw.split(Regex("""\s+""")).size
                        val hasDoVerb = matched(SCROLL_DO_VERBS)
                        if (tokenCount > 4 && !hasDoVerb) null else true
                    }
                }
            }
        }
    }

    /**
     * v50: text me scroll shabd POORE token ki
     * tarah hai kya (aakhir me টা/টি jud sakta
     * hai) — nange-scroll wale raaste ki sakhti।
     * "স্কুলে" jaisa ghusa hua roop yahan fail
     * hota hai, "স্ক্রল" / "স্ক্রলটা" pass।
     */
    private fun hasBareScrollToken(rawText: String): Boolean {
        // v51 (item 7): Devanagari skeleton wale token
        // bhi gino — "सकरोल" nanga shabd bhi scroll hai।
        val tokens = listOf(scrollSkeleton(rawText), hindiSkeleton(rawText))
            .flatMap { it.split(Regex("""\s+""")) }
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val needles = SCROLL_WORD_MARKERS.flatMap {
            listOf(scrollSkeleton(it), hindiSkeleton(it))
        }
        return tokens.any { token ->
            needles.any { needle ->
                token == needle ||
                    token == needle + "টা" ||
                    token == needle + "টি"
            }
        }
    }

    /**
     * v50: scroll milaane ke liye text ka bandha
     * skeleton roop — chhote akshar, bikhre swar
     * ek-codepoint me, aur ZWNJ (U+200C) / ZWJ
     * (U+200D) poori tarah hataye hue। Dono taraf
     * (suna text aur sanket) yahi lagta hai।
     */
    private fun scrollSkeleton(input: String): String {
        var t = input.lowercase(Locale.ROOT)
        t = t.replace("\u09C7\u09BE", "\u09CB")
            .replace("\u09C7\u09D7", "\u09CC")
        t = t.replace("\u200C", "").replace("\u200D", "")
        return t
    }

    fun deterministicScrollCommand(rawText: String): CommandResult? {
        val scrollDown: Boolean = classifyScrollDirection(rawText) ?: run {
            // v50: baat scroll-jaisi thi par pehchan
            // bani hi nahi to report me NOMATCH bandho
            // — agli nakami par andaza nahi lagana
            // padega: "last_scroll_command" khud
            // batayega ki suna kya tha aur pehchan
            // kyun nahi mili।
            if (looksLikeScrollAttempt(rawText)) {
                saveScrollNoMatchDiag(rawText)
            }
            return null
        }
        val lang = PlayIntent.requestLanguage(rawText)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveScrollDiag(
                rawText, scrollDown, false,
                JarvisAccessibilityService.MECHANISM_NONE,
                "accessibility band"
            )
            return CommandResult(scrollNeedsAccessibilityReply(lang), true)
        }
        val latch = CountDownLatch(1)
        var report: JarvisAccessibilityService.ScrollReport? = null
        try {
            JarvisAccessibilityService.scrollCommandEx(context, scrollDown) { r ->
                report = r
                latch.countDown()
            }
        } catch (t: Throwable) {
            saveScrollDiag(
                rawText, scrollDown, false,
                JarvisAccessibilityService.MECHANISM_NONE,
                "bulane me gadbad: " + t.javaClass.simpleName
            )
            return CommandResult(scrollFailedReply(lang), true)
        }
        val completed = try {
            latch.await(SCROLL_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        val result = report
        val done = completed && result != null && result.ok
        saveScrollDiag(
            rawText, scrollDown, done,
            result?.mechanism ?: JarvisAccessibilityService.MECHANISM_NONE,
            result?.detail ?: "intezaar poora, report nahi aayi"
        )
        return if (done) {
            CommandResult(scrollDoneReply(lang), true)
        } else {
            CommandResult(scrollFailedReply(lang), true)
        }
    }

    /** v38: scroll ho jane ki chhoti si pushti — user ki bhasha me. */
    private fun scrollDoneReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI -> "স্ক্রল করে দিয়েছি।"
        PlayIntent.SpokenLang.HINDI -> "स्क्रॉल कर दिया।"
        PlayIntent.SpokenLang.ENGLISH -> "Scrolled."
    }

    /** v38: swipe na ho paye to sachha jawab — user ki bhasha me. */
    private fun scrollFailedReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI -> "স্ক্রল করতে পারলাম না।"
        PlayIntent.SpokenLang.HINDI -> "स्क्रॉल नहीं हो पाया।"
        PlayIntent.SpokenLang.ENGLISH -> "Couldn't scroll."
    }

    /** v38: Accessibility band ho to wahi bolo — user ki bhasha me. */
    private fun scrollNeedsAccessibilityReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রল করতে Accessibility লাগবে — অ্যাপ খুলে চালু করে নাও।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रॉल करने के लिए पहले ऐप खोलकर Accessibility ऑन कर लो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Scrolling needs Accessibility — please open the app and turn it on."
        }

    /**
     * v38+v42: scroll koshish ki report jarvis_diag me —
     * suna hua hukm, disha, kaun si mashinari chali
     * (node/gesture/gesture-retry/none), natija aur wajah।
     * Agli nakaami par andaza nahi lagana padega: report
     * khud batayegi ki pehchan mili thi ya nahi, aur mili
     * thi to scroll kis mashinari se hua ya kyun nahi hua.
     */
    private fun saveScrollDiag(
        rawText: String,
        down: Boolean,
        ok: Boolean,
        mechanism: String,
        detail: String
    ) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_scroll_command",
                    (if (down) "down" else "up") + ": " +
                        rawText.trim().take(40) +
                        " [" + mechanism + "]" +
                        " -> " + (if (ok) "OK" else "FAIL") +
                        " (" + detail.take(60) + ")"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v50: baat scroll-jaisi hai kya — scroll wala
     * koi shabd (uska garble roop bhi) text me ho।
     * Sirf report ke liye: pehchan na bane par baat
     * scroll-jaisi ho to NOMATCH bandhta hai।
     */
    private fun looksLikeScrollAttempt(rawText: String): Boolean {
        val skeleton = scrollSkeleton(rawText.lowercase(Locale.ROOT))
        if (skeleton.isEmpty()) return false
        return SCROLL_WORD_MARKERS.any { marker ->
            skeleton.contains(scrollSkeleton(marker))
        }
    }

    /** v50: scroll pehchan na banne ki report — suna hua hukm jyon ka tyon। */
    private fun saveScrollNoMatchDiag(rawText: String) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_scroll_command",
                    "nomatch: " + rawText.trim().take(40) +
                        " [none] -> NOMATCH"
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    // ------------------------------------------------------------------
    // v92: LAGATAR SCROLL + VOICE TAP + VOICE BACK
    //
    // User (2026-11-10): "স্ক্রল ডাউন করতে বললে স্ক্রল
    // করতেই থাকবে ধীরে ধীরে, আমি যখন বলব স্টপ তখন
    // দাঁড়িয়ে যাবে" / "আমি যে ভিডিওটা ক্লিক করতে
    // বলব সেই ভিডিওটা যেন ক্লিক করতে পারি" / "ব্যাক
    // করতে বললে যেন ব্যাক হতে পারে"।
    //
    // Teenon pakke offline raaste hain (wake service /
    // MainActivity ki deterministic chain se bulaye jate
    // hain — agent ke paas kabhi nahi jate), teeno
    // script (Bangla / Hindi / English) me milte hain।
    //
    // DHYAAN: [deterministicScrollCommand] EK baar wala
    // scroll hi rehta hai — sikhaye hue kaam aur combo
    // use seedha bulate hain aur ek hi scroll + uska
    // pakka jawab chahte hain (ComboRules SCROLL_OK_TEXTS)।
    // Lagatar scroll sirf yahan ka naya
    // [startContinuousScrollCommand] hai, jo voice
    // dispatch se bulaya jata hai।
    // ------------------------------------------------------------------

    /**
     * v92: lagatar dheema scroll shuru — "স্ক্রল ডাউন
     * করো" / "নিচে স্ক্রল করো" / "scroll down" (aur
     * upar wale roop) bolte hi scroll shuru hota hai
     * aur [CONTINUOUS_SCROLL_INTERVAL_MS] par ek chhota
     * scroll (wahi [JarvisAccessibilityService
     * .scrollCommandEx]: node ACTION_SCROLL pehle, swipe
     * baad me) tab tak chalta rehta hai jab tak
     * "থামো" / "stop" na bola jaye. Disha ki pehchan
     * wahi [classifyScrollDirection] hai — naya
     * matching nahi, purani pehchan hi.
     * Scroll na ho to null (aam raasta faisla karega)।
     */
    fun startContinuousScrollCommand(rawText: String): CommandResult? {
        val scrollDown: Boolean = classifyScrollDirection(rawText) ?: return null
        val lang = PlayIntent.requestLanguage(rawText)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveContinuousScrollDiag(rawText, scrollDown, "SERVICE_OFF")
            return CommandResult(scrollNeedsAccessibilityReply(lang), true)
        }
        startContinuousScroll(context, scrollDown)
        saveContinuousScrollDiag(rawText, scrollDown, "STARTED")
        return CommandResult(continuousScrollStartedReply(lang), true)
    }

    /**
     * v92: lagatar scroll roko — "থামো" / "থাম" /
     * "থামাও" / "স্টপ" / "stop" / "রুকো" / "रुको" /
     * "stop scrolling" / "স্ক্রল থামাও"। Scroll chal
     * raha ho to turant rukta hai (agla tick kabhi
     * post nahi hota — [stopContinuousScroll] handler
     * se pending tick hata deta hai; beech me chal raha
     * ek swipe (~350 ms) hi aakhiri kadam hota hai)।
     * Scroll chal hi nahi raha ho to chhota hukm-jaisa
     * vakya (4 shabd tak) bhi handled hai — "কিছু
     * স্ক্রল হচ্ছে না" — taaki wake session chup na
     * rahe. Lamba vakya, ya gaana/timer/alarm/video
     * wala "stop" (jaise "গান থামাও" / "stop the
     * timer"), yahan nahi phansta — woh aam raaste
     * (media pause waghera) ke liye null laut-ta hai।
     */
    fun stopContinuousScrollCommand(rawText: String): CommandResult? {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return null
        // Gaana / timer / alarm / video wala stop kabhi
        // scroll-stop nahi hai — woh aam raaste ka hai।
        if (STOP_GUARD_WORDS.any { raw.contains(it) }) return null
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        fun matched(markers: List<String>): Boolean =
            markers.any { marker ->
                val needles = listOf(
                    scrollSkeleton(marker), hindiSkeleton(marker)
                ).distinct()
                surfaces.any { surface ->
                    needles.any { surface.contains(it) }
                }
            }
        if (!matched(STOP_SCROLL_PHRASES) && !stopTokenPresent(raw)) {
            return null
        }
        val lang = PlayIntent.requestLanguage(rawText)
        if (!continuousScrollActive) {
            val tokens = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.size > 4) return null
            return CommandResult(notScrollingReply(lang), true)
        }
        val wasDown = continuousScrollDown
        stopContinuousScroll()
        saveContinuousScrollDiag(rawText, wasDown, "STOPPED")
        return CommandResult(continuousScrollStoppedReply(lang), true)
    }

    /**
     * v92: "থামো" jaisa roko-shabd POORE token ki
     * tarah hai kya — skeleton satahon par, taaki
     * likhavat ka halka farq milan na tode।
     */
    private fun stopTokenPresent(rawText: String): Boolean {
        val tokens = listOf(scrollSkeleton(rawText), hindiSkeleton(rawText))
            .flatMap { it.split(Regex("""\s+""")) }
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        val needles = STOP_SCROLL_TOKENS.flatMap {
            listOf(scrollSkeleton(it), hindiSkeleton(it))
        }
        return tokens.any { it in needles }
    }

    /** v92: lagatar scroll shuru hua — user ki bhasha me chhoti tasalli। */
    private fun continuousScrollStartedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রল করছি — থামো বললেই থেমে যাব।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रॉल कर रही हूँ — रुकने को कहो तो रुक जाऊँगी।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Scrolling slowly — say stop and I'll stop."
        }

    /** v92: lagatar scroll ruka — user ki bhasha me। */
    private fun continuousScrollStoppedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "থেমে গেছি।"
            PlayIntent.SpokenLang.HINDI -> "रुक गई।"
            PlayIntent.SpokenLang.ENGLISH -> "Stopped scrolling."
        }

    /** v92: roko bola par kuch scroll ho hi nahi raha tha। */
    private fun notScrollingReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "এখন কিছু স্ক্রল হচ্ছে না।"
            PlayIntent.SpokenLang.HINDI -> "अभी कुछ स्क्रॉल नहीं हो रहा है।"
            PlayIntent.SpokenLang.ENGLISH -> "Nothing is scrolling right now."
        }

    /** v92: lagatar scroll ki report jarvis_diag me — disha + haal। */
    private fun saveContinuousScrollDiag(
        rawText: String,
        down: Boolean,
        state: String
    ) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_continuous_scroll",
                    (if (down) "down" else "up") + ": " +
                        rawText.trim().take(40) + " -> " + state
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v92: VOICE TAP / CLICK — "এই ভিডিওটা ক্লিক করো"
     * / "ট্যাপ করো" / "tap this video" / "click [naam]"।
     * Tap/click kriya honi hi chahiye, warna null (aam
     * raasta faisla karega)। Nishana:
     *  - naam bola ho ("[naam] ট্যাপ করো") to wahi naam
     *    screen par dhoondhkar dabao — pehle
     *    [JarvisAccessibilityService.tapScreenText]
     *    (hubahu, warna naam rakhne wala sabse chhota
     *    label; gesture tap, warna ACTION_CLICK);
     *  - woh na mile to screen ke saar se shabd-milan
     *    wala fallback (sabse zyada shabd milne wala
     *    clickable hissa, uske beech par tap);
     *  - nanga / "এই ভিডিওটা" jaisa aam nishana ho to
     *    sabse upar wala bada video-jaisa hissa dabao
     *    (YouTube saamne ho to pehle [tapFirstYouTubeResult]
     *    wala aazmaya hua raasta, warna screen-saar se
     *    sabse bada clickable hissa); "দ্বিতীয় ভিডিও"
     *    / "second video" ho to dusra wala।
     * Kuch na mile to imaandaar "পেলাম না" — kabhi
     * andaze se koi aur cheez nahi dabti। Banking /
     * payment wali screen ([ScreenSafety]) par tap
     * bilkul mana hai — wahan kuch nahi dabta।
     * Tap se pehle lagatar scroll (chal raha ho to)
     * rok diya jata hai, taaki nishana hilta na rahe।
     */
    fun deterministicTapCommand(rawText: String): CommandResult? {
        val target = extractTapTarget(rawText) ?: return null
        // Nishana hilta na rahe: tap se pehle scroll roko।
        stopContinuousScroll()
        val lang = PlayIntent.requestLanguage(rawText)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveTapDiag(rawText, "SERVICE_OFF")
            return CommandResult(tapNeedsAccessibilityReply(lang), true)
        }
        val snapshot = captureScreenSync()
        val pkg = snapshot?.packageName ?: ""
        if (pkg.isNotEmpty() &&
            ScreenSafety.denyReason(context, pkg) != null
        ) {
            saveTapDiag(rawText, "DENIED")
            return CommandResult(tapDeniedReply(lang), true)
        }
        // YouTube saamne ho aur pehla video chahiye ho to
        // aazmaya hua pehle-video wala raasta pehle।
        if (target.generic && target.ordinal == 0 &&
            pkg.contains("youtube", ignoreCase = true)
        ) {
            when (tapFirstYouTubeSync()) {
                JarvisAccessibilityService.TapOutcome.TAPPED,
                JarvisAccessibilityService.TapOutcome.UNVERIFIED -> {
                    saveTapDiag(rawText, "OK (youtube-first)")
                    return CommandResult(tapDoneReply(lang), true)
                }
                else -> {
                    // NOT_FOUND / BUSY / SERVICE_OFF: neeche
                    // wala screen-saar wala raasta aazmao।
                }
            }
        }
        if (!target.generic && target.text != null) {
            if (tapTextSync(target.text)) {
                saveTapDiag(rawText, "OK (tap_text)")
                return CommandResult(tapDoneReply(lang), true)
            }
            val overlap = bestOverlapElement(snapshot, target.text)
            if (overlap != null &&
                tapPointSync(overlap.centerX, overlap.centerY)
            ) {
                saveTapDiag(rawText, "OK (overlap)")
                return CommandResult(tapDoneReply(lang), true)
            }
            // v95: tasveer se dhoondho — naam se na mile to
            // Gemini ko nishan-aankit tasveer dikhakar pata
            // lagao (jaise "লাল বোতামটা" / "the red button")।
            // Main thread par network call nahi — foreground
            // (TAP TO SPEAK / likha) wala raasta main thread
            // par hai, wahan ye kadam chhod do; wake service
            // wala raasta background thread par hai, wahan
            // chalega। Key na ho to bhi ye kadam nahi — pehle
            // wala "পেলাম না" hi milega।
            if (Looper.myLooper() != Looper.getMainLooper() &&
                GeminiBrain.isConfigured(context)
            ) {
                val located = ScreenEyes.locateByVision(
                    context, target.text, snapshot
                )
                val el = located.element
                if (located.kind == ScreenEyes.Kind.OK && el != null &&
                    tapPointSync(el.centerX, el.centerY)
                ) {
                    saveTapDiag(rawText, "OK (vision)")
                    return CommandResult(tapDoneReply(lang), true)
                }
                saveTapDiag(rawText, "VISION_" + located.kind.name)
            }
            saveTapDiag(rawText, "NOT_FOUND")
            return CommandResult(tapNotFoundReply(lang), true)
        }
        val candidates = videoLikeCandidates(snapshot)
        val pick = candidates.getOrNull(target.ordinal)
        if (pick == null) {
            saveTapDiag(rawText, "NOT_FOUND")
            return CommandResult(tapNotFoundReply(lang), true)
        }
        if (tapPointSync(pick.centerX, pick.centerY)) {
            saveTapDiag(rawText, "OK (generic)")
            return CommandResult(tapDoneReply(lang), true)
        }
        saveTapDiag(rawText, "FAIL")
        return CommandResult(tapFailedReply(lang), true)
    }

    /**
     * v92: tap wale hukm se nishana nikalo — tap/click
     * kriya na ho to null। [TapTarget.text] naam wala
     * nishana hai, [TapTarget.generic] "এই ভিডিওটা"
     * jaisa aam nishana hai, [TapTarget.ordinal] kram
     * ("প্রথম" = 0, "দ্বিতীয়" = 1, "তৃতীয়" = 2)।
     */
    private fun extractTapTarget(rawText: String): TapTarget? {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return null
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        fun phraseMatched(markers: List<String>): Boolean =
            markers.any { marker ->
                val needles = listOf(
                    scrollSkeleton(marker), hindiSkeleton(marker)
                ).distinct()
                surfaces.any { surface ->
                    needles.any { surface.contains(it) }
                }
            }
        val tokens = raw.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() }
        val verbTokens = TAP_VERB_TOKENS.flatMap {
            listOf(scrollSkeleton(it), hindiSkeleton(it))
        }
        val hasVerbToken = tokens.any { token ->
            val sk = scrollSkeleton(token)
            val hi = hindiSkeleton(token)
            sk in verbTokens || hi in verbTokens
        }
        if (!hasVerbToken && !phraseMatched(TAP_VERB_PHRASES)) return null
        val kept = tokens.filter { token ->
            val sk = scrollSkeleton(token)
            val hi = hindiSkeleton(token)
            sk !in verbTokens && hi !in verbTokens &&
                token !in TAP_FILLER_TOKENS
        }
        // Kram ("দ্বিতীয় ভিডিও" / "second video" / "2 নম্বর")।
        var ordinal = 0
        val rest = ArrayList<String>()
        var i = 0
        while (i < kept.size) {
            val tok = kept[i]
            val next = kept.getOrNull(i + 1)
            if ((next == "নম্বর" || next == "নাম্বার" || next == "number") &&
                tok in TAP_ORDINAL_NUMBERS
            ) {
                ordinal = TAP_ORDINAL_NUMBERS.getValue(tok)
                i += 2
                continue
            }
            if (tok in TAP_ORDINAL_WORDS) {
                ordinal = TAP_ORDINAL_WORDS.getValue(tok)
                i++
                continue
            }
            rest.add(tok)
            i++
        }
        val nameWords = rest.filter { it !in GENERIC_VIDEO_WORDS }
        return if (nameWords.isEmpty()) {
            TapTarget(text = null, generic = true, ordinal = ordinal)
        } else {
            TapTarget(
                text = nameWords.joinToString(" "),
                generic = false,
                ordinal = ordinal
            )
        }
    }

    /** v92: tap wale nishane ka bandha roop — naam / aam / kram। */
    private data class TapTarget(
        val text: String?,
        val generic: Boolean,
        val ordinal: Int
    )

    /**
     * v92: screen ka saar bandha (sync) lo — capture ka
     * jawab service ke worker thread se aata hai, isliye
     * bulane wala thread main thread bhi ho to latch
     * par intezaar surakshit hai (service ka worker
     * alag thread hai)। Na mile to null।
     */
    private fun captureScreenSync(): JarvisAccessibilityService.ScreenSnapshot? {
        val latch = CountDownLatch(1)
        var snapshot: JarvisAccessibilityService.ScreenSnapshot? = null
        try {
            JarvisAccessibilityService.captureScreen(context) { snap ->
                snapshot = snap
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        try {
            // Capture aam taur par 1 s se kam me aata hai —
            // 3 s kaafi hai (tap ka 5 s wala intezaar alag hai)।
            latch.await(3_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Intezaar toota: jo mila wahi kaafi.
        }
        return snapshot
    }

    /** v92: naam se tap ka bandha intezaar — natija sachha। */
    private fun tapTextSync(text: String): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        try {
            JarvisAccessibilityService.tapScreenText(context, text) { result ->
                ok = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return false
        }
        try {
            latch.await(TAP_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Intezaar toota: tap ka natija nahi mila.
        }
        return ok
    }

    /** v92: (x, y) par tap ka bandha intezaar — natija sachha। */
    private fun tapPointSync(x: Int, y: Int): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        try {
            JarvisAccessibilityService.tapScreenPoint(context, x, y) { result ->
                ok = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return false
        }
        try {
            latch.await(TAP_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Intezaar toota: tap ka natija nahi mila.
        }
        return ok
    }

    /** v92: YouTube pehle-video tap ka bandha intezaar। */
    private fun tapFirstYouTubeSync(): JarvisAccessibilityService.TapOutcome {
        val latch = CountDownLatch(1)
        var outcome = JarvisAccessibilityService.TapOutcome.NOT_FOUND
        try {
            JarvisAccessibilityService.tapFirstYouTubeResult(context) { result ->
                outcome = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return JarvisAccessibilityService.TapOutcome.NOT_FOUND
        }
        try {
            latch.await(TAP_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Intezaar toota: tap ka natija nahi mila.
        }
        return outcome
    }

    /**
     * v92: naam wale nishane ka shabd-milan wala fallback —
     * screen-saar me woh clickable hissa jiske naam me
     * nishane ke sabse zyada shabd hon (kam se kam ek);
     * barabar hon to sabse chhota naam। Ek bhi shabd na
     * mile to null — andaze se kuch nahi dabta।
     */
    private fun bestOverlapElement(
        snapshot: JarvisAccessibilityService.ScreenSnapshot?,
        targetText: String
    ): JarvisAccessibilityService.ScreenElement? {
        if (snapshot == null) return null
        val words = targetText.lowercase(Locale.ROOT)
            .split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.length >= 2 }
        if (words.isEmpty()) return null
        var best: JarvisAccessibilityService.ScreenElement? = null
        var bestOverlap = 0
        var bestLabelLength = Int.MAX_VALUE
        for (el in snapshot.elements) {
            if (!el.clickable) continue
            val label = el.text.lowercase(Locale.ROOT)
            if (label.isEmpty()) continue
            val overlap = words.count { label.contains(it) }
            if (overlap == 0) continue
            val better = overlap > bestOverlap ||
                (overlap == bestOverlap && label.length < bestLabelLength)
            if (better) {
                best = el
                bestOverlap = overlap
                bestLabelLength = label.length
            }
        }
        return best
    }

    /**
     * v92: aam ("এই ভিডিওটা") nishane ke ummeedwar —
     * screen-saar ke bade clickable hisse (screen ke
     * ~4% se bada chaukona, upar/neeche ki patti
     * chhodkar), upar se neeche ke kram me। Video
     * item ka container aam taur par yahi hota hai —
     * chauda, uncha, clickable, naam ho ya na ho।
     */
    private fun videoLikeCandidates(
        snapshot: JarvisAccessibilityService.ScreenSnapshot?
    ): List<JarvisAccessibilityService.ScreenElement> {
        if (snapshot == null) return emptyList()
        val metrics = try {
            context.resources.displayMetrics
        } catch (t: Throwable) {
            return emptyList()
        }
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return emptyList()
        val screenArea = width.toLong() * height
        return snapshot.elements
            .filter { it.clickable }
            .filter {
                val area = (it.right - it.left).toLong() *
                    (it.bottom - it.top)
                area >= screenArea * 4 / 100
            }
            .filter { it.centerY > height * 0.08 && it.centerY < height * 0.92 }
            .sortedWith(
                compareBy(
                    { it.top },
                    { -((it.right - it.left) * (it.bottom - it.top)) }
                )
            )
    }

    /** v92: tap ho gaya — user ki bhasha me। */
    private fun tapDoneReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "ট্যাপ করে দিয়েছি।"
            PlayIntent.SpokenLang.HINDI -> "टैप कर दिया।"
            PlayIntent.SpokenLang.ENGLISH -> "Tapped it."
        }

    /** v92: nishana screen par mila hi nahi — sachha jawab। */
    private fun tapNotFoundReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ওই নামে স্ক্রিনে কিছু পেলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "उस नाम से स्क्रीन पर कुछ नहीं मिला।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't find that on the screen."
        }

    /** v92: tap ki koshish chali par dab nahi paya। */
    private fun tapFailedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "ট্যাপ করতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI -> "टैप नहीं हो पाया।"
            PlayIntent.SpokenLang.ENGLISH -> "Couldn't tap it."
        }

    /** v92: tap ke liye Accessibility pehle chahiye। */
    private fun tapNeedsAccessibilityReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ট্যাপ করতে Accessibility লাগবে — অ্যাপ খুলে চালু করে নাও।"
            PlayIntent.SpokenLang.HINDI ->
                "टैप करने के लिए पहले ऐप खोलकर Accessibility ऑन कर लो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Tapping needs Accessibility — please open the app and turn it on."
        }

    /** v92: banking/payment wali screen par tap mana hai। */
    private fun tapDeniedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "এই স্ক্রিনে ট্যাপ করতে পারব না — এটা ব্যাংক/পেমেন্টের সুরক্ষিত স্ক্রিন।"
            PlayIntent.SpokenLang.HINDI ->
                "इस स्क्रीन पर टैप नहीं कर सकती — यह बैंक/पेमेंट की सुरक्षित स्क्रीन है।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I can't tap on this screen — it's a protected banking/payment screen."
        }

    /** v92: tap koshish ki report jarvis_diag me। */
    private fun saveTapDiag(rawText: String, state: String) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_tap_command",
                    rawText.trim().take(40) + " -> " + state
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v92: VOICE BACK — "ব্যাক করো" / "পেছনে যাও" /
     * "পিছে যাও" / "ফিরে যাও" / "back" / "go back" /
     * "पीछे जाओ"। System back
     * ([AccessibilityService.GLOBAL_ACTION_BACK]) saamne
     * wali screen par chalta hai — koi bhi app ho।
     * "background" jaisa shabd isme nahi phansta
     * (poora token milaya jata hai, substring nahi);
     * "come back tomorrow" jaisa bhavishya wala vakya
     * bhi nahi (guard shabd)। Tap ki tarah, back se
     * pehle lagatar scroll (chal raha ho to) rok diya
     * jata hai।
     */
    fun deterministicBackCommand(rawText: String): CommandResult? {
        if (!isBackCommand(rawText)) return null
        stopContinuousScroll()
        val lang = PlayIntent.requestLanguage(rawText)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveBackDiag(rawText, "SERVICE_OFF")
            return CommandResult(backNeedsAccessibilityReply(lang), true)
        }
        val latch = CountDownLatch(1)
        var ok = false
        try {
            JarvisAccessibilityService.screenGlobalAction(
                context,
                AccessibilityService.GLOBAL_ACTION_BACK
            ) { result ->
                ok = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            saveBackDiag(rawText, "FAIL")
            return CommandResult(backFailedReply(lang), true)
        }
        try {
            latch.await(BACK_COMMAND_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Intezaar toota: back ka natija nahi mila.
        }
        saveBackDiag(rawText, if (ok) "OK" else "FAIL")
        return if (ok) {
            CommandResult(backDoneReply(lang), true)
        } else {
            CommandResult(backFailedReply(lang), true)
        }
    }

    /** v92: baat back wala hukm hai kya — token + bandha wakya, guard ke saath। */
    private fun isBackCommand(rawText: String): Boolean {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        if (BACK_GUARD_WORDS.any { raw.contains(it) }) return false
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        fun phraseMatched(markers: List<String>): Boolean =
            markers.any { marker ->
                val needles = listOf(
                    scrollSkeleton(marker), hindiSkeleton(marker)
                ).distinct()
                surfaces.any { surface ->
                    needles.any { surface.contains(it) }
                }
            }
        if (phraseMatched(BACK_PHRASES)) return true
        val tokens = raw.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.isNotEmpty() }
        val backTokens = BACK_TOKENS.flatMap {
            listOf(scrollSkeleton(it), hindiSkeleton(it))
        }
        val hasBackToken = tokens.any { token ->
            scrollSkeleton(token) in backTokens ||
                hindiSkeleton(token) in backTokens
        }
        if (!hasBackToken) return false
        // Nanga "back" / "ব্যাক" chhote hukm me hi back hai;
        // lamba vakya ho to saath me kriya (করো/যাও/karo/jao)
        // chahiye — "come back" jaisa vakya yahan nahi aata।
        if (tokens.size <= 3) return true
        return BACK_VERB_TOKENS.any { verb ->
            tokens.any { scrollSkeleton(it) == scrollSkeleton(verb) }
        }
    }

    /** v92: back ho gaya — user ki bhasha me। */
    private fun backDoneReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "পেছনে গেলাম।"
            PlayIntent.SpokenLang.HINDI -> "पीछे चली गई।"
            PlayIntent.SpokenLang.ENGLISH -> "Went back."
        }

    /** v92: back dab nahi paya। */
    private fun backFailedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "ব্যাক করতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI -> "पीछे नहीं जा पाई।"
            PlayIntent.SpokenLang.ENGLISH -> "Couldn't go back."
        }

    /** v92: back ke liye Accessibility pehle chahiye। */
    private fun backNeedsAccessibilityReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ব্যাক করতে Accessibility লাগবে — অ্যাপ খুলে চালু করে নাও।"
            PlayIntent.SpokenLang.HINDI ->
                "पीछे जाने के लिए पहले ऐप खोलकर Accessibility ऑन कर लो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Going back needs Accessibility — please open the app and turn it on."
        }

    /** v92: back koshish ki report jarvis_diag me। */
    private fun saveBackDiag(rawText: String, state: String) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_back_command",
                    rawText.trim().take(40) + " -> " + state
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    // ------------------------------------------------------------------
    // v95: MAYA'S EYES — screen dekhna (vision)
    // ------------------------------------------------------------------

    /**
     * v95: teenon aankh-wale hukmon ka sasta pehchan —
     * sirf shabd-milan (koi latch/IO/network nahi), taaki
     * MainActivity main thread par pehle poochh kar
     * background executor par chala sake (runDirectScreenEyes)।
     */
    fun isScreenEyesCommand(rawText: String): Boolean =
        isReadScreenCommand(rawText) ||
            isFindOnScreenCommand(rawText) ||
            isDescribeScreenCommand(rawText)

    /**
     * v95: foreground (MainActivity) wala bandha raasta —
     * background executor se bulana (describe/find/read
     * teeno me capture + Gemini vision hota hai, main
     * thread par nahi chal sakte)। Pehla mila hua hukm
     * hi chalega (kram: read -> find -> describe, taaki
     * "read what's on the screen" jaisa vakya describe
     * me na ghuse)।
     */
    fun runScreenEyesCommand(rawText: String): CommandResult {
        readScreenCommand(rawText)?.let { return it }
        findOnScreenCommand(rawText)?.let { return it }
        describeScreenCommand(rawText)?.let { return it }
        return CommandResult(
            eyesVisionFailedReply(PlayIntent.requestLanguage(rawText)), true
        )
    }

    /** v95: "স্ক্রিনে কী আছে বলো" / "describe the screen" jaisa hukm hai kya। */
    fun isDescribeScreenCommand(rawText: String): Boolean {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        return eyesPhraseMatched(raw, EYES_DESCRIBE_PHRASES)
    }

    /**
     * v95: screen par kya hai, batao — ScreenEyes.describe
     * (tasveer + Gemini vision)। BANDHA call hai: background
     * thread se hi bulana।
     */
    fun describeScreenCommand(rawText: String): CommandResult? {
        if (!isDescribeScreenCommand(rawText)) return null
        val lang = PlayIntent.requestLanguage(rawText)
        val result = ScreenEyes.describe(context)
        saveEyesDiag(rawText, "describe:" + result.kind.name)
        return when (result.kind) {
            ScreenEyes.Kind.OK ->
                CommandResult(result.text!!, true)
            ScreenEyes.Kind.NO_KEY ->
                CommandResult(eyesNoKeyReply(lang), true)
            ScreenEyes.Kind.NO_ACCESSIBILITY ->
                CommandResult(eyesNeedsAccessibilityReply(lang), true)
            ScreenEyes.Kind.NO_SHOT ->
                CommandResult(eyesShotFailedReply(lang), true)
            ScreenEyes.Kind.SECURE ->
                CommandResult(eyesSecureReply(lang), true)
            else ->
                CommandResult(eyesVisionFailedReply(lang), true)
        }
    }

    /**
     * v95: "এই স্ক্রিনে [X] কোথায়" / "where is [X] on the
     * screen" jaisa hukm hai kya — "কোথায়/where" + "স্ক্রিন/
     * screen" dono chahiye, warna ye find nahi hai।
     */
    fun isFindOnScreenCommand(rawText: String): Boolean {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        fun wordMatched(markers: List<String>): Boolean =
            markers.any { marker ->
                val needle = hindiSkeleton(marker)
                surfaces.any { it.contains(needle) }
            }
        return wordMatched(EYES_WHERE_WORDS) &&
            wordMatched(EYES_SCREEN_WORDS)
    }

    /**
     * v95: [X] screen par kahan hai — pehle ped se, na
     * mile to vision se; jagah hamesha hisse ke asal
     * chaukone se। BANDHA call hai: background thread se hi।
     */
    fun findOnScreenCommand(rawText: String): CommandResult? {
        if (!isFindOnScreenCommand(rawText)) return null
        val lang = PlayIntent.requestLanguage(rawText)
        val target = extractFindTarget(rawText)
        if (target.isNullOrBlank()) {
            saveEyesDiag(rawText, "find:EMPTY")
            return CommandResult(eyesFindWhatReply(lang), true)
        }
        val result = ScreenEyes.find(context, target)
        saveEyesDiag(rawText, "find:" + result.kind.name)
        return when (result.kind) {
            ScreenEyes.Kind.OK -> {
                val spot = result.spot!!
                CommandResult(eyesFindFoundReply(lang, target, spot), true)
            }
            ScreenEyes.Kind.NOT_FOUND ->
                CommandResult(eyesFindNotFoundReply(lang, target), true)
            ScreenEyes.Kind.NO_KEY ->
                CommandResult(eyesNoKeyReply(lang), true)
            ScreenEyes.Kind.NO_ACCESSIBILITY ->
                CommandResult(eyesNeedsAccessibilityReply(lang), true)
            ScreenEyes.Kind.NO_SHOT ->
                CommandResult(eyesShotFailedReply(lang), true)
            ScreenEyes.Kind.SECURE ->
                CommandResult(eyesSecureReply(lang), true)
            else ->
                CommandResult(eyesVisionFailedReply(lang), true)
        }
    }

    /** v95: "স্ক্রিনটা পড়ে শোনাও" / "read the screen" jaisa hukm hai kya। */
    fun isReadScreenCommand(rawText: String): Boolean {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        return eyesPhraseMatched(raw, EYES_READ_PHRASES)
    }

    /**
     * v95: screen ka dikhai dene wala text padhkar sunao —
     * pehle ped se, na mile to vision se। BANDHA call hai:
     * background thread se hi bulana।
     */
    fun readScreenCommand(rawText: String): CommandResult? {
        if (!isReadScreenCommand(rawText)) return null
        val lang = PlayIntent.requestLanguage(rawText)
        val result = ScreenEyes.readAloud(context)
        saveEyesDiag(rawText, "read:" + result.kind.name)
        return when (result.kind) {
            ScreenEyes.Kind.OK ->
                CommandResult(eyesReadIntroReply(lang, result.text!!), true)
            ScreenEyes.Kind.NO_KEY ->
                CommandResult(eyesNoKeyReply(lang), true)
            ScreenEyes.Kind.NO_ACCESSIBILITY ->
                CommandResult(eyesNeedsAccessibilityReply(lang), true)
            ScreenEyes.Kind.NO_SHOT ->
                CommandResult(eyesShotFailedReply(lang), true)
            ScreenEyes.Kind.SECURE ->
                CommandResult(eyesSecureReply(lang), true)
            else ->
                CommandResult(eyesVisionFailedReply(lang), true)
        }
    }

    /**
     * v95: find wale hukm se nishana nikalo — "কোথায়/
     * where" + "স্ক্রিন/screen" + bhare shabd hatakar
     * bacha hua hi nishana hai; kuch na bache to null।
     */
    private fun extractFindTarget(rawText: String): String? {
        val tokens = rawText.lowercase(Locale.ROOT).trim()
            .split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() }
        val kept = tokens.filter { it !in EYES_FIND_DROP_TOKENS }
        val joined = kept.joinToString(" ").trim()
        return if (joined.isEmpty()) null else joined
    }

    /**
     * v95: bandhe wakya ka milan — v92 ke extractTapTarget
     * wali hi shaili (kachha + normalize + do skeleton
     * satahen), taaki Bangla/Hindi ke tode hue roop bhi milen।
     */
    private fun eyesPhraseMatched(raw: String, markers: List<String>): Boolean {
        val surfaces = listOf(raw, normalize(raw))
            .flatMap { listOf(scrollSkeleton(it), hindiSkeleton(it)) }
            .distinct()
        return markers.any { marker ->
            val needles = listOf(
                scrollSkeleton(marker), hindiSkeleton(marker)
            ).distinct()
            surfaces.any { surface ->
                needles.any { surface.contains(it) }
            }
        }
    }

    /** v95: mile hisse ki jagah — user ki bhasha me ("উপরে বাঁদিকে" ...)। */
    private fun zonePhrase(
        spot: ScreenEyes.FoundSpot,
        lang: PlayIntent.SpokenLang
    ): String {
        val key = spot.vertical + "-" + spot.horizontal
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> when (key) {
                "top-left" -> "উপরে বাঁদিকে"
                "top-centre" -> "উপরে মাঝখানে"
                "top-right" -> "উপরে ডানদিকে"
                "middle-left" -> "মাঝখানে বাঁদিকে"
                "middle-centre" -> "মাঝখানে"
                "middle-right" -> "মাঝখানে ডানদিকে"
                "bottom-left" -> "নিচে বাঁদিকে"
                "bottom-centre" -> "নিচে মাঝখানে"
                "bottom-right" -> "নিচে ডানদিকে"
                else -> "স্ক্রিনে"
            }
            PlayIntent.SpokenLang.HINDI -> when (key) {
                "top-left" -> "ऊपर बाईं ओर"
                "top-centre" -> "ऊपर बीच में"
                "top-right" -> "ऊपर दाईं ओर"
                "middle-left" -> "बीच में बाईं ओर"
                "middle-centre" -> "बीच में"
                "middle-right" -> "बीच में दाईं ओर"
                "bottom-left" -> "नीचे बाईं ओर"
                "bottom-centre" -> "नीचे बीच में"
                "bottom-right" -> "नीचे दाईं ओर"
                else -> "स्क्रीन पर"
            }
            PlayIntent.SpokenLang.ENGLISH -> when (key) {
                "top-left" -> "top left"
                "top-centre" -> "top centre"
                "top-right" -> "top right"
                "middle-left" -> "middle left"
                "middle-centre" -> "in the middle"
                "middle-right" -> "middle right"
                "bottom-left" -> "bottom left"
                "bottom-centre" -> "bottom centre"
                "bottom-right" -> "bottom right"
                else -> "on the screen"
            }
        }
    }

    /** v95: mila — jagah ke saath। */
    private fun eyesFindFoundReply(
        lang: PlayIntent.SpokenLang,
        target: String,
        spot: ScreenEyes.FoundSpot
    ): String {
        val zone = zonePhrase(spot, lang)
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "'$target' স্ক্রিনে আছে — $zone।"
            PlayIntent.SpokenLang.HINDI ->
                "'$target' स्क्रीन पर है — $zone।"
            PlayIntent.SpokenLang.ENGLISH ->
                "'$target' is on the screen — $zone."
        }
    }

    /** v95: nahi mila — imaandaar "পেলাম না"। */
    private fun eyesFindNotFoundReply(
        lang: PlayIntent.SpokenLang,
        target: String
    ): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনে '$target' পেলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन पर '$target' नहीं मिला।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't find '$target' on the screen."
        }

    /** v95: kya dhoondhna hai, wohi nahi bola। */
    private fun eyesFindWhatReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনে কী খুঁজবো, সেটা বলো।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन पर क्या ढूँढूँ, वो बताओ।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Tell me what to look for on the screen."
        }

    /** v95: padhkar sunane ka mukh — phir screen ka apna text। */
    private fun eyesReadIntroReply(
        lang: PlayIntent.SpokenLang,
        text: String
    ): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনে লেখা আছে: $text"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन पर लिखा है: $text"
            PlayIntent.SpokenLang.ENGLISH ->
                "On the screen it says: $text"
        }

    /** v95: screen dekhne ke liye Gemini key chahiye (API Keys screen se)। */
    private fun eyesNoKeyReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিন দেখতে Gemini key লাগবে — API Keys থেকে key দাও।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन देखने के लिए Gemini key चाहिए — API Keys में key डालो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I need the Gemini key to see the screen — add it in API Keys."
        }

    /** v95: screen dekhne ke liye Accessibility pehle chahiye। */
    private fun eyesNeedsAccessibilityReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিন দেখতে Accessibility লাগবে — অ্যাপ খুলে চালু করে নাও।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन देखने के लिए पहले ऐप खोलकर Accessibility ऑन कर लो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Seeing the screen needs Accessibility — please open the app and turn it on."
        }

    /** v95: tasveer hi nahi mili — sachha nakam jawab। */
    private fun eyesShotFailedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনের ছবি নিতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन की तस्वीर नहीं ले पाई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't capture the screen."
        }

    /** v95: screen surakshit hai — dekhna mana hai। */
    private fun eyesSecureReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "এই স্ক্রিনটা সুরক্ষিত — এটা দেখতে পারবো না।"
            PlayIntent.SpokenLang.HINDI ->
                "यह स्क्रीन सुरक्षित है — इसे देख नहीं सकती।"
            PlayIntent.SpokenLang.ENGLISH ->
                "This screen is protected, so I can't see it."
        }

    /** v95: vision koshish asafal — sachha jawab, andaza nahi। */
    private fun eyesVisionFailedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনটা দেখতে পারলাম না — আবার বলো।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीन देख नहीं पाई — फिर से बोलो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't see the screen — please try again."
        }

    /** v95: aankh wali koshish ki report jarvis_diag me। */
    private fun saveEyesDiag(rawText: String, tag: String) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_screen_eyes",
                    tag + " :: " + rawText.trim().take(40)
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v40: ye suna hua hukm seedha SCREENSHOT lene wala
     * hai kya — "স্ক্রিনশট নাও/তোলো/নে", "स्क्रीनशॉट लो",
     * "screenshot lo / take a screenshot / screenshot"।
     * [deterministicOffCommand] /
     * [deterministicScrollCommand] wali hi shaili: match
     * kachhe (raw) text se, teeno script, aur faisla
     * SASTA hai (koi latch/IO nahi) taaki dono bulaane
     * wale pehle poochh saken. Do suraksha-shartein:
     * (1) screenshot shabd hona hi chahiye;
     * (2) PATHANE/share ka iraada ho to ye seedha hukm
     * NAHI hai — "…করে স্ক্রিনশট পাঠাও" jaise sanyukt
     * hukm agent + share_screenshot auzaar ke hain.
     * Lene wali kriya na mile to nanga "স্ক্রিনশট" sirf
     * chhote (4 shabd tak) hukm-jaise vakya me maana
     * jata hai — lambe vakya me beech me aaya screenshot
     * shabd bharosa-mand hukm nahi hai.
     */
    fun isScreenshotCommand(rawText: String): Boolean {
        val raw = rawText.lowercase(Locale.ROOT).trim()
        if (raw.isEmpty()) return false
        if (SCREENSHOT_SHARE_MARKERS.any { raw.contains(it) }) return false
        // v51 (item 7): shabd-milan skeleton satahon par
        // bhi — "सकरिनशॉट" jaise tode hue roop bhi
        // screenshot shabd maane jayein. Pathane wala
        // veto (upar) aur lene-wali kriya ki jaanch
        // kachhe text par hi rehti hai (suraksha wahi)।
        val surfaces = listOf(raw, hindiSkeleton(raw)).distinct()
        fun wordMatched(markers: List<String>): Boolean =
            markers.any { marker ->
                val needle = hindiSkeleton(marker)
                surfaces.any { it.contains(needle) }
            }
        if (!wordMatched(SCREENSHOT_WORD_MARKERS)) return false
        if (SCREENSHOT_TAKE_VERBS.any { raw.contains(it) }) return true
        val tokens = raw.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        return tokens.size <= 4
    }

    /**
     * v40: pakke screenshot hukm ki asal koshish —
     * v39 accessibility capture se tasveer, phir
     * gallery (MediaStore, "ASHIP JARVIS" album) me
     * bachaakar hukm ki bhasha me chhota sachha jawab।
     * BANDHA (blocking) call hai: capture ka jawab
     * service ke main-executor se aata hai, isliye
     * bulane wala thread MAIN-thread NAHI hona chahiye
     * (foreground raasta apne background executor se,
     * wake-service apne executor thread se bulata hai)।
     * Service band ho, tasveer hi na mile, ya screen
     * surakshit (FLAG_SECURE) ho — teeno me imaan-daar
     * wajah boli jati hai, jhoothi safalta kabhi nahi।
     * Har koshish jarvis_diag me "last_screenshot_command"
     * bankar rehti hai (scroll wali report ki shaili)।
     */
    fun runScreenshotCommand(rawText: String): CommandResult {
        val lang = PlayIntent.requestLanguage(rawText)
        if (!JarvisAccessibilityService.isEnabled(context)) {
            saveScreenshotDiag(rawText, "SERVICE_OFF")
            return CommandResult(screenshotNeedsAccessibilityReply(lang), true)
        }
        val bitmap = ScreenshotStore.captureFresh(context)
        if (bitmap == null) {
            saveScreenshotDiag(rawText, "FAIL")
            return CommandResult(screenshotFailedReply(lang), true)
        }
        if (ScreenshotStore.screenLooksSecure(context, bitmap)) {
            saveScreenshotDiag(rawText, "SECURE")
            return CommandResult(screenshotSecureReply(lang), true)
        }
        val saved = ScreenshotStore.saveToGallery(context, bitmap)
        saveScreenshotDiag(rawText, if (saved) "OK" else "FAIL")
        return if (saved) {
            CommandResult(screenshotSavedReply(lang), true)
        } else {
            CommandResult(screenshotFailedReply(lang), true)
        }
    }

    /** v40: screenshot bach gaya — hukm ki bhasha me chhoti tasalli. */
    private fun screenshotSavedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনশট নিয়ে গ্যালারিতে রেখে দিয়েছি।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीनशॉट लेकर गैलरी में रख दिया है।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Screenshot taken and saved to your gallery."
        }

    /** v40: screen surakshit hai — screenshot ho hi nahi sakta. */
    private fun screenshotSecureReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "এই স্ক্রিনটা সুরক্ষিত — এর স্ক্রিনশট নেওয়া যাবে না।"
            PlayIntent.SpokenLang.HINDI ->
                "यह स्क्रीन सुरक्षित है — इसका स्क्रीनशॉट नहीं लिया जा सकता।"
            PlayIntent.SpokenLang.ENGLISH ->
                "This screen is protected, so I can't take its screenshot."
        }

    /** v40: capture/bachana asafal — sachha nakam jawab. */
    private fun screenshotFailedReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনশট নিতে পারলাম না।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीनशॉट नहीं ले पाई।"
            PlayIntent.SpokenLang.ENGLISH ->
                "I couldn't take the screenshot."
        }

    /** v40: screenshot ke liye Accessibility pehle chahiye. */
    private fun screenshotNeedsAccessibilityReply(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "স্ক্রিনশট নিতে Accessibility লাগবে — অ্যাপ খুলে চালু করে নাও।"
            PlayIntent.SpokenLang.HINDI ->
                "स्क्रीनशॉट लेने के लिए पहले ऐप खोलकर Accessibility ऑन कर लो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Taking a screenshot needs Accessibility — " +
                    "please open the app and turn it on."
        }

    /** v40: screenshot koshish ki report jarvis_diag me — suna hua hukm + natija. */
    private fun saveScreenshotDiag(rawText: String, tag: String) {
        try {
            context.getSharedPreferences("jarvis_diag", Context.MODE_PRIVATE)
                .edit()
                .putString(
                    "last_screenshot_command",
                    "screenshot: " + rawText.trim().take(40) + " -> " + tag
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal hukm isse nahi rukta.
        }
    }

    /**
     * v25: agent ke auzaar (AgentTools) ke liye seedha
     * app-kholna. Raasta wahi pakka hai jo "kholo" command
     * leta hai — pehle fixed list, phir launcher ke labels
     * (galat-suna naam sudhaar ke saath) — par ek farq ke
     * saath: yahan [CommandResult.handled] me ASAL safalta
     * bolti hai. Aam command raaste me handled ka matlab
     * "samajh aaya" hota hai (koshish asafal ho tab bhi
     * true); agent ko sach chahiye — khula to true, na
     * khula (nahi mila / overlay permission / lock) to
     * false, aur jawab ke text me wajah bhi. handle() /
     * dispatch() ki koi harkat isse nahi badli.
     */
    fun tryOpenApp(spokenName: String): CommandResult {
        val name = stripFillers(normalize(spokenName.lowercase(Locale.ROOT).trim()))
        if (name.isEmpty()) {
            return CommandResult("ऐप का नाम समझ नहीं आया।", false)
        }
        for ((keyword, target) in appTargets) {
            if (name.contains(keyword)) {
                return openTargetHonest(target)
            }
        }
        // Dispatch wala hi label-match: seedha, warna
        // galat-suna naam sudhaarkar dobara.
        val match = findLauncherApp(name)
            ?: findLauncherApp(stripFillers(normalize(fuzzyCorrect(name))))
        return if (match != null) {
            openTargetHonest(
                AppTarget(match.label, TargetKind.PACKAGE, match.packageName)
            )
        } else {
            CommandResult("'$spokenName' नाम की ऐप इस फ़ोन में नहीं मिली।", false)
        }
    }

    /**
     * v46: [tryOpenApp] wali hi pehchan, bina khole —
     * bola gaya naam asal me kis package par utrega,
     * sirf woh batao (na utre to null). ScreenAgent ka
     * suraksha-parda (ScreenSafety) open_app wale kadam
     * se pehle isiliye istemal karta hai: banking/payment
     * wali app khulne se pehle hi ruk jaye. tryOpenApp
     * ka bartao isse bilkul nahi badla — ye usi khoj ka
     * sirf-padhne wala roop hai (bandhi kataar, phir
     * launcher naam, phir galat-suna sudhaar)।
     */
    fun resolveAppPackage(spokenName: String): String? {
        val name = stripFillers(
            normalize(spokenName.lowercase(Locale.ROOT).trim())
        )
        if (name.isEmpty()) return null
        for ((keyword, target) in appTargets) {
            if (name.contains(keyword)) {
                return if (target.kind == TargetKind.PACKAGE) {
                    target.packageName
                } else {
                    null
                }
            }
        }
        val match = findLauncherApp(name)
            ?: findLauncherApp(
                stripFillers(normalize(fuzzyCorrect(name)))
            )
        return match?.packageName
    }

    /**
     * [openTarget] ki hi koshish, shart ek: jawab ke
     * saath handled me asal safalta (khula = true; nahi
     * mila / permission / lock = false). Aam raasta
     * [openTarget] bilkul pehle jaisa hi rehta hai — ye
     * roop sirf v25 agent ke liye hai, jise "samajh aaya"
     * nahi, "sach me hua" chahiye.
     */
    private fun openTargetHonest(target: AppTarget): CommandResult {
        if (fromBackgroundService) {
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
                    CommandResult(OVERLAY_PERMISSION_REPLY, false)
                LaunchOutcome.LOCKED ->
                    CommandResult(LOCK_REPLY, false)
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    CommandResult(
                        "${target.displayName} इस फ़ोन में नहीं मिला या खुल नहीं पाया।",
                        false
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
            CommandResult(
                "${target.displayName} इस फ़ोन में नहीं मिला या खुल नहीं पाया।",
                false
            )
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

    // v94: companion public — teardown() calls
    // CommandEngine.stopContinuousScroll() (v92) and the
    // private companion made it uncompilable (CI v92:
    // "Cannot access 'companion object Companion': it is
    // private"). The state inside stays private; only the
    // stop function and the reply constants are reachable.
    companion object {
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

        /**
         * v18: toggle ho gaya par dabane ke baad ki haalat padh
         * nahi paye to Maya ka sachha jawab (Devanagari shaili).
         */
        const val DATA_TOGGLED_REPLY =
            "डेटा का बटन दबा दिया — ऑन था तो बंद, बंद था तो ऑन हो गया।"

        /**
         * v57: vision se tile dab to gaya, par dabane ke
         * baad ki haalat padh nahi paye to Maya ka sachha
         * jawab (Devanagari shaili) — dabna pakka, natija nahi.
         */
        const val DATA_VISION_UNKNOWN_REPLY =
            "डेटा वाला बटन छवि देखकर दबा दिया, पर ऑन हुआ या नहीं, यह पढ़ नहीं पाई।"

        /**
         * v62: vision chahiye tha par Gemini key set hi nahi —
         * "nahi mila" nahi, key ki kami hai (Devanagari shaili)।
         */
        const val DATA_NO_KEY_REPLY =
            "तस्वीर देखकर बटन खोजने के लिए Gemini key चाहिए — " +
                "API Keys से key डालो, फिर मैं डेटा ऑन कर पाऊँगा।"

        /**
         * v17: Accessibility toggle ke natije ka adhiktam
         * intezaar. v57: 4s se 25s — vision fallback ko
         * samay chahiye (text khoj 8s tak + Gemini round
         * trip + jaanch); aam raasta ab bhi 1-3s me hi
         * laut aata hai, lambi seema sirf fallback par
         * lagti hai. v62: 25s se 45s — vivo par har baar
         * pura vision raasta chalta hai (AgentTools wali
         * seema ke barabar)। v65: 45s se 60s — vision
         * kataar (3 mukhya model, har koshish 30s judna
         * + 60s padhna) dheeme net par 45s se zyada le
         * sakti hai; seema beetne par Maya jhootha "tile
         * nahi mila" bolti hai jabki service abhi kaam kar
         * rahi hoti hai।
         */
        const val DATA_COMMAND_WAIT_MS = 60_000L

        /** v38: scroll swipe ke natije ka adhiktam intezaar (swipe ~350 ms ka hai). v42: do-mashinari wale raaste (node, phir do swipe) ko 5 s diya. */
        const val SCROLL_COMMAND_WAIT_MS = 5_000L

        /**
         * v92: lagatar scroll ka bandha antaral — har
         * [CONTINUOUS_SCROLL_INTERVAL_MS] par ek chhota
         * scroll (scrollCommandEx wala ek kadam)। 500 ms
         * = dheema, padhne layak scroll; tez nahi।
         */
        const val CONTINUOUS_SCROLL_INTERVAL_MS = 500L

        /** v92: ek tap-koshish (capture + tap) ka adhiktam intezaar। */
        const val TAP_COMMAND_WAIT_MS = 5_000L

        /** v92: back global action ka adhiktam intezaar। */
        const val BACK_COMMAND_WAIT_MS = 3_000L

        /**
         * v92: lagatar scroll ki SAANJHI haalat — saare
         * CommandEngine instance (wake service wala aur
         * MainActivity wala) ek hi scroll dekhte hain,
         * isliye haalat companion me hai, instance me
         * nahi। [continuousScrollActive] kisi bhi thread
         * se padha jata hai (@Volatile); tick sirf main
         * looper wale [continuousScrollHandler] par
         * chalta hai, isliye start/stop kabhi aapas me
         * nahi ladte। [continuousScrollRunnable] ka
         * initializer sirf [continuousScrollTick] function
         * ko bulata hai — property khud ko nahi (v75
         * wali galti se bachne ke liye explicit type
         * ke saath)।
         */
        @Volatile
        private var continuousScrollActive = false

        @Volatile
        private var continuousScrollDown = true

        @Volatile
        private var continuousScrollContext: Context? = null

        private val continuousScrollHandler: Handler =
            Handler(Looper.getMainLooper())

        private val continuousScrollRunnable: Runnable =
            Runnable { continuousScrollTick() }

        /**
         * v92: lagatar scroll ka ek tick — ek chhota
         * scroll, phir agla tick [CONTINUOUS_SCROLL_INTERVAL_MS]
         * baad। Ruka hua ho, ya Accessibility band ho
         * gaya ho, to tick khud ruk jata hai (band hone
         * par [stopContinuousScroll] se safaai bhi)।
         */
        private fun continuousScrollTick() {
            if (!continuousScrollActive) return
            val ctx = continuousScrollContext ?: run {
                continuousScrollActive = false
                return
            }
            if (!JarvisAccessibilityService.isEnabled(ctx)) {
                stopContinuousScroll()
                return
            }
            val down = continuousScrollDown
            try {
                JarvisAccessibilityService.scrollCommandEx(ctx, down) { }
            } catch (t: Throwable) {
                // Ek tick fail ho to bhi rukna nahi — agla
                // tick phir aazmayega; rukna sirf "থামো"
                // / stop se hota hai।
            }
            continuousScrollHandler.postDelayed(
                continuousScrollRunnable, CONTINUOUS_SCROLL_INTERVAL_MS
            )
        }

        /** v92: lagatar scroll chal raha hai kya — kisi bhi raaste se poochho। */
        fun isContinuousScrolling(): Boolean = continuousScrollActive

        /**
         * v92: lagatar scroll turant roko — pending tick
         * handler se hat jata hai, isliye agla scroll
         * kabhi nahi aata। Beech me chal raha ek swipe
         * (~350 ms) hi aakhiri kadam hota hai, use
         * beech me roka nahi ja sakta। Chal raha tha
         * to true, pehle se ruka tha to false। Service
         * band hone / full shutdown par bhi yahi bulaya
         * jata hai, taaki scroll peeche se chalta na rahe।
         */
        fun stopContinuousScroll(): Boolean {
            val was = continuousScrollActive
            continuousScrollActive = false
            continuousScrollContext = null
            try {
                continuousScrollHandler.removeCallbacks(continuousScrollRunnable)
            } catch (t: Throwable) {
                // Handler band ho to bhi haalat saaf hai —
                // tick pehli jaanch me hi ruk jayega।
            }
            return was
        }

        /** v92: lagatar scroll shuru — purana tick hatakar nayi disha se। */
        private fun startContinuousScroll(context: Context, down: Boolean) {
            stopContinuousScroll()
            continuousScrollDown = down
            continuousScrollContext = context.applicationContext
            continuousScrollActive = true
            continuousScrollHandler.post(continuousScrollRunnable)
        }

        /**
         * v92: scroll roko wale shabd — bandha wakya
         * (skeleton satahon par contains)।
         */
        val STOP_SCROLL_PHRASES = listOf(
            "stop scrolling", "scroll roko", "scroll tham",
            "স্ক্রল থামাও", "স্ক্রল থামো", "স্ক্রল থেমে যাও",
            "স্ক্রল থামিয়ে দাও", "स्क्रॉल रुको", "स्क्रॉल थम",
            "ruk jao", "রুক যাও", "থেমে যাও", "থামিয়ে দাও"
        )

        /** v92: scroll roko wale akele shabd — poora token milaya jata hai। */
        val STOP_SCROLL_TOKENS = listOf(
            "থামো", "থাম", "থামাও", "থেমে", "স্টপ", "stop",
            "রুকো", "রুক", "रुको", "रुक", "थम", "थमो", "থামিয়ে"
        )

        /**
         * v92: "stop" kabhi scroll-stop nahi hai — gaana /
         * timer / alarm / video wala stop aam raaste ka hai।
         */
        val STOP_GUARD_WORDS = listOf(
            "গান", "gaana", "music", "song", "timer", "টাইমার",
            "alarm", "অ্যালার্ম", "video", "ভিডিও"
        )

        /**
         * v92: tap/click kriya — bandha wakya (skeleton
         * satahon par contains)।
         */
        val TAP_VERB_PHRASES = listOf(
            "ট্যাপ করো", "ট্যাপ কর", "ক্লিক করো", "ক্লিক কর",
            "tap karo", "click karo", "tap koro", "click koro",
            "टैप करो", "टैप कर", "क्लिक करो", "क्लिक कर",
            "টাচ করো", "চাপ দাও", "চাপো"
        )

        /** v92: tap/click kriya — akele shabd, poora token milaya jata hai। */
        val TAP_VERB_TOKENS = listOf(
            "tap", "click", "ট্যাপ", "ক্লিক", "টাচ", "চাপো",
            "टैप", "क्लिक"
        )

        /**
         * v92: tap wale hukm se nishana nikalte samay
         * hataye jaane wale bhare shabd (kriya + ishara)।
         */
        val TAP_FILLER_TOKENS = setOf(
            "করো", "কর", "করুন", "দাও", "দিন", "দে",
            "karo", "koro", "do", "kore", "dao",
            "এটা", "এই", "এইটা", "ওই", "ওইটা", "টাকে",
            "the", "this", "that", "it", "on", "please",
            "এখন", "একটু", "টা", "টি", "কে", "করে"
        )

        /** v92: "এই ভিডিওটা" jaisa aam nishana batane wale shabd। */
        val GENERIC_VIDEO_WORDS = setOf(
            "ভিডিও", "ভিডিওটা", "ভিডিওটি", "ভিডিওটাকে",
            "ভিডিওটায়", "video", "ভিডিয়ো", "ভিডিয়োটা"
        )

        /** v92: kram wale shabd ("দ্বিতীয়" = 1, "তৃতীয়" = 2 ...)। */
        val TAP_ORDINAL_WORDS = mapOf(
            "প্রথম" to 0, "first" to 0, "1st" to 0,
            "দ্বিতীয়" to 1, "second" to 1, "2nd" to 1,
            "তৃতীয়" to 2, "third" to 2, "3rd" to 2
        )

        /** v92: "2 নম্বর" jaisa kram — "নম্বর" se pehle wala number। */
        val TAP_ORDINAL_NUMBERS = mapOf(
            "১" to 0, "1" to 0, "এক" to 0,
            "২" to 1, "2" to 1, "দুই" to 1,
            "৩" to 2, "3" to 2, "তিন" to 2
        )

        /**
         * v92: back wale bandhe wakya — skeleton satahon
         * par contains (jaise "পেছনে যাও" / "go back")।
         */
        val BACK_PHRASES = listOf(
            "go back", "পেছনে যাও", "পিছনে যাও", "পেছনে যাও",
            "পিছে যাও", "পেছনে ফিরে যাও", "ফিরে যাও",
            "पीछे जाओ", "पीछे जाओ", "back jao", "back karo",
            "ব্যাক করো", "ব্যাক কর", "wapas jao", "ওয়াপস যাও"
        )

        /** v92: back wale akele shabd — poora token milaya jata hai ("background" nahi phansta)। */
        val BACK_TOKENS = listOf(
            "back", "ব্যাক", "পেছনে", "পিছনে", "পিছে", "पीछे"
        )

        /** v92: lambe vakya me back-token ke saath chahiye wali kriya। */
        val BACK_VERB_TOKENS = listOf(
            "করো", "কর", "যাও", "যা", "karo", "jao", "जा", "जाओ"
        )

        /** v92: "come back tomorrow" jaisa bhavishya wala vakya back hukm nahi hai। */
        val BACK_GUARD_WORDS = listOf(
            "tomorrow", "later", "পরে", "কাল", "আসবে", "আসব"
        )

        /**
         * v38 + v42: scroll-down ke pakke sanket — kachhe text
         * par contains milaane ke liye. v42 me Bangla
         * recognizer ke jaane-pehchane garble roop jude:
         * স্কুল / ইস্ক্রল / স্‌ক্রল / এস্ক্রল ("স্ক্রল" ka
         * galat suna roop — user khud "স্কুল ডাউন" likhkar
         * bhej chuka hai) aur Roman "iskrol/escroll"।
         */
        val SCROLL_DOWN_MARKERS = listOf(
            "scroll down", "iskrol down", "escroll down",
            "স্ক্রল ডাউন", "স্ক্রোল ডাউন", "স্ক্রল ডাউন",
            "স্কুল ডাউন", "স্কুল নামাও", "স্কুল করো",
            "ইস্ক্রল ডাউন", "এস্ক্রল ডাউন", "স্ক্রল ডাউন",
            "स्क्रॉल डाउन", "स्क्रोल डाउन",
            "নিচে নামাও", "নিচে নামা", "নিচে যাও", "নিচে করো", "নিচে কর",
            "नीचे करो", "नीचे कर", "neeche karo", "neeche kar", "niche karo",
            // v50: aur garble / boli ke roop — recognizer
            // "ডাউন" ko डাওন/দাউন, "স্ক্রল" ko স্করল/সক্রল
            // bhi sunta hai; Hindi kaan Bangla boli ko
            // "स्कूल डाउन" likhta hai; "পেজ ডাউন" bhi
            // aam boli hai।
            "স্ক্রল নামাও", "স্ক্রোল নামাও", "স্ক্রল করে নামাও",
            "স্ক্রল ডাওন", "স্ক্রোল ডাওন", "স্ক্রল দাউন",
            "স্কুল ডাওন", "স্করল ডাউন", "সক্রল ডাউন",
            "স্ক্রল নিচে", "নিচে স্ক্রল", "স্কুল নিচে",
            "নিচে নামিয়ে দাও", "নিচে নিয়ে যাও", "নিচে স্ক্রল করো",
            "পেজ ডাউন", "page down", "scroll daun",
            "skrol down", "scrol down",
            "स्कूल डाउन", "स्कूल नीचे", "इसक्रॉल डाउन",
            "एसक्रॉल डाउन", "डाउन करो", "डाउन कर"
        )

        /** v38 + v42: scroll-up ke pakke sanket (v42: wahi garble roop upar ke liye). */
        val SCROLL_UP_MARKERS = listOf(
            "scroll up", "iskrol up", "escroll up",
            "স্ক্রল আপ", "স্ক্রোল আপ", "স্ক্রল আপ",
            "স্কুল আপ", "স্কুল তোলো", "স্কুল ওঠো",
            "ইস্ক্রল আপ", "এস্ক্রল আপ", "স্ক্রল আপ",
            "स्क्रॉल अप", "स्क्रोल अप",
            "উপরে তোলো", "উপরে তোল", "উপরে ওঠো", "উপরে ওঠ", "উপরে করো",
            "ऊपर करो", "ऊपर कर", "upar karo", "upore tolo",
            // v50: wahi vistaar upar ke liye।
            "স্ক্রল উপরে", "স্ক্রল ওপরে", "উপরে স্ক্রল",
            "ওপরে স্ক্রল", "স্ক্রল উঠাও", "স্ক্রল তোলো",
            "স্কুল ওপরে", "পেজ আপ", "page up",
            "स्कूल अप", "स्कूल ऊपर", "ऊपर स्क्रॉल",
            "स्क्रॉल ऊपर", "अप करो", "अप कर"
        )

        /**
         * v38 + v42: nanga scroll shabd — disha ka sanket na
         * mile to neeche wala iraada. v42: garble roop bhi —
         * স্কুল / ইস্ক্রল / স্ক্রল / এস্ক্রল aur Roman
         * iskrol/escroll/skrol. v50: aur garble roop —
         * স্করল / সক্রল / স্কল / ইসক্রল / এসক্রল / scrol /
         * स्कूल / इसक्रॉल।
         */
        val SCROLL_WORD_MARKERS = listOf(
            "scroll", "iskrol", "escroll", "skrol",
            "স্ক্রল", "স্ক্রোল", "স্ক্রল",
            "স্কুল", "ইস্ক্রল", "এস্ক্রল", "স্ক্রল",
            "स्क्रॉल", "स्क्रोल",
            "স্করল", "সক্রল", "স্কল", "ইসক্রল", "এসক্রল",
            "scrol", "skroll",
            "स्कूल", "इसक्रॉल", "एसक्रॉल", "स्क्राल"
        )

        /**
         * v50: scroll shabd ke saath akele disha batane
         * wale shabd — bandha disha-wakya na mile to
         * yehi disha bante hain ("নিচে স্ক্রল করো")।
         * Sirf scroll shabd ke saath hi inka matlab
         * hai; akele inpar kabhi scroll nahi banta।
         */
        val SCROLL_DOWN_TOKENS = listOf(
            "ডাউন", "ডাওন", "দাউন", "নিচে", "নীচে",
            "down", "daun", "नीचे", "डाउन"
        )

        /** v50: upar wale disha-shabd (upar wala jod dekho)। */
        val SCROLL_UP_TOKENS = listOf(
            "আপ", "উপরে", "ওপরে", "up", "ऊपर", "अप"
        )

        /**
         * v42: Latin "school" scroll NAHI hai — ye shabd
         * aaye aur scroll ka koi aur pakka sanket na mile
         * to pakka hukm nahi banta. (Bangla wala স্কুল
         * isse alag hai: woh upar garble suchi me
         * jaan-boojh kar rakha hai — Bangla boli me
         * "স্ক্রল" ka suna roop wahi banta hai, aur
         * "স্কুল ডাউন করো" bolne wala school nahi,
         * scroll hi chahta hai.)
         */
        val SCROLL_SCHOOL_GUARD_MARKERS = listOf(
            "school"
        )

        /** v38: nange scroll shabd ke saath hukm wali kriya hai kya. v50: করে/দাও/do wale aur roop। */
        val SCROLL_DO_VERBS = listOf(
            "করো", "কর", "করে", "করে দাও", "দাও", "দিন",
            "करो", "कर", "दो", "दीजिए",
            "karo", "koro", "do", "please", "now"
        )

        /**
         * v51 (item 7): skeleton-sudhaar ki jodi-suchi —
         * (suna ja sakne wala roop -> pakka roop)। Pakka
         * roop wahi hain jo [normalize] ki bandhi suchi
         * jaanti hai; isliye sudhra vakya aam dispatch
         * se hi guzarta hai. Zyada-tar jodiyan canonical
         * shabd khud hain (skeleton-milan unke garble
         * roopon ko khud pakad leta hai); kuch spasht
         * variant jodiyan bhi hain jahan skeleton bhi
         * ek nahi banta (टार्च/टर्च -> टॉर्च aadi)।
         * Suchi jaan-boojh kar upar ke pakke hukmon
         * (torch, data, kholne ki kriya, aam apps) tak
         * simit hai — har shabd sudhaarna iska kaam
         * nahi।
         */
        val SKELETON_CANON_PAIRS = listOf(
            // Torch / light
            "टॉर्च" to "टॉर्च", "टार्च" to "टॉर्च",
            "टर्च" to "टॉर्च", "टोर्च" to "टॉर्च",
            "फ्लैशलाइट" to "फ्लैशलाइट",
            "लाइट" to "लाइट",
            "টর্চ" to "টর্চ", "ফ্ল্যাশলাইট" to "ফ্ল্যাশলাইট",
            "লাইট" to "লাইট",
            // On / off / kriya shabd
            "बंद" to "बंद", "बनद" to "बंद",
            "বন্ধ" to "বন্ধ",
            "जलाओ" to "जलाओ", "जलावो" to "जलाओ",
            "जलादो" to "जलाओ", "जला" to "जलाओ",
            "चालू" to "चालू", "চালু" to "চালু",
            "खोलो" to "खोलो", "खुलो" to "खोलो",
            "খোলো" to "খোলো",
            "चलाओ" to "चलाओ", "চালাও" to "চালাও",
            // Mobile data
            "मोबाइल" to "मोबाइल", "डेटा" to "डेटा",
            "डाटा" to "डेटा",
            "মোবাইল" to "মোবাইল", "ডেটা" to "ডেটা",
            "ডাটা" to "ডেটা",
            // Aam auzaar / apps (normalize wale pakke roop)
            "अलार्म" to "अलार्म", "टाइमर" to "टाइमर",
            "बैटरी" to "बैटरी", "बॅटरी" to "बैटरी",
            "वॉल्यूम" to "वॉल्यूम",
            "समय" to "समय", "तारीख" to "तारीख",
            "गाना" to "गाना", "गाणा" to "गाना",
            "सर्च" to "सर्च", "गूगल" to "गूगल",
            "दिखाओ" to "दिखाओ", "मिनट" to "मिनट",
            "घंटा" to "घंटा", "म्यूट" to "म्यूट",
            "आवाज़" to "आवाज़",
            "यूट्यूब" to "यूट्यूब",
            "व्हाट्सएप" to "व्हाट्सएप",
            "वाट्सएप" to "व्हाट्सएप",
            "व्हट्सएप" to "व्हाट्सएप",
            "फेसबुक" to "फेसबुक",
            "टेलीग्राम" to "टेलीग्राम",
            "इंस्टाग्राम" to "इंस्टाग्राम",
            "क्रोम" to "क्रोम", "कैमरा" to "कैमरा",
            "सेटिंग्स" to "सेटिंग्स",
            "जीमेल" to "जीमेल", "मैप्स" to "मैप्स",
            "फ़ोनपे" to "फ़ोनपे",
            "पेटीएम" to "पेटीएम",
            "स्पॉटिफाई" to "स्पॉटिफाई",
            "स्नैपचैट" to "स्नैपचैट",
            "ইউটিউব" to "ইউটিউব",
            "হোয়াটসঅ্যাপ" to "হোয়াটসঅ্যাপ",
            "টেলিগ্রাম" to "টেলিগ্রাম",
            "ফেসবুক" to "ফেসবুক",
            "ক্যামেরা" to "ক্যামেরা", "ক্রোম" to "ক্রোম"
        )

        /** v50: akele "লাইট" wale hukm ke light-shabd। */
        val LIGHT_TOKENS = setOf("লাইট", "light", "लाइट")

        /**
         * v50: akele-light wale hukm me light-shabd ke
         * saath sirf ye shabd aa sakte hain — isse zyada
         * ho to baat aam vakya hai, light hukm nahi
         * ("ঘরে লাইট আছে" kabhi torch nahi jalayega)।
         */
        val LIGHT_ALLOWED_TOKENS = LIGHT_TOKENS + setOf(
            "জ্বালাও", "জ্বালো", "জ্বালিয়ে", "জ্বালা",
            "দাও", "দে", "অন", "on", "চালু",
            "করো", "কর", "করুন",
            "करो", "कर", "दो", "जलाओ", "जला", "दीजिए",
            "টা"
        )

        /** v59: lock hukm ke kriya shabd। */
        val LOCK_VERBS = setOf(
            "করো", "করুন", "কর", "দাও", "karo", "do", "करो", "कर"
        )

        /** v59: Bangla saptah / maas (Calendar ke kram me)। */
        val BANGLA_WEEKDAYS = listOf(
            "রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার",
            "বৃহস্পতিবার", "শুক্রবার", "শনিবার"
        )
        val BANGLA_MONTHS = listOf(
            "জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন",
            "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"
        )

        /** v40: screenshot shabd ke saare roop — kachhe text par contains milaane ke liye. */
        val SCREENSHOT_WORD_MARKERS = listOf(
            "স্ক্রিনশট", "স্ক্রীনশট", "স্ক্রিন শট",
            "स्क्रीनशॉट", "स्क्रीनशाट", "स्क्रीन शॉट",
            "screenshot", "screen shot"
        )

        /**
         * v40: screenshot LENE wali kriya ke sanket.
         * Bangla "নে" jaan-boojh kar nahi hai — "মনে"-jaise
         * shabdon ke andar ghuskar jhootha match bana
         * sakta hai; nanga chhota "স্ক্রিনশট নে" token-ginati
         * wale raaste se waise bhi pakda jata hai.
         */
        val SCREENSHOT_TAKE_VERBS = listOf(
            "নাও", "নিন", "নেও", "নিয়ে",
            "তোলো", "তুলে", "তোল", "করো", "কর",
            "लो", "ले", "निकालो", "खींचो", "करो",
            "take", "capture", "snap", "lo", "le", "karo", "koro", "now"
        )

        /**
         * v40: pathane/share ke iraade ke sanket — inme
         * se ek bhi ho to hukm seedha screenshot-wala
         * NAHI hai (agent + share_screenshot ka kaam hai).
         */
        val SCREENSHOT_SHARE_MARKERS = listOf(
            "পাঠাও", "পাঠিয়ে", "পাঠান", "পাঠা", "পাঠাতে",
            "শেয়ার", "পাঠিয়ে দাও",
            "send", "share",
            "भेज", "भेजो", "भेजिए", "शेयर"
        )

        /**
         * v95: MAYA'S EYES — describe wale bandhe wakya
         * ("স্ক্রিনে কী আছে বলো" / "describe the screen")।
         * Skeleton satahon par contains milaya jata hai।
         */
        val EYES_DESCRIBE_PHRASES = listOf(
            "স্ক্রিনে কী আছে", "স্ক্রিনে কি আছে",
            "স্ক্রিনে কী দেখা যাচ্ছে", "স্ক্রিনে কি দেখা যাচ্ছে",
            "স্ক্রিনটা দেখো", "স্ক্রিন দেখো",
            "এই স্ক্রিনে কি আছে", "স্ক্রিনে কি আছে বলো",
            "স্ক্রিনে কী আছে বলো", "স্ক্রিনে কি দেখতে পাচ্ছ",
            "स्क्रीन पर क्या है", "स्क्रीन देखो",
            "स्क्रीन में क्या है", "screen par kya hai",
            "screen dekho", "screen pe kya hai",
            "what's on the screen", "whats on the screen",
            "what is on the screen", "describe the screen",
            "look at the screen", "what do you see",
            "what is on this screen"
        )

        /**
         * v95: MAYA'S EYES — padhkar sunane wale bandhe
         * wakya ("স্ক্রিনটা পড়ে শোনাও" / "read the screen")।
         * Read ko describe se PEHLE jaancha jata hai, taaki
         * "read what's on the screen" describe me na ghuse।
         */
        val EYES_READ_PHRASES = listOf(
            "স্ক্রিনটা পড়ে শোনাও", "স্ক্রিন পড়ে শোনাও",
            "স্ক্রিন পড়ো", "স্ক্রিনটা পড়ো",
            "স্ক্রিন পড়ে বলো", "স্ক্রিনে কি লেখা আছে",
            "স্ক্রিনে কী লেখা আছে",
            "स्क्रीन पढ़कर सुनाओ", "स्क्रीन पढ़ो",
            "screen padhkar sunao", "screen padho",
            "screen par kya likha hai",
            "read the screen", "read out the screen",
            "read the screen aloud",
            "read what's on the screen",
            "what's written on the screen",
            "what is written on the screen"
        )

        /** v95: MAYA'S EYES — find wale "কোথায়/where" shabd। */
        val EYES_WHERE_WORDS = listOf(
            "কোথায়", "কোথায়", "where",
            "कहाँ", "कहां", "kahan", "kaha",
            "খুঁজে", "খোঁজো", "khojo", "find"
        )

        /** v95: MAYA'S EYES — find wale "স্ক্রিন/screen" shabd। */
        val EYES_SCREEN_WORDS = listOf(
            "স্ক্রিনে", "স্ক্রিন", "স্ক্রিনটা", "স্ক্রিনটি", "স্ক্রিনের",
            "screen", "स्क्रीन"
        )

        /**
         * v95: MAYA'S EYES — find wale hukm se nishana
         * nikalte samay hataye jaane wale shabd ("কোথায়/
         * where" + "স্ক্রিন/screen" + bhare shabd)।
         */
        val EYES_FIND_DROP_TOKENS = setOf(
            "কোথায়", "কোথায়", "where",
            "कहाँ", "कहां", "kahan", "kaha",
            "খুঁজে", "খোঁজো", "khojo", "find",
            "স্ক্রিনে", "স্ক্রিন", "স্ক্রিনটা", "স্ক্রিনটি", "স্ক্রিনের",
            "screen", "स्क्रीन",
            "এই", "এইটা", "ওই", "টা", "টি",
            "কি", "কী", "আছে", "বলো", "দেখো",
            "is", "on", "the", "a", "an", "it", "me", "to",
            "yeh", "hai", "kya", "batao", "bolo"
        )

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
            // v59: call / message / reminder ke shabd
            "ফোন", "মেসেজ", "করিয়ে", "মনে",
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

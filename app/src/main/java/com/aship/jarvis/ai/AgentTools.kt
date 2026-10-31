package com.aship.jarvis.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.OverlayLauncher
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.CommandEngine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v25: Maya (agent) ke OFFLINE auzaar — phone par asal
 * kaam karne wale haath.
 *
 * Upar baitha Gemini mastishk (AgentBrain) poori baat
 * samajhta hai, online kadam (jawab, faisla, search ke
 * shabd) khud leta hai, aur phone wale kadam in auzaaron
 * se karwata hai. Har auzaar app ki PEHLE SE maujood
 * machine par chalta hai — naya raasta kuch nahi bana:
 * app kholna CommandEngine ke pakke open raaste se,
 * mobile data wahi Accessibility service, torch wahi
 * PhoneActions, web search wahi Google URL wala raasta.
 *
 * Har auzaar ka natija (ok + sachha varnan) mastishk ko
 * wapas jata hai, taaki woh jhootha "ho gaya" kabhi na
 * bole: app na mile, permission na ho, tile na mile —
 * jo sach hai wahi likha jata hai.
 *
 * [fromBackgroundService] wake service ke liye hai —
 * wahan kholne wale kaam overlay bridge se jaate hain,
 * bilkul CommandEngine ki tarah.
 *
 * Execute hamesha kisi background thread se bulana
 * (AgentBrain apne executor se bulata hai): mobile data
 * wala auzaar Accessibility ke natije ka bandha intezaar
 * (latch) karta hai, bilkul CommandEngine ki tarah.
 *
 * v26: ek naya auzaar — youtube_play: mastishk query
 * chunta hai, results yahin se khulte hain (app ho to
 * seedha usi par, warna browser par) aur pehle video
 * par tap Accessibility service karti hai; natija
 * OK / PARTIAL / FAILED me hamesha sachha.
 *
 * v27: do aur SIRF-PADHNE wale auzaar — get_battery aur
 * get_network. "Battery koto ache?" / "net cholche,
 * Wi-Fi na data?" jaise sawalon ka jawab Maya ab
 * andaze se nahi, phone ki asal haalat padhkar deti
 * hai: battery kitne percent hai, charging ho rahi
 * hai ya nahi, aur is pal connection Wi-Fi hai,
 * mobile data hai ya hai hi nahi. Koi nayi permission
 * nahi — battery sticky ghoshna se padhi jati hai,
 * network ConnectivityManager se.
 *
 * v28: do kaam wale auzaar — set_timer aur set_alarm.
 * Dono ghadi (clock) app ke AlarmClock intents se
 * lagte hain; koi nayi permission nahi (AlarmClock
 * intents permission-free hote hain) aur EXTRA_SKIP_UI
 * true hai — jahan support hai wahan bina screen
 * dikhaye lag jata hai. "Laga diya" sirf tab banta
 * hai jab intent asal me kisi resolving activity ko
 * bheja gaya ho; ghadi wala handler hi na mile to
 * imaandaar FAILED — "clock app nahi mila".
 *
 * v29: ek kaam wala auzaar — call_person. Maya
 * contacts me naam dhundhti hai (exact, phir shuru
 * se, phir beech me — bade-chhote akshar aur khaali
 * jagah ka hisaab saaf karke) aur mile hue number ke
 * saath DIALER khol deti hai. Call woh khud NAHI
 * lagati — ACTION_DIAL hai, CALL_PHONE permission
 * li hi nahi gayi; call wala button hamesha user
 * dabata hai. Isi ek auzaar ke liye app ki EK nayi
 * permission hai: READ_CONTACTS. Kai alag log ek hi
 * naam ke milein to Maya dial NAHI karti — ummeedwaar
 * (naam + number ke aakhiri 4 ank) mastishk ko deti
 * hai taaki woh user se poochh sake kaun sa wala.
 *
 * v30: ek aur kaam wala auzaar — whatsapp_message.
 * Khoj call_person wali hi hai (saajhi [findContact]:
 * wahi READ_CONTACTS permission, wahi teen-seedhi
 * wala milan), number nikal kar us sampark ki
 * WhatsApp chat khol deta hai, sandesh pehle se
 * likha hua. Bhejna woh KHUD nahi dabata — send
 * wala button hamesha user khud dabata hai; koi
 * nayi permission nahi lagi.
 *
 * v31: teen yaad wale auzaar — remember_fact,
 * recall_facts, forget_fact. User jo baatein "yaad
 * rakho" bolkar bandhta hai woh [MayaMemory] me
 * sirf phone par rehti hain; mastishk unhe har
 * baatchit me sandarbh ke roop me pata hai
 * (AgentBrain dekho) aur inhi auzaaron se jodta,
 * ginata aur bhulta hai. Yaad ka text reporton
 * (jarvis_diag) me kabhi nahi jata.
 *
 * v32: ek online sirf-padhne wala auzaar —
 * get_weather. "আজকের আবহাওয়া কেমন?" bolne par
 * Maya Open-Meteo se us jagah ka ASAL mausam lati
 * hai: pehle geocoding se jagah ke akshansh/rekhansh,
 * phir forecast se aaj ya kal ka sabse zyada/kam
 * taapmaan, barish ki sambhavna aur mausam ka
 * chhota varnan (WMO code se). Open-Meteo bilkul
 * muft hai aur iske liye koi API key NAHI chahiye —
 * is file me bhi koi key nahi hai. Net band ho,
 * jagah na mile, jawab kharab aaye — har haal me
 * imaandaar FAILED; andaza lagakar sankhya kabhi
 * nahi banti.
 */
class AgentTools(
    private val context: Context,
    private val commandEngine: CommandEngine,
    private val fromBackgroundService: Boolean
) {

    /** Ek auzaar ka natija: asal me hua ya nahi, aur kya hua. */
    data class ToolResult(val ok: Boolean, val text: String)

    /** Naam se auzaar chalao. Anjaan naam par bhi sachha jawab. */
    fun execute(tool: String, args: JSONObject): ToolResult = when (tool) {
        "open_app" -> openApp(args.optString("app").trim())
        "mobile_data" ->
            mobileData(args.optString("state").trim().lowercase(Locale.ROOT))
        "torch" -> torch(args.optString("state").trim().lowercase(Locale.ROOT))
        "web_search" -> webSearch(args.optString("query").trim())
        "youtube_play" -> youtubePlay(args.optString("query").trim())
        "get_time" -> ToolResult(
            true,
            "Current time is " +
                SimpleDateFormat("h:mm a", Locale.US).format(Date()) + "."
        )
        "get_date" -> ToolResult(
            true,
            "Today's date is " +
                SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date()) + "."
        )
        "calculate" -> calculate(args.optString("expression").trim())
        "get_battery" -> getBattery()
        "get_network" -> getNetwork()
        "set_timer" -> setTimer(args.optInt("seconds", -1))
        "set_alarm" -> setAlarm(
            args.optInt("hour", -1),
            args.optInt("minute", 0),
            args.optString("label").trim()
        )
        "call_person" -> callPerson(args.optString("name").trim())
        "whatsapp_message" -> whatsappMessage(
            args.optString("name").trim(),
            args.optString("message").trim()
        )
        "remember_fact" -> rememberFact(args.optString("fact").trim())
        "recall_facts" -> recallFacts()
        "forget_fact" -> forgetFact(args.optString("match").trim())
        "get_weather" -> getWeather(
            args.optString("city").trim(),
            args.optString("when").trim().lowercase(Locale.ROOT)
        )
        else -> ToolResult(
            false,
            "Unknown tool '$tool'. Available tools: open_app, mobile_data, " +
                "torch, web_search, youtube_play, get_time, get_date, " +
                "calculate, get_battery, get_network, set_timer, set_alarm, " +
                "call_person, whatsapp_message, remember_fact, " +
                "recall_facts, forget_fact, get_weather."
        )
    }

    /**
     * App kholna — CommandEngine ka wahi pakka raasta
     * (fixed list, phir launcher labels), par natija
     * [CommandEngine.tryOpenApp] se aata hai jahan handled
     * ka matlab hi asal safalta hai. Jawaab ka text engine
     * ka apna bola hua sach hai (khula / nahi mila /
     * permission / lock) — mastishk ke liye wahi kaafi hai.
     */
    private fun openApp(name: String): ToolResult {
        if (name.isEmpty()) {
            return ToolResult(false, "No app name was given.")
        }
        val result = commandEngine.tryOpenApp(name)
        return ToolResult(result.handled, result.responseText)
    }

    /** Torch — PhoneActions wala wahi seedha control jo engine leta hai. */
    private fun torch(state: String): ToolResult = when (state) {
        "on" -> if (PhoneActions.setTorch(context, true)) {
            ToolResult(true, "Torch is now ON.")
        } else {
            ToolResult(false, "Torch control failed on this phone.")
        }
        "off" -> if (PhoneActions.setTorch(context, false)) {
            ToolResult(true, "Torch is now OFF.")
        } else {
            ToolResult(false, "Torch control failed on this phone.")
        }
        else -> ToolResult(false, "state must be 'on' or 'off'.")
    }

    /**
     * Google search browser me kholo — engine wala wahi
     * URL wala raasta (saamne PhoneActions.openUrl,
     * background me overlay bridge wala roop).
     */
    private fun webSearch(query: String): ToolResult {
        if (query.isEmpty()) {
            return ToolResult(false, "No search query was given.")
        }
        val url = "https://www.google.com/search?q=" +
            URLEncoder.encode(query, "UTF-8")
        return if (fromBackgroundService) {
            when (PhoneActions.openUrlFromBackground(context, url)) {
                LaunchOutcome.LAUNCHED ->
                    ToolResult(true, "Google search for '$query' opened.")
                LaunchOutcome.PERMISSION_NEEDED -> ToolResult(
                    false,
                    "Needs the 'Display over other apps' permission — " +
                        "the user must grant it in the app first."
                )
                LaunchOutcome.LOCKED -> ToolResult(
                    false,
                    "The phone is locked — the search could not be opened."
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    ToolResult(false, "The browser could not be opened.")
            }
        } else if (PhoneActions.openUrl(context, url)) {
            ToolResult(true, "Google search for '$query' opened.")
        } else {
            ToolResult(false, "The browser could not be opened.")
        }
    }

    /**
     * v33: calculate — asal hisaab phone par hi hota hai
     * (BigDecimal wala chhota parser), mastishk ke andaze
     * se nahi. v32 tak koi calculate auzaar tha hi nahi —
     * Gemini dimaag me hisaab karke galtiyan karta tha.
     * Bangla/Devanagari ank bhi seedhe ank ban jate hain;
     * × ÷ − chinh bhi chalte hain. Zero se bhaag, kharab
     * expression — sab par imaandaar FAILED.
     */
    private fun calculate(expression: String): ToolResult {
        if (expression.isEmpty()) {
            return ToolResult(false, "No expression was given to calculate.")
        }
        val normalized = normalizeMathText(expression)
        if (normalized.length > 120) {
            return ToolResult(false, "That calculation is too long.")
        }
        return try {
            val value = MathParser(normalized).parse()
            ToolResult(
                true,
                "The exact result of $normalized is " +
                    formatMathResult(value) + "."
            )
        } catch (e: MathParseException) {
            ToolResult(false, "Calculation failed: " + e.message)
        } catch (t: Throwable) {
            ToolResult(false, "Calculation failed.")
        }
    }

    /** Bangla/Devanagari ank aur chinh seedhe roop me lao. */
    private fun normalizeMathText(raw: String): String {
        val out = StringBuilder()
        for (ch in raw) {
            when (ch) {
                in '\u09E6'..'\u09EF' ->
                    out.append('0' + (ch - '\u09E6'))
                in '\u0966'..'\u096F' ->
                    out.append('0' + (ch - '\u0966'))
                '×' -> out.append('*')
                '÷' -> out.append('/')
                '−' -> out.append('-')
                ',' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString().trim()
    }

    /** Hisab ka natija bolne-layak saaf roop me. */
    private fun formatMathResult(value: java.math.BigDecimal): String {
        return try {
            value.stripTrailingZeros().toPlainString()
        } catch (t: Throwable) {
            value.toPlainString()
        }
    }

    /** Parser ki apni galti — sandesh ke saath. */
    private class MathParseException(message: String) : Exception(message)

    /**
     * Chhota surakshit hisaab-parser: + - * / % ^ aur
     * koshthak (parentheses). Koi eval nahi, koi code
     * nahi — sirf ank aur chinh.
     */
    private class MathParser(private val text: String) {
        private var pos = 0
        private val mathContext = java.math.MathContext(
            12, java.math.RoundingMode.HALF_UP
        )

        fun parse(): java.math.BigDecimal {
            val value = parseExpression()
            skipSpaces()
            if (pos != text.length) {
                throw MathParseException("samajh na aane wala hissa reh gaya")
            }
            return value
        }

        private fun parseExpression(): java.math.BigDecimal {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '+' -> {
                        pos++
                        value = value.add(parseTerm())
                    }
                    '-' -> {
                        pos++
                        value = value.subtract(parseTerm())
                    }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): java.math.BigDecimal {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                when (peek()) {
                    '*' -> {
                        pos++
                        value = value.multiply(parseFactor())
                    }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor.compareTo(java.math.BigDecimal.ZERO) == 0) {
                            throw MathParseException(
                                "zero se bhaag nahi ho sakta"
                            )
                        }
                        value = value.divide(divisor, mathContext)
                    }
                    '%' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor.compareTo(java.math.BigDecimal.ZERO) == 0) {
                            throw MathParseException(
                                "zero se bhaag nahi ho sakta"
                            )
                        }
                        value = value.remainder(divisor)
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): java.math.BigDecimal {
            skipSpaces()
            if (peek() == '-') {
                pos++
                return parseFactor().negate()
            }
            if (peek() == '+') {
                pos++
                return parseFactor()
            }
            val base = parsePrimary()
            skipSpaces()
            if (peek() == '^') {
                pos++
                val exponent = parseFactor()
                return power(base, exponent)
            }
            return base
        }

        private fun power(
            base: java.math.BigDecimal,
            exponent: java.math.BigDecimal
        ): java.math.BigDecimal {
            val expInt = try {
                exponent.intValueExact()
            } catch (t: Throwable) {
                throw MathParseException("ghaat (power) poora ank hona chahiye")
            }
            if (expInt < -12 || expInt > 12) {
                throw MathParseException("ghaat (power) bahut badi hai")
            }
            return if (expInt >= 0) {
                base.pow(expInt)
            } else {
                if (base.compareTo(java.math.BigDecimal.ZERO) == 0) {
                    throw MathParseException("zero se bhaag nahi ho sakta")
                }
                java.math.BigDecimal.ONE.divide(
                    base.pow(-expInt), mathContext
                )
            }
        }

        private fun parsePrimary(): java.math.BigDecimal {
            skipSpaces()
            if (peek() == '(') {
                pos++
                val value = parseExpression()
                skipSpaces()
                if (peek() != ')') {
                    throw MathParseException("koshthak band nahi hua")
                }
                pos++
                return value
            }
            val start = pos
            var seenDot = false
            while (pos < text.length) {
                val ch = text[pos]
                if (ch.isDigit()) {
                    pos++
                } else if (ch == '.' && !seenDot) {
                    seenDot = true
                    pos++
                } else {
                    break
                }
            }
            if (start == pos) {
                throw MathParseException("ank nahi mila")
            }
            return try {
                java.math.BigDecimal(text.substring(start, pos))
            } catch (t: Throwable) {
                throw MathParseException("ank samajh nahi aaya")
            }
        }

        private fun peek(): Char {
            return if (pos < text.length) text[pos] else '\u0000'
        }

        private fun skipSpaces() {
            while (pos < text.length && text[pos] == ' ') {
                pos++
            }
        }
    }

    /**
     * v26: YouTube par [query] search karke pehla video
     * chalao — user ki apni misal ("sad song chalao"):
     * mastishk query chunta hai, phone ke haath (results
     * kholna + Accessibility ka ek tap) kaam karte hain,
     * aur poora kaam ek hi hukm me khatm hota hai. Natija
     * teen sachon me se ek: video chal gaya (ok), results
     * khule par tap nahi ho paya (PARTIAL — mastishk
     * "chala diya" jhooth nahi bolega), ya YouTube khula
     * hi nahi (FAILED).
     */
    private fun youtubePlay(query: String): ToolResult {
        if (query.isEmpty()) {
            return ToolResult(false, "No search query was given.")
        }
        val url = "https://www.youtube.com/results?search_query=" +
            URLEncoder.encode(query, "UTF-8")
        openYouTubeResults(url)?.let { return it }
        // Results khulne ki ghoshna ho chuki hai — app ke
        // saamne aane ki ek saans do, phir tap ki koshish.
        try {
            Thread.sleep(YOUTUBE_SETTLE_MS)
        } catch (e: InterruptedException) {
            // Intezaar kata: tap phir bhi aazmaya jayega.
        }
        // Tap ka natija Accessibility service callback se
        // deti hai; agent ka jawab bandha (sync) chahiye,
        // isliye latch wala intezaar — mobile_data ki tarah.
        // Seema service ki apni khoj-seema (10 s) se upar hai.
        val latch = CountDownLatch(1)
        var outcome: JarvisAccessibilityService.TapOutcome? = null
        try {
            JarvisAccessibilityService.tapFirstYouTubeResult(context) { result ->
                outcome = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par tap " +
                    "wali service tak pahunch nahi payi; results " +
                    "screen par hain."
            )
        }
        val completed = try {
            latch.await(YOUTUBE_TAP_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed) {
            return ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par tap " +
                    "ka jawab samay par nahi aaya; results screen " +
                    "par hain."
            )
        }
        return when (outcome) {
            JarvisAccessibilityService.TapOutcome.TAPPED -> ToolResult(
                true,
                "YouTube par '$query' search karke pehla video " +
                    "chala diya."
            )
            JarvisAccessibilityService.TapOutcome.UNVERIFIED -> ToolResult(
                false,
                "PARTIAL — search results khul gaye hain aur pehle " +
                    "video par tap bhi hua, par video asal me khula " +
                    "hai — yeh pakka nahi ho paya; results screen " +
                    "par hain."
            )
            JarvisAccessibilityService.TapOutcome.SERVICE_OFF -> ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par pehle " +
                    "video par tap nahi ho paya (accessibility " +
                    "service band hai); results screen par hain."
            )
            JarvisAccessibilityService.TapOutcome.BUSY -> ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par pehle " +
                    "video par tap nahi ho paya (ek tap-koshish " +
                    "pehle se chal rahi thi); results screen par hain."
            )
            JarvisAccessibilityService.TapOutcome.NOT_FOUND -> ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par pehle " +
                    "video par tap nahi ho paya (results me video " +
                    "nahi mila); results screen par hain."
            )
            null -> ToolResult(
                false,
                "PARTIAL — search results khul gaye hain, par tap " +
                    "ka jawab samay par nahi aaya; results screen " +
                    "par hain."
            )
        }
    }

    /**
     * YouTube search-results wala link kholo: app phone me
     * ho to intent seedha usi par bandha (package laga kar),
     * warna jo browser mile usi par — web_search wala hi
     * bandobosto (saamne seedha startActivity, background
     * me overlay bridge). Khul jaye to null; na khule to
     * FAILED wala sachha natija.
     */
    private fun openYouTubeResults(url: String): ToolResult? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isYouTubeInstalled()) {
                setPackage(YOUTUBE_PACKAGE)
            }
        }
        if (fromBackgroundService) {
            val hasHandler = try {
                intent.resolveActivity(context.packageManager) != null
            } catch (e: Exception) {
                false
            }
            if (!hasHandler) {
                return ToolResult(false, "FAILED — YouTube khul nahi paya.")
            }
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> null
                LaunchOutcome.PERMISSION_NEEDED -> ToolResult(
                    false,
                    "FAILED — YouTube khul nahi paya: 'Display over " +
                        "other apps' permission chahiye."
                )
                LaunchOutcome.LOCKED -> ToolResult(
                    false,
                    "FAILED — phone locked hai, YouTube khul nahi paya."
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    ToolResult(false, "FAILED — YouTube khul nahi paya.")
            }
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            ToolResult(false, "FAILED — YouTube khul nahi paya.")
        }
    }

    /** YouTube app phone me hai kya (uska launch raasta milta hai kya). */
    private fun isYouTubeInstalled(): Boolean = try {
        context.packageManager
            .getLaunchIntentForPackage(YOUTUBE_PACKAGE) != null
    } catch (e: Exception) {
        false
    }

    /** Mobile data — disha di ho to woh, warna toggle. */
    private fun mobileData(state: String): ToolResult {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return ToolResult(
                false,
                "Accessibility service is OFF — the user must open the " +
                    "app and turn on Accessibility first; the data tile " +
                    "cannot be pressed until then."
            )
        }
        return when (state) {
            "on" -> setMobileData(turnOn = true)
            "off" -> setMobileData(turnOn = false)
            "toggle" -> flipMobileData()
            else -> ToolResult(
                false,
                "state must be 'on', 'off' or 'toggle'."
            )
        }
    }

    /**
     * Data on/off — Accessibility service callback deti
     * hai; agent ka jabab bandha (sync) chahiye, isliye
     * yahan latch wala intezaar hai, CommandEngine ki
     * tarah. Seema service ki apni khoj-seema (8 s) se
     * upar rakhi hai taaki natija pehle aa jaye.
     */
    private fun setMobileData(turnOn: Boolean): ToolResult {
        val latch = CountDownLatch(1)
        var outcome: JarvisAccessibilityService.DataResult? = null
        try {
            JarvisAccessibilityService.toggleMobileData(context, turnOn) { result ->
                outcome = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return ToolResult(false, "The accessibility service could not be reached.")
        }
        val completed = try {
            latch.await(DATA_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed) {
            return ToolResult(
                false,
                "No answer from the accessibility service in time."
            )
        }
        return when (outcome) {
            // SUCCESS ka matlab: tile dab gaya YA pehle se
            // waisi hi haalat thi — dono me "ab waisi hai" sach.
            JarvisAccessibilityService.DataResult.SUCCESS -> ToolResult(
                true,
                if (turnOn) "Mobile data is ON now." else "Mobile data is OFF now."
            )
            JarvisAccessibilityService.DataResult.SERVICE_OFF -> ToolResult(
                false,
                "Accessibility service is OFF."
            )
            JarvisAccessibilityService.DataResult.TILE_NOT_FOUND -> ToolResult(
                false,
                "The mobile-data tile was not found in the quick-settings panel."
            )
            JarvisAccessibilityService.DataResult.PANEL_NOT_OPENED -> ToolResult(
                false,
                "The quick-settings panel did not open."
            )
            null -> ToolResult(
                false,
                "No answer from the accessibility service in time."
            )
        }
    }

    /** Bina disha ka toggle — dabane ke baad ki haalat ke saath. */
    private fun flipMobileData(): ToolResult {
        val latch = CountDownLatch(1)
        var outcome: JarvisAccessibilityService.DataOutcome? = null
        try {
            JarvisAccessibilityService.flipMobileData(context) { result ->
                outcome = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return ToolResult(false, "The accessibility service could not be reached.")
        }
        val completed = try {
            latch.await(DATA_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!completed) {
            return ToolResult(
                false,
                "No answer from the accessibility service in time."
            )
        }
        return when (outcome) {
            JarvisAccessibilityService.DataOutcome.TURNED_ON ->
                ToolResult(true, "Mobile data toggled — it is now ON.")
            JarvisAccessibilityService.DataOutcome.TURNED_OFF ->
                ToolResult(true, "Mobile data toggled — it is now OFF.")
            JarvisAccessibilityService.DataOutcome.TOGGLED_UNKNOWN -> ToolResult(
                true,
                "The data tile was pressed (toggled), but the new " +
                    "state could not be read back."
            )
            JarvisAccessibilityService.DataOutcome.SERVICE_OFF -> ToolResult(
                false,
                "Accessibility service is OFF."
            )
            JarvisAccessibilityService.DataOutcome.TILE_NOT_FOUND -> ToolResult(
                false,
                "The mobile-data tile was not found in the quick-settings panel."
            )
            JarvisAccessibilityService.DataOutcome.PANEL_NOT_OPENED -> ToolResult(
                false,
                "The quick-settings panel did not open."
            )
            null -> ToolResult(
                false,
                "No answer from the accessibility service in time."
            )
        }
    }

    /**
     * v28: Ghadi app me [seconds] ka countdown timer
     * lagao — AlarmClock.ACTION_SET_TIMER, koi
     * permission nahi chahiye. Seconds sahi (positive)
     * na hon to imaandaar FAILED; ghadi wala handler
     * na mile to bhi FAILED — "laga diya" sirf asal
     * dispatch ke baad.
     */
    private fun setTimer(seconds: Int): ToolResult {
        if (seconds <= 0) {
            return ToolResult(
                false,
                "FAILED — timer ke liye seconds ek positive " +
                    "number hona chahiye (mila: $seconds)."
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Maya timer")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        dispatchClockIntent(intent)?.let { return it }
        return ToolResult(
            true,
            "Timer: " + formatDuration(seconds) + " ka laga diya."
        )
    }

    /**
     * v28: Ghadi app me alarm lagao — [hour] 0-23 aur
     * [minute] 0-59 (24-ghante wala samay jo mastishk
     * bhejta hai), [label] ho to wahi ghadi par dikhne
     * wala naam, warna "Maya alarm". Range se bahar ke
     * args par imaandaar FAILED — crash kabhi nahi.
     * EXTRA_SKIP_UI true: jahan support hai, bina
     * screen dikhaye lag jata hai.
     */
    private fun setAlarm(hour: Int, minute: Int, label: String): ToolResult {
        if (hour !in 0..23) {
            return ToolResult(
                false,
                "FAILED — alarm ka hour 0-23 ke beech hona " +
                    "chahiye (mila: $hour)."
            )
        }
        if (minute !in 0..59) {
            return ToolResult(
                false,
                "FAILED — alarm ka minute 0-59 ke beech hona " +
                    "chahiye (mila: $minute)."
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(
                AlarmClock.EXTRA_MESSAGE,
                if (label.isEmpty()) "Maya alarm" else label
            )
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        dispatchClockIntent(intent)?.let { return it }
        val text = "Alarm: " + formatAlarmTime(hour, minute) +
            " ka laga diya."
        return ToolResult(
            true,
            if (label.isEmpty()) {
                text
            } else {
                text + " (label: '$label')"
            }
        )
    }

    /**
     * Ghadi wala intent (timer/alarm) asal me bhejo —
     * youtube_play ke kholne wale bandobosto wala hi
     * kram: pehle dekh lo ki use lene wali koi activity
     * hai bhi ya nahi (na ho to imaandaar "clock app
     * nahi mila"), saamne ho to seedha startActivity,
     * background service se ho to overlay bridge
     * (OverlayLauncher) se. Intent asal me ek resolving
     * activity ko dispatch ho jaye to null; warna
     * FAILED wala sachha natija.
     */
    private fun dispatchClockIntent(intent: Intent): ToolResult? {
        val hasHandler = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) {
            return ToolResult(false, "FAILED — clock app nahi mila.")
        }
        if (fromBackgroundService) {
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> null
                LaunchOutcome.PERMISSION_NEEDED -> ToolResult(
                    false,
                    "FAILED — clock wala kaam nahi ho paya: " +
                        "'Display over other apps' permission " +
                        "chahiye."
                )
                LaunchOutcome.LOCKED -> ToolResult(
                    false,
                    "FAILED — phone locked hai, clock wala " +
                        "kaam nahi ho paya."
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    ToolResult(false, "FAILED — clock app nahi mila.")
            }
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            ToolResult(false, "FAILED — clock app nahi mila.")
        }
    }

    /**
     * Ek sampark (vyakti) ki pehchan: contacts me
     * dikhne wala naam aur uske number (type ke saath —
     * mobile ko pasandeeda chunne ke liye). Ek hi
     * vyakti ke kai number ho sakte hain.
     */
    private class ContactPerson(
        val displayName: String,
        val numbers: MutableList<Pair<String, Int>> = mutableListOf()
    ) {
        /**
         * Pasandeeda number: pehla mobile number, woh
         * na ho to pehla number. (numbers khaali kabhi
         * nahi banti — vyakti number ke saath hi banta
         * hai.)
         */
        fun preferredNumber(): String {
            for ((number, type) in numbers) {
                if (type ==
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                ) {
                    return number
                }
            }
            return numbers[0].first
        }
    }

    /**
     * Contacts-khoj ka bandha hua natija — call_person
     * aur whatsapp_message dono auzaar isi ek khoj par
     * chalte hain (ek hi permission, ek hi milan).
     */
    private sealed class ContactLookup {
        /** READ_CONTACTS permission hi nahi hai. */
        object PermissionDenied : ContactLookup()

        /** Permission thi par contacts padhe nahi ja paye. */
        object LoadFailed : ContactLookup()

        /** Naam kisi bhi seedhi par nahi mila. */
        object NotFound : ContactLookup()

        /** Sabse achhi seedhi par ek hi alag vyakti. */
        class One(val person: ContactPerson) : ContactLookup()

        /** Sabse achhi seedhi par kai alag vyakti — user se poochhna hoga. */
        class Many(val matches: List<ContactPerson>) : ContactLookup()
    }

    /**
     * [name] ki saajhi contacts-khoj: permission jaanch,
     * phir saare sampark padhna, phir teen-seedhi wala
     * milan — natija [ContactLookup] me bandha hua.
     * (SecurityException: permission jaanch ke baad bhi
     * system mana kar de — jaise beech me permission
     * hati ho — to crash ki jagah PermissionDenied.)
     */
    private fun findContact(name: String): ContactLookup {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactLookup.PermissionDenied
        }
        val people = try {
            loadContacts()
        } catch (e: SecurityException) {
            return ContactLookup.PermissionDenied
        } catch (t: Throwable) {
            return ContactLookup.LoadFailed
        }
        val matches = matchPeople(people, name)
        return when {
            matches.isEmpty() -> ContactLookup.NotFound
            matches.size == 1 -> ContactLookup.One(matches[0])
            else -> ContactLookup.Many(matches)
        }
    }

    /** Contacts permission na hone par dono auzaaron ka ek-sa imaandaar jawab. */
    private fun contactsPermissionDeniedResult(): ToolResult = ToolResult(
        false,
        "FAILED — contacts ki permission nahi hai; app " +
            "kholkar ek baar permission de do, phir naam " +
            "bolkar call kar sakti hoon."
    )

    /**
     * Kai-umeedwaar wali pehchan-suchi ek saajha roop me:
     * "Naam — number ke aakhiri 4 ank 1234; ..." aur
     * chaar se zyada hon to "(aur N aur bhi hain)".
     */
    private fun candidateListText(matches: List<ContactPerson>): String {
        val candidates = matches.take(4).joinToString("; ") {
            "${it.displayName} — number ke aakhiri 4 " +
                "ank ${lastFourDigits(it.preferredNumber())}"
        }
        val moreNote = if (matches.size > 4) {
            " (aur ${matches.size - 4} aur bhi hain)"
        } else {
            ""
        }
        return candidates + moreNote
    }

    /**
     * v30: [name] ke sampark ki WhatsApp chat kholo,
     * [message] pehle se likha hua — bhejna woh KHUD
     * nahi dabata: send wala button hamesha user khud
     * dabata hai. Khoj call_person wali hi hai
     * ([findContact] — wahi permission, wahi milan,
     * kai log milein to wahi poochhne wala jawab).
     */
    private fun whatsappMessage(name: String, message: String): ToolResult {
        if (name.isEmpty()) {
            return ToolResult(false, "No contact name was given.")
        }
        if (message.isEmpty()) {
            return ToolResult(
                false,
                "FAILED — WhatsApp par bhejne wala sandesh " +
                    "khaali hai; user se poochho kya likhna hai."
            )
        }
        return when (val lookup = findContact(name)) {
            ContactLookup.PermissionDenied ->
                contactsPermissionDeniedResult()
            ContactLookup.LoadFailed -> ToolResult(
                false,
                "FAILED — contacts padhe nahi ja paye."
            )
            ContactLookup.NotFound -> ToolResult(
                false,
                "FAILED — contacts me '$name' naam ka koi " +
                    "number nahi mila."
            )
            is ContactLookup.One ->
                openWhatsAppChat(lookup.person, message)
            is ContactLookup.Many -> ToolResult(
                false,
                "'$name' naam ke kai log mile — kaun sa " +
                    "wala? ${candidateListText(lookup.matches)}. " +
                    "User se poochho kaun sa, phir usi poore " +
                    "naam se whatsapp_message dobara chalao; " +
                    "abhi WhatsApp NAHI khula hai."
            )
        }
    }

    /**
     * Mile hue sampark ki WhatsApp chat wa.me wale
     * link se kholo, sandesh query me pehle se bhara
     * hua. App phone me ho to intent seedha usi par
     * bandha — chat app me hi khulti hai; app na ho
     * (ya uska bandha raasta resolve na ho) to bina
     * bandha link khulta hai — natija tab PARTIAL
     * hota hai, kyunki chat app wale raaste se NAHI
     * khuli. Kuch bhi resolve na ho to imaandaar
     * FAILED — "WhatsApp is phone me nahi mila".
     */
    private fun openWhatsAppChat(
        person: ContactPerson,
        message: String
    ): ToolResult {
        val url = "https://wa.me/" + waMeNumber(person.preferredNumber()) +
            "?text=" + URLEncoder.encode(message, "UTF-8")
        val snippet = message.take(40) +
            if (message.length > 40) "…" else ""
        val extraNote = if (person.numbers.size > 1) {
            " Unke kul ${person.numbers.size} number hain; " +
                "mobile wala number chuna hai."
        } else {
            ""
        }
        if (isWhatsAppInstalled()) {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(WHATSAPP_PACKAGE)
            }
            if (resolves(appIntent)) {
                dispatchWhatsAppIntent(appIntent)?.let { return it }
                return ToolResult(
                    true,
                    "WhatsApp chat khol diya — " +
                        "${person.displayName} ke liye sandesh " +
                        "likh diya hai ('$snippet'); bhejne ke " +
                        "liye send wala button dabana hai." +
                        extraNote
                )
            }
            // App hai par bandha raasta resolve nahi
            // hua — neeche bina-bandha raasta aazmaya
            // jayega (natija PARTIAL hi rahega).
        }
        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!resolves(genericIntent)) {
            return ToolResult(
                false,
                "FAILED — WhatsApp is phone me nahi mila."
            )
        }
        dispatchWhatsAppIntent(genericIntent)?.let { return it }
        return ToolResult(
            false,
            "PARTIAL — WhatsApp chat app wale raaste se " +
                "nahi khula; wa.me wala page khul gaya " +
                "hai — ${person.displayName} ke liye " +
                "sandesh ('$snippet') wahan taiyaar hai; " +
                "bhejna wahan se hoga." + extraNote
        )
    }

    /**
     * WhatsApp wala intent asal me bhejo — handler ki
     * jaanch bulane wala pehle kar chuka hai; saamne ho
     * to seedha startActivity, background service se
     * ho to overlay bridge (doosre kholne wale
     * auzaaron wala hi bandobosto). Chal jaye to null;
     * warna FAILED wala sachha natija.
     */
    private fun dispatchWhatsAppIntent(intent: Intent): ToolResult? {
        if (fromBackgroundService) {
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> null
                LaunchOutcome.PERMISSION_NEEDED -> ToolResult(
                    false,
                    "FAILED — WhatsApp chat nahi khul paya: " +
                        "'Display over other apps' permission " +
                        "chahiye."
                )
                LaunchOutcome.LOCKED -> ToolResult(
                    false,
                    "FAILED — phone locked hai, WhatsApp chat " +
                        "nahi khul paya."
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    ToolResult(
                        false,
                        "FAILED — WhatsApp chat nahi khul paya."
                    )
            }
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            ToolResult(false, "FAILED — WhatsApp chat nahi khul paya.")
        }
    }

    /** Intent ko lene wali koi activity hai bhi ya nahi. */
    private fun resolves(intent: Intent): Boolean = try {
        intent.resolveActivity(context.packageManager) != null
    } catch (e: Exception) {
        false
    }

    /** WhatsApp app phone me hai kya (uska launch raasta milta hai kya). */
    private fun isWhatsAppInstalled(): Boolean = try {
        context.packageManager
            .getLaunchIntentForPackage(WHATSAPP_PACKAGE) != null
    } catch (e: Exception) {
        false
    }

    /**
     * wa.me ke liye number ko desh-code (country code)
     * wale ankon me saaf karo: '+' hatao; 12 ank '91'
     * se shuru hon to waise hi; 11 ank '0' se shuru
     * hon to '0' hatakar aage '91'; 10 ank hon to
     * aage '91' (Bharat wala number); warna jo ank
     * hain waise hi (pehle se antarrashtriya roop).
     */
    private fun waMeNumber(number: String): String {
        val raw = number.trim()
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.startsWith("+") -> digits
            digits.length == 12 && digits.startsWith("91") -> digits
            digits.length == 11 && digits.startsWith("0") ->
                "91" + digits.substring(1)
            digits.length == 10 -> "91" + digits
            else -> digits
        }
    }

    /**
     * v31: Ek baat hamesha ke liye yaad bandho
     * ([MayaMemory] — sirf phone par). Baat bilkul
     * waisi hi pehle se bandhi ho to dobara NAHI
     * jodti, natija usi ka zikr karta hai. Yaad ki
     * seema ([MayaMemory.MAX_FACTS]) poori ho to
     * sabse purani baat hat-ti hai — natija woh bhi
     * saaf kehta hai.
     */
    private fun rememberFact(fact: String): ToolResult {
        val clean = MayaMemory.cleanFact(fact)
        if (clean.isEmpty()) {
            return ToolResult(
                false,
                "FAILED — yaad rakhne wali baat khaali " +
                    "hai; user se poochho kya yaad rakhna hai."
            )
        }
        val facts = MayaMemory.readFacts(context).toMutableList()
        if (facts.any { it.equals(clean, ignoreCase = true) }) {
            return ToolResult(
                true,
                "Ye baat pehle se yaad hai: '$clean' " +
                    "(kul ${facts.size} baatein yaad hain)."
            )
        }
        val droppedOldest = facts.size >= MayaMemory.MAX_FACTS
        facts.add(clean)
        MayaMemory.writeFacts(context, facts)
        val total = minOf(facts.size, MayaMemory.MAX_FACTS)
        val dropNote = if (droppedOldest) {
            " Yaad ki seema (${MayaMemory.MAX_FACTS}) poori " +
                "ho gayi thi, isliye sabse purani baat hata " +
                "di gayi hai."
        } else {
            ""
        }
        return ToolResult(
            true,
            "Yaad rakh liya: '$clean' " +
                "(kul $total baatein yaad hain)." + dropNote
        )
    }

    /**
     * v31: Bandhi hui saari baatein ginkar lautao —
     * mastishk unhe user ko ginakar sunata hai. Yaad
     * khaali ho to natija wahi saaf kehta hai.
     */
    private fun recallFacts(): ToolResult {
        val facts = MayaMemory.readFacts(context)
        if (facts.isEmpty()) {
            return ToolResult(
                true,
                "abhi tak koi baat yaad nahi rakhi gayi hai."
            )
        }
        val list = facts.mapIndexed { index, fact ->
            "${index + 1}) $fact"
        }.joinToString("\n")
        return ToolResult(
            true,
            "Yaad rakhi hui baatein (kul ${facts.size}):\n$list"
        )
    }

    /**
     * v31: [match] shabd/ansh se milti-julti HAR
     * bandhi baat yaad se hatao (bade-chhote aksharon
     * ka bhed nahi). Natija kehta hai kitni hatayi
     * gayin; ek bhi na mile to imaandaar "nahi mili"
     * — kuch nahi hataya jata. Match khaali ho to
     * kuch NAHI hat-ta (galti se poori yaad saaf
     * hone se bachav).
     */
    private fun forgetFact(match: String): ToolResult {
        val needle = match.trim()
        if (needle.isEmpty()) {
            return ToolResult(
                false,
                "FAILED — bhoolne ke liye koi shabd ya " +
                    "ansh nahi diya gaya; user se poochho " +
                    "kaun si baat bhoolni hai."
            )
        }
        val facts = MayaMemory.readFacts(context)
        val kept = facts.filter {
            !it.contains(needle, ignoreCase = true)
        }
        val removed = facts.size - kept.size
        if (removed == 0) {
            return ToolResult(
                true,
                "'$needle' se milti koi baat yaad me " +
                    "nahi mili — kuch nahi hataya gaya."
            )
        }
        MayaMemory.writeFacts(context, kept)
        return ToolResult(
            true,
            "'$needle' se milti $removed baat(en) bhool " +
                "di gayi (ab kul ${kept.size} baatein " +
                "yaad hain)."
        )
    }

    /**
     * v32: [city] ka asal mausam Open-Meteo se lao —
     * do kadam: (1) geocoding se jagah ka naam,
     * akshansh/rekhansh aur rajya/desh; (2) wahan ke
     * forecast se chune hue din ([whenArg] "tomorrow"
     * ho to kal, warna aaj) ka sabse zyada/kam
     * taapmaan, barish ki sambhavna aur WMO code ka
     * chhota varnan. Open-Meteo muft hai, koi API
     * key nahi chahiye. Har nakami (net band, samay
     * khatm, HTTP gadbad, kharab JSON, ajnabi jagah)
     * imaandaar FAILED hai — sankhya kabhi banti
     * nahi. Barish ki sambhavna forecast me na aaye
     * (kuch jagahon par null hoti hai) to woh hissa
     * chhod diya jata hai — jhoothi pratishata nahi
     * likhti.
     */
    private fun getWeather(city: String, whenArg: String): ToolResult {
        if (city.isEmpty()) {
            return ToolResult(
                false,
                "FAILED — mausam ke liye shahar/jagah ka " +
                    "naam nahi diya gaya; user se poochho " +
                    "kis jagah ka mausam chahiye."
            )
        }
        val failText = "FAILED — mausam ki jaankari nahi " +
            "mil payi (net ya server se jawab nahi aaya)."
        val tomorrow = whenArg == "tomorrow"
        val dayWord = if (tomorrow) "kal" else "aaj"
        val dayIndex = if (tomorrow) 1 else 0
        // Kadam 1: jagah dhundho (geocoding).
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=" + URLEncoder.encode(city, "UTF-8") +
            "&count=1&language=en&format=json"
        val geo = fetchJsonObject(geoUrl)
            ?: return ToolResult(false, failText)
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) {
            return ToolResult(
                false,
                "FAILED — jagah '$city' nahi mili; naam " +
                    "dobara check karke bhejo."
            )
        }
        val place = results.optJSONObject(0)
            ?: return ToolResult(false, failText)
        val latitude = place.optDouble("latitude", Double.NaN)
        val longitude = place.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) {
            return ToolResult(false, failText)
        }
        val placeText = listOf(
            place.optString("name"),
            place.optString("admin1"),
            place.optString("country")
        ).filter { it.isNotEmpty() }.distinct()
            .joinToString(", ")
            .ifEmpty { city }
        // Kadam 2: us jagah ka forecast lao.
        val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&daily=temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,weathercode" +
            "&timezone=auto&forecast_days=3"
        val forecast = fetchJsonObject(forecastUrl)
            ?: return ToolResult(false, failText)
        val daily = forecast.optJSONObject("daily")
            ?: return ToolResult(false, failText)
        val maxArr = daily.optJSONArray("temperature_2m_max")
        val minArr = daily.optJSONArray("temperature_2m_min")
        if (maxArr == null || minArr == null ||
            maxArr.length() <= dayIndex || minArr.length() <= dayIndex
        ) {
            return ToolResult(false, failText)
        }
        val tempMax = maxArr.optDouble(dayIndex, Double.NaN)
        val tempMin = minArr.optDouble(dayIndex, Double.NaN)
        if (tempMax.isNaN() || tempMin.isNaN()) {
            return ToolResult(false, failText)
        }
        val phrase = weatherCodePhrase(
            daily.optJSONArray("weathercode")
                ?.optInt(dayIndex, -1) ?: -1
        )
        val rainPart = daily.optJSONArray("precipitation_probability_max")
            ?.optDouble(dayIndex, Double.NaN)
            ?.takeIf { !it.isNaN() }
            ?.let { ", barish ki sambhavna " + Math.round(it) + "%" }
            ?: ""
        return ToolResult(
            true,
            "Weather — " + placeText + ": " + dayWord +
                " sabse zyada " + Math.round(tempMax) + "°, " +
                "sabse kam " + Math.round(tempMin) + "°" +
                rainPart + " (" + phrase + ")."
        )
    }

    /**
     * v32: Ek URL se JSON jawab lao (GET) —
     * GeminiBrain wali hi HttpURLConnection paddhati
     * (bandha connect/read samay, safalta par hi body
     * padhi jati hai). Koi bhi gadbad null — bulane
     * wala use imaandaar FAILED me badalta hai. Koi
     * key ya khaas header nahi bheja jata: Open-Meteo
     * ko chahiye hi nahi.
     */
    private fun fetchJsonObject(urlText: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = WEATHER_CONNECT_TIMEOUT_MS
                    readTimeout = WEATHER_READ_TIMEOUT_MS
                }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = connection.inputStream
                    .bufferedReader().use { it.readText() }
                JSONObject(body)
            }
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * v32: WMO mausam-code ka chhota sa bolne-layak
     * varnan — Open-Meteo ke daily weathercode ke
     * liye bandhi hui saarani, koi library nahi.
     * Ajnabi code par bhi bana-banaaya jhooth nahi —
     * mila-jula wala imaandaar varnan.
     */
    private fun weatherCodePhrase(code: Int): String = when (code) {
        0 -> "saaf aasmaan"
        1 -> "zyadatar saaf"
        2 -> "aanshik badal"
        3 -> "badal chhaye hue"
        45, 48 -> "dhundh chhayi hui"
        51, 53, 55 -> "halki boonda-baandi ho sakti hai"
        56, 57 -> "jamne wali boonda-baandi ho sakti hai"
        61 -> "halki barish ho sakti hai"
        63 -> "barish ho sakti hai"
        65 -> "tez barish ho sakti hai"
        66, 67 -> "jamne wali barish ho sakti hai"
        71, 73, 75, 77 -> "barf gir sakti hai"
        80 -> "halki barish ki bauchhar ho sakti hai"
        81 -> "barish ki bauchhar ho sakti hai"
        82 -> "tez barish ki bauchhar ho sakti hai"
        85, 86 -> "barf ki bauchhar ho sakti hai"
        95 -> "garaj ke saath toofan aa sakta hai"
        96, 99 -> "ole ke saath toofan aa sakta hai"
        else -> "mausam mila-jula reh sakta hai"
    }

    /**
     * v29: [name] ko contacts me dhundho aur mile to
     * uske number ke saath dialer kholo (ACTION_DIAL
     * — call KHUD nahi lagta, button user dabata hai).
     *
     * Milan teen seedhiyon par: poora naam ekdum waisa
     * (exact), phir naam se shuru hone wala, phir naam
     * jisme mila hua (contains) — bade-chhote akshar
     * aur khaali jagah saaf karke, jagah hatakar bhi
     * taala jata hai. Sabse achhi seedhi par EK hi
     * alag vyakti mile to dial; kai alag vyakti milein
     * to dial NAHI — ummeedwaar (naam + number ke
     * aakhiri 4 ank) lautaye jate hain taaki mastishk
     * user se poochh sake kaun sa wala.
     */
    private fun callPerson(name: String): ToolResult {
        if (name.isEmpty()) {
            return ToolResult(false, "No contact name was given.")
        }
        return when (val lookup = findContact(name)) {
            ContactLookup.PermissionDenied ->
                contactsPermissionDeniedResult()
            ContactLookup.LoadFailed -> ToolResult(
                false,
                "FAILED — contacts padhe nahi ja paye."
            )
            ContactLookup.NotFound -> ToolResult(
                false,
                "FAILED — contacts me '$name' naam ka koi " +
                    "number nahi mila."
            )
            is ContactLookup.One -> {
                val person = lookup.person
                val displayNumber = person.preferredNumber()
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    data = Uri.parse(
                        "tel:" + cleanDialNumber(displayNumber)
                    )
                }
                dispatchDialIntent(intent)?.let { return it }
                val extraNote = if (person.numbers.size > 1) {
                    " Unke kul ${person.numbers.size} number " +
                        "hain; mobile wala number chuna hai."
                } else {
                    ""
                }
                ToolResult(
                    true,
                    "Dialer khol diya — ${person.displayName} " +
                        "($displayNumber) par call karne ke liye " +
                        "taiyaar hai; call wala button dabana " +
                        "hai." + extraNote
                )
            }
            is ContactLookup.Many -> ToolResult(
                false,
                "'$name' naam ke kai log mile — kaun sa " +
                    "wala? ${candidateListText(lookup.matches)}. " +
                    "User se poochho kaun sa, phir usi poore " +
                    "naam se call_person dobara chalao; abhi " +
                    "dialer NAHI khula hai."
            )
        }
    }

    /**
     * Saare phone-number wale sampark padho — ek
     * vyakti (dikhaai naam, saaf-karke pehchan) ke
     * neeche uske saare number jama karke. Cursor
     * hamesha band hota hai; padhne ki koi gadbad
     * bulane wale par jaati hai (jo use imaandaar
     * FAILED me badal deta hai).
     */
    private fun loadContacts(): List<ContactPerson> {
        val byKey = LinkedHashMap<String, ContactPerson>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        ) ?: return emptyList()
        cursor.use {
            val nameIdx = it.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val numberIdx = it.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val typeIdx = it.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            while (it.moveToNext()) {
                val displayName = it.getString(nameIdx)
                    ?.trim()
                    ?.takeIf { n -> n.isNotEmpty() } ?: continue
                val number = it.getString(numberIdx)
                    ?.trim()
                    ?.takeIf { n -> n.isNotEmpty() } ?: continue
                val type = it.getInt(typeIdx)
                val key = normalizeName(displayName)
                val person = byKey.getOrPut(key) {
                    ContactPerson(displayName)
                }
                if (person.numbers.none { p -> p.first == number }) {
                    person.numbers.add(number to type)
                }
            }
        }
        return byKey.values.toList()
    }

    /**
     * Naam-milan ki teen seedhiyan — sabse achhi
     * seedhi par mile hue alag-alag vyakti (contacts
     * wale kram me). Har seedhi saaf-naam par aur
     * jagah-hate-naam par, dono par aazmayi jati hai.
     */
    private fun matchPeople(
        people: List<ContactPerson>,
        query: String
    ): List<ContactPerson> {
        val queryNorm = normalizeName(query)
        val querySquash = squashName(queryNorm)
        if (queryNorm.isEmpty()) return emptyList()
        val tiers: List<(String, String) -> Boolean> = listOf(
            { norm, squash ->
                norm == queryNorm || squash == querySquash
            },
            { norm, squash ->
                norm.startsWith(queryNorm) ||
                    squash.startsWith(querySquash)
            },
            { norm, squash ->
                norm.contains(queryNorm) ||
                    squash.contains(querySquash)
            }
        )
        for (tier in tiers) {
            val matched = people.filter { person ->
                val norm = normalizeName(person.displayName)
                tier(norm, squashName(norm))
            }
            if (matched.isNotEmpty()) return matched
        }
        return emptyList()
    }

    /** Naam saaf karo: chhote akshar, khaali jagah ek-si, kinare kaate. */
    private fun normalizeName(raw: String): String =
        raw.lowercase(Locale.ROOT).trim().replace(WHITESPACE_RUN, " ")

    /** Jagah-hataya naam — "Rahul Sharma" aur "RahulSharma" ek pehchan. */
    private fun squashName(normalized: String): String =
        normalized.replace(" ", "")

    /** tel: URI ke liye saaf number — '+' aur ank rehne do, baaki hatao. */
    private fun cleanDialNumber(number: String): String =
        number.replace(DIAL_STRIP_CHARS, "")

    /** Ummeedwaar pehchan ke liye number ke aakhiri 4 ank (sirf ank). */
    private fun lastFourDigits(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 4) digits.takeLast(4) else digits
    }

    /**
     * Dialer wala intent asal me bhejo — ghadi wale
     * intent wala hi bandobosto (dispatchClockIntent
     * ka aaina): pehle koi lene wali activity hai bhi
     * ya nahi, saamne ho to seedha startActivity,
     * background service se ho to overlay bridge.
     * Dispatch ho jaye to null; warna FAILED wala
     * sachha natija (dialer wale shabdome).
     */
    private fun dispatchDialIntent(intent: Intent): ToolResult? {
        val hasHandler = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) {
            return ToolResult(false, "FAILED — dialer app nahi mila.")
        }
        if (fromBackgroundService) {
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> null
                LaunchOutcome.PERMISSION_NEEDED -> ToolResult(
                    false,
                    "FAILED — dialer nahi khul paya: 'Display " +
                        "over other apps' permission chahiye."
                )
                LaunchOutcome.LOCKED -> ToolResult(
                    false,
                    "FAILED — phone locked hai, dialer nahi " +
                        "khul paya."
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED ->
                    ToolResult(false, "FAILED — dialer nahi khul paya.")
            }
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            ToolResult(false, "FAILED — dialer nahi khul paya.")
        }
    }

    /** Timer ki muddat bolne-layak shabdon me: "10 minute", "1 ghanta 30 minute". */
    private fun formatDuration(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (hours > 0) {
            parts.add(if (hours == 1) "1 ghanta" else "$hours ghante")
        }
        if (minutes > 0) parts.add("$minutes minute")
        if (seconds > 0) parts.add("$seconds second")
        return parts.joinToString(" ")
    }

    /** Alarm ka samay bolne-layak roop me: "subah 7:00 baje", "raat 10:30 baje". */
    private fun formatAlarmTime(hour: Int, minute: Int): String {
        val partOfDay = when (hour) {
            in 4..11 -> "subah"
            in 12..16 -> "dupahar"
            in 17..20 -> "shaam"
            else -> "raat"
        }
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val minuteText = minute.toString().padStart(2, '0')
        return "$partOfDay $hour12:$minuteText baje"
    }

    /**
     * v27: Battery ki asal haalat — kitne percent hai,
     * charging ho rahi hai ya nahi (ho rahi ho to AC/USB
     * ka zikr bhi). Koi receiver register nahi karna
     * padta: ACTION_BATTERY_CHANGED wali sticky ghoshna
     * ko registerReceiver(null, ...) se seedha padh lete
     * hain — isiliye koi nayi permission bhi nahi chahiye
     * (BATTERY_STATS bhi nahi). Padha hi na jaye to
     * jhoothi sankhya nahi, imaandaar fail.
     */
    private fun getBattery(): ToolResult {
        val intent = try {
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (t: Throwable) {
            null
        } ?: return ToolResult(false, "Battery ki jaankari nahi mil payi.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return ToolResult(false, "Battery ki jaankari nahi mil payi.")
        }
        val percent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        if (!charging) {
            return ToolResult(
                true,
                "Battery: $percent%, charging nahi ho raha."
            )
        }
        val source = when (
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        ) {
            BatteryManager.BATTERY_PLUGGED_AC -> " (AC se)"
            BatteryManager.BATTERY_PLUGGED_USB -> " (USB se)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> " (wireless se)"
            else -> ""
        }
        return ToolResult(
            true,
            "Battery: $percent%, charging ho raha hai$source."
        )
    }

    /**
     * v27: Is pal ka connection — hai ya nahi, Wi-Fi hai
     * ya mobile data (mobile ho to 4G/5G ka label bhi,
     * jahan bina permission ke seedha mil jaye), aur
     * internet wali kshamta hai ya nahi. Raasta vahi jo
     * app ka isNetworkUp leta hai: ConnectivityManager
     * ka activeNetwork + uski capabilities (permission
     * ACCESS_NETWORK_STATE pehle se manifest me hai).
     * Connection "nahi hai" bhi ek asal padhi hui haalat
     * hai (ok) — padh hi na payein to fail.
     */
    private fun getNetwork(): ToolResult {
        val cm = try {
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        } catch (t: Throwable) {
            null
        } ?: return ToolResult(false, "Network ki jaankari nahi mil payi.")
        val network = try {
            cm.activeNetwork
        } catch (t: Throwable) {
            null
        } ?: return ToolResult(
            true,
            "Network: koi connection nahi hai — " +
                "internet uplabdh nahi hai."
        )
        val caps = try {
            cm.getNetworkCapabilities(network)
        } catch (t: Throwable) {
            null
        } ?: return ToolResult(false, "Network ki jaankari nahi mil payi.")
        val internetText = if (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            "internet uplabdh hai"
        } else {
            "internet uplabdh nahi hai"
        }
        val typeText = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val label = mobileGenerationLabel()
                if (label != null) "mobile data $label" else "mobile data"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                "Ethernet"
            else -> "doosra connection"
        }
        return ToolResult(true, "Network: $typeText ($internetText).")
    }

    /**
     * Mobile data ki peedhi (4G/5G...) — sirf tab jab
     * bina koi permission maange seedha mil jaye.
     * dataNetworkType aam taur par READ_PHONE_STATE
     * maangta hai; woh permission hamare paas nahi hai,
     * isliye SecurityException par chup chaap null
     * (koi label nahi) — jhootha label kabhi nahi.
     */
    private fun mobileGenerationLabel(): String? {
        val type = try {
            val tm = context.getSystemService(
                Context.TELEPHONY_SERVICE
            ) as? TelephonyManager ?: return null
            @Suppress("DEPRECATION")
            tm.dataNetworkType
        } catch (t: Throwable) {
            return null
        }
        return when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> null
        }
    }

    private companion object {
        /**
         * Accessibility ke natije ka adhiktam intezaar —
         * service ki tile-khoj seema 8 s hai, usse upar
         * rakha hai taaki sachha natija pehle aa jaye
         * (CommandEngine wali 4 s wali seema se alag, kyunki
         * agent ka raasta pehle se hi bandha intezaar wala
         * hai aur der ka jawab jhooth se behtar hai).
         */
        const val DATA_WAIT_MS = 12_000L

        /** v26: YouTube app ka package naam. */
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        /** v30: WhatsApp app ka package naam. */
        const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** v26: results khulne ke baad app ke saamne aane ki saans. */
        const val YOUTUBE_SETTLE_MS = 700L

        /**
         * v26: YouTube tap ke natije ka adhiktam intezaar —
         * service ki apni khoj-seema 10 s hai, usse upar
         * rakha hai taaki sachha natija pehle aa jaye.
         */
        const val YOUTUBE_TAP_WAIT_MS = 14_000L

        /** v29: naam-saaf ke liye lagatar khaali jagah ka jhund. */
        val WHITESPACE_RUN = Regex("\\s+")

        /** v29: tel: URI se hatne wale nishan (jagah, dash, koshthak). */
        val DIAL_STRIP_CHARS = Regex("[\\s\\-()]")

        /** v32: Open-Meteo mausam ke liye bandha connect intezaar. */
        const val WEATHER_CONNECT_TIMEOUT_MS = 15_000

        /** v32: Open-Meteo mausam ke liye bandha padhne ka intezaar. */
        const val WEATHER_READ_TIMEOUT_MS = 15_000
    }
}

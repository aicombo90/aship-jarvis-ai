package com.aship.jarvis.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.telephony.TelephonyManager
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.OverlayLauncher
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.commands.CommandEngine
import org.json.JSONObject
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
        "get_battery" -> getBattery()
        "get_network" -> getNetwork()
        else -> ToolResult(
            false,
            "Unknown tool '$tool'. Available tools: open_app, mobile_data, " +
                "torch, web_search, youtube_play, get_time, get_date, " +
                "get_battery, get_network."
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

        /** v26: results khulne ke baad app ke saamne aane ki saans. */
        const val YOUTUBE_SETTLE_MS = 700L

        /**
         * v26: YouTube tap ke natije ka adhiktam intezaar —
         * service ki apni khoj-seema 10 s hai, usse upar
         * rakha hai taaki sachha natija pehle aa jaye.
         */
        const val YOUTUBE_TAP_WAIT_MS = 14_000L
    }
}

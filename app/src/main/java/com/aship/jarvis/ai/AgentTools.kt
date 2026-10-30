package com.aship.jarvis.ai

import android.content.Context
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.actions.LaunchOutcome
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
        else -> ToolResult(
            false,
            "Unknown tool '$tool'. Available tools: open_app, mobile_data, " +
                "torch, web_search, get_time, get_date."
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
    }
}

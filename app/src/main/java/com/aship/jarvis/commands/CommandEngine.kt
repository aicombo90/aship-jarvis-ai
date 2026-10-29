package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.actions.PhoneActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of handling one command: the reply Jarvis shows and speaks. */
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
 */
class CommandEngine(private val context: Context) {

    fun handle(rawText: String): CommandResult {
        val text = normalize(rawText.lowercase(Locale.ROOT).trim())
        if (text.isEmpty()) {
            return CommandResult("Kuch sunai nahi diya, phir se boliye.", false)
        }

        // --- Alarm (checked first: it contains numbers) -------------------
        if (text.contains("alarm")) {
            val (hour, minute) = parseAlarmTime(text)
            val opened = PhoneActions.setAlarm(context, hour, minute)
            return if (opened) {
                if (hour != null) {
                    CommandResult(
                        "Alarm screen khol diya hai, $hour bajkar ${minute ?: 0} minute par. Confirm kar dijiye.",
                        true
                    )
                } else {
                    CommandResult("Alarm screen khol diya hai, time set karke confirm kar dijiye.", true)
                }
            } else {
                CommandResult("Alarm app nahi khul paya.", false)
            }
        }

        // --- Torch / flashlight -------------------------------------------
        if (text.contains("torch") || text.contains("flashlight") || text.contains("flash light")) {
            val turnOff = text.contains("off") || text.contains("band")
            val ok = PhoneActions.setTorch(context, !turnOff)
            return if (ok) {
                CommandResult(if (turnOff) "Torch band kar diya." else "Torch jala diya.", true)
            } else {
                CommandResult("Is phone par torch control nahi ho paya.", false)
            }
        }

        // --- Open apps ------------------------------------------------------
        for ((keyword, target) in appTargets) {
            if (text.contains(keyword)) {
                return openTarget(target)
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

        // --- Fallback ---------------------------------------------------------
        return CommandResult(
            "Ye command abhi offline mode me available nahi hai. " +
                "Online AI Phase 3 me aayega, phir main aise sawalon ke jawab bhi de paunga.",
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
            "ইউটিউব" to "youtube", "যুটব" to "youtube", "য়ুটুব" to "youtube",
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
            "धन्यवाद" to "thank", "ধন্যবাদ" to "thank"
        )
        for ((from, to) in words) {
            t = t.replace(from, to)
        }
        return t
    }

    private fun openTarget(target: AppTarget): CommandResult {
        val opened = when (target.kind) {
            TargetKind.PACKAGE -> PhoneActions.openApp(context, target.packageName!!)
            TargetKind.CAMERA -> PhoneActions.openCamera(context)
            TargetKind.SETTINGS -> PhoneActions.openSettings(context)
        }
        return if (opened) {
            CommandResult("${target.displayName} khol diya hai.", true)
        } else {
            CommandResult("${target.displayName} is phone me nahi mila ya khul nahi paya.", false)
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
}

package com.aship.jarvis.commands

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.aship.jarvis.MainActivity
import com.aship.jarvis.ai.ProactiveRoutines
import com.aship.jarvis.speaker.TtsSpeaker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * v59: awaaz se yaad dilana (reminder) — OFFLINE, bilkul
 * saadah: "আমাকে ১০ মিনিট পরে মনে করিয়ে দিও" / "সন্ধ্যা
 * ৬টায় মনে করিয়ে দিও" / "কাল সকাল ৭টায় মনে করিয়ে দিও"।
 *
 * Yaadein SharedPreferences me rehti hain (koi network
 * nahi) aur [AlarmManager] se bajti hain — bajne par
 * [ReminderReceiver] notification dikhata hai aur awaaz
 * se bol bhi deta hai। Exact alarm mile to exact, warna
 * inexact (Android 12+ par SCHEDULE_EXACT_ALARM na ho
 * to wahi raasta)।
 *
 * Reboot: phone reboot ho to AlarmManager ke alarm mit
 * jate hain — v68 se [BootReceiver] boot hote hi bachi
 * hui (bhavishya wali) yaadein isi godam se padhkar
 * dobara laga deta hai; jinka samay phone band rehte
 * beet gaya, woh hata di jati hain (missed bolkar nahi
 * bajayi jati — boot ke pal me sahi nahi)।
 */
object ReminderCommands {

    /**
     * Ek yaad — id + kaam + kab (millis)। [repeatKind]:
     * "NONE" (ek baar), "DAILY" (har din), "WEEKLY"
     * (hafte me ek din — [repeatDay] = Calendar ka din,
     * SUNDAY=1 .. SATURDAY=7)।
     */
    data class Reminder(
        val id: Long,
        val text: String,
        val atMillis: Long,
        val repeatKind: String = "NONE",
        val repeatDay: Int = 0
    )

    /**
     * v98: kisi shaks ke message par nazar — woh message
     * kare to yaad dilana (ek baar ki nazar; bajte hi
     * hat jati hai)।
     */
    data class PersonWatch(
        val id: Long,
        val name: String,
        val task: String
    )

    const val ACTION_FIRE = "com.aship.jarvis.REMINDER_FIRE"
    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"

    private const val PREFS_NAME = "jarvis_reminders"
    private const val KEY_REMINDERS = "reminders_json"
    private const val MAX_REMINDERS = 20

    /**
     * Reminder wala hukm ho to chalao, warna null —
     * aam raasta bilkul pehle jaisa chalega। [text] =
     * normalize hua dispatch text।
     */
    fun tryHandle(context: Context, text: String): CommandResult? {
        val lang = PlayIntent.requestLanguage(text)
        val lower = text.lowercase(java.util.Locale.ROOT)

        // --- Sab batiil (pehle: isme "মনে করানো" bhi hai) ---
        if ((lower.contains("সব") || lower.contains("sab")) &&
            (lower.contains("বাতিল") || lower.contains("বন্ধ") ||
                lower.contains("cancel")) &&
            (lower.contains("মনে") || lower.contains("reminder"))
        ) {
            return cancelAll(context, lang)
        }
        // --- v98: message wali nazar batiil ---
        if ((lower.contains("মেসেজ") || lower.contains("message") ||
                lower.contains("मैसेज")) &&
            (lower.contains("মনে করানো") || lower.contains("মনে করিয়ে") ||
                lower.contains("reminder")) &&
            (lower.contains("বাতিল") || lower.contains("বন্ধ") ||
                lower.contains("cancel"))
        ) {
            return cancelAllWatches(context, lang)
        }
        // --- v98: number wala batiil ("২ নম্বর রিমাইন্ডার বাতিল করো") ---
        // Bengali ank pehle Latin me (regex \d sirf Latin ank pakadta hai)।
        val numCancel = Regex("""(\d{1,2})\s*(নম্বর|নাম্বার|number|नंबर)""")
            .find(normalizeDigits(text))
        if (numCancel != null &&
            (lower.contains("বাতিল") || lower.contains("বন্ধ") ||
                lower.contains("cancel")) &&
            (lower.contains("মনে") || lower.contains("reminder"))
        ) {
            val idx = numCancel.groupValues[1].toIntOrNull()
            if (idx != null) return cancelByIndex(context, idx, lang)
        }
        // --- Ek batiil (sabse haal wala) ---
        if ((lower.contains("মনে করানো") || lower.contains("reminder")) &&
            (lower.contains("বাতিল") || lower.contains("বন্ধ") ||
                lower.contains("cancel"))
        ) {
            return cancelLatest(context, lang)
        }
        // --- Suchi ---
        if (lower.contains("কী কী মনে করানো") ||
            lower.contains("কি কি মনে করানো") ||
            lower.contains("মনে করানোর তালিকা") ||
            lower.contains("মনে করানোর লিস্ট") ||
            lower.contains("মনে করানো আছে") ||
            lower.contains("কী মনে করাতে হবে") ||
            lower.trim() == "reminders" ||
            lower.trim() == "reminder"
        ) {
            return listReminders(context, lang)
        }
        // --- v98: kisi ke message par nazar ("যখন [name] মেসেজ
        // করবে তখন মনে করিয়ে দিও") — time wala set se pehle,
        // warna "মনে করিয়ে দিও" wala hissa ise nigal jata।
        if (isPersonWatchRequest(text)) {
            return setPersonWatch(context, text, lang)
        }
        // --- Naya set karo ---
        val isSet = lower.contains("মনে করিয়ে") ||
            lower.contains("মনে করাও") ||
            lower.contains("remind") ||
            lower.contains("yaad dila") ||
            lower.contains("याद दिला") ||
            lower.contains("মনে করিয়ে দেবে")
        if (!isSet) return null
        return setReminder(context, text, lang)
    }

    // ------------------------------------------------------------------
    // Set karna
    // ------------------------------------------------------------------

    private fun setReminder(
        context: Context,
        text: String,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        // v98: samay + repeat (har din / har hafte) ek saath।
        val schedule = parseSchedule(text)
        val atMillis = schedule?.atMillis
        if (atMillis == null) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কখন মনে করিয়ে দেবো? সময়টা বলো — যেমন '১০ মিনিট পরে' " +
                            "বা 'সন্ধ্যা ৬টায়'।"
                    PlayIntent.SpokenLang.HINDI ->
                        "कब याद दिलाऊँ? समय बताओ — जैसे '10 मिनट बाद' या 'शाम 6 बजे'।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "When should I remind you? Tell me the time — like " +
                            "'in 10 minutes' or 'at 6 pm'."
                },
                true
            )
        }
        if (atMillis <= System.currentTimeMillis() + 5_000L) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "ওই সময়টা তো পেরিয়ে গেছে — আগের সময় বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "वह समय तो बीत गया — आगे का समय बताओ।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "That time has already passed — tell me a future time."
                },
                true
            )
        }
        val task = extractTask(text)
        if (task.isEmpty()) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কী মনে করিয়ে দেবো? কাজটা বলো — যেমন 'ওষুধ খেতে মনে করিয়ে দিও'।"
                    PlayIntent.SpokenLang.HINDI ->
                        "क्या याद दिलाऊँ? काम बताओ — जैसे 'दवाई खाने की याद दिला दो'।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "What should I remind you about? Tell me the task."
                },
                true
            )
        }
        val all = loadAll(context).toMutableList()
        if (all.size >= MAX_REMINDERS) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "অনেকগুলো মনে করানো জমে গেছে — পুরনোগুলো বাতিল করে আবার বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "बहुत सारे reminder जमा हो गए हैं — पुराने हटाकर फिर बोलो।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Too many reminders are already set — cancel some old ones first."
                },
                true
            )
        }
        val reminder = Reminder(
            System.currentTimeMillis(), task, atMillis,
            schedule?.repeatKind ?: "NONE",
            schedule?.repeatDay ?: 0
        )
        all.add(reminder)
        saveAll(context, all)
        if (!schedule(context, reminder)) {
            // Save ho gaya par alarm nahi laga — jhootha "ho
            // gaya" nahi bolenge; entry hatakar sach bologe।
            removeById(context, reminder.id)
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "মনে করানোটা লাগাতে পারলাম না — একবার আবার চেষ্টা করো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "Reminder नहीं लग पाया — एक बार फिर कोशिश करो।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "I couldn't set that reminder — please try once more."
                },
                true
            )
        }
        // v100: PROACTIVE — lagaya hua yaad routine ki
        // tarah bandho (wahi kaam wahi samay 3 baar
        // hote hi Maya khud prastav karegi — arg me
        // kaam + samay, dono bandhte hain)।
        val remindCal = Calendar.getInstance()
        remindCal.timeInMillis = atMillis
        ProactiveRoutines.observe(
            context, ProactiveRoutines.Kind.ALARM,
            task + "|" + remindCal.get(Calendar.HOUR_OF_DAY) + ":" +
                remindCal.get(Calendar.MINUTE).toString().padStart(2, '0'),
            task
        )
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ঠিক আছে, ${whenPhrase(atMillis, lang, reminder.repeatKind, reminder.repeatDay)} মনে করিয়ে দেবো — $task।"
                PlayIntent.SpokenLang.HINDI ->
                    "ठीक है, ${whenPhrase(atMillis, lang, reminder.repeatKind, reminder.repeatDay)} याद दिला दूँगी — $task।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Okay, I'll remind you ${whenPhrase(atMillis, lang, reminder.repeatKind, reminder.repeatDay)} — $task."
            },
            true
        )
    }

    // ------------------------------------------------------------------
    // v98: SMART — samay + repeat (har din / har hafte) + aadha ghanta
    // ------------------------------------------------------------------

    /** Samay + repeat — ek saath (parseTime ki jagah)। Na mile to null। */
    private data class ParsedSchedule(
        val atMillis: Long,
        val repeatKind: String,
        val repeatDay: Int
    )

    /**
     * v98: samay nikaalo — pehle "X minute/ghanta baad" wala
     * apekshik roop, phir ghadi wala roop ("৬টায়", "6:30",
     * "at 6 pm")। Naya: "আধ ঘণ্টা" (= 30 minute), "দেড়
     * ঘণ্টা" (= 90 minute), "প্রতিদিন" (= har din),
     * "প্রতি সোমবার" (= har hafte us din)। Bengali /
     * Devanagari ank pehle Latin ank me badlo — bolkar
     * suna "৯টায়" pehle regex se chhoot jata tha।
     */
    private fun parseSchedule(rawText: String): ParsedSchedule? {
        val text = normalizeDigits(replaceNumberWords(rawText))
        val now = System.currentTimeMillis()

        // --- Aadha / dedh ghanta: "আধ ঘণ্টা পরে" ---
        if (containsAny(
                text,
                "আধ ঘণ্টা", "আধা ঘণ্টা", "আধ ঘন্টা", "আধা ঘন্টা",
                "आधा घंटा", "half an hour", "half hour"
            )
        ) {
            return ParsedSchedule(now + 30 * 60_000L, "NONE", 0)
        }
        if (containsAny(text, "দেড় ঘণ্টা", "দেড় ঘন্টা", "डेढ़ घंटा")) {
            return ParsedSchedule(now + 90 * 60_000L, "NONE", 0)
        }

        // --- Apekshik: "10 minute pore" / "2 ghanta pore" ---
        val rel = Regex("""(\d{1,3})\s*(minute|ghanta|second|ঘণ্টা|ঘন্টা)""")
            .find(text)
        if (rel != null) {
            val amount = rel.groupValues[1].toIntOrNull() ?: return null
            val unit = rel.groupValues[2]
            val delay = when (unit) {
                "minute" -> amount * 60_000L
                "ghanta", "ঘণ্টা", "ঘন্টা" -> amount * 3_600_000L
                "second" -> amount * 1_000L
                else -> return null
            }
            return ParsedSchedule(now + delay, "NONE", 0)
        }

        // --- Ghadi: "6:30" / "৬টায়" / "at 6 pm" ---
        val clock = parseClockTime(text) ?: return null
        var hour = clock.first
        val minute = clock.second

        // Din ka hissa: সকাল = AM, দুপুর/বিকেল/সন্ধ্যা/রাত = PM।
        val lower = text.lowercase(java.util.Locale.ROOT)
        val isPmHint = lower.contains("দুপুর") || lower.contains("বিকেল") ||
            lower.contains("সন্ধ্যা") || lower.contains("রাত") ||
            lower.contains("pm")
        val isAmHint = lower.contains("সকাল") || lower.contains("am")
        if (hour in 1..12) {
            if (isPmHint && hour != 12) hour += 12
            if (isAmHint && hour == 12) hour = 0
        }
        val tomorrow = lower.contains("কাল") || lower.contains("tomorrow") ||
            lower.contains("আগামীকাল")

        // --- Repeat: har din / har hafte us din ---
        val repeat = detectRepeat(lower)
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        when {
            repeat.first == "DAILY" -> {
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                return ParsedSchedule(cal.timeInMillis, "DAILY", 0)
            }
            repeat.first == "WEEKLY" -> {
                val day = repeat.second
                var guard = 0
                while (
                    (cal.get(Calendar.DAY_OF_WEEK) != day ||
                        cal.timeInMillis <= now) && guard < 8
                ) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    guard++
                }
                return ParsedSchedule(cal.timeInMillis, "WEEKLY", day)
            }
            tomorrow -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            cal.timeInMillis <= now -> {
                // Aaj ka woh samay beet gaya — kal wahi samay।
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return ParsedSchedule(cal.timeInMillis, "NONE", 0)
    }

    /** Ghadi wala hissa — ghanta + minute (na mile to null)। */
    private fun parseClockTime(text: String): Pair<Int, Int>? {
        var hour: Int? = null
        var minute = 0
        val colon = Regex("""(\d{1,2})[:.](\d{2})""").find(text)
        if (colon != null) {
            hour = colon.groupValues[1].toIntOrNull()
            minute = colon.groupValues[2].toIntOrNull() ?: 0
        } else {
            val ta = Regex("""(\d{1,2})\s*(টা|ta)""").find(text)
            if (ta != null) {
                hour = ta.groupValues[1].toIntOrNull()
            } else {
                val ampm = Regex("""(\d{1,2})\s*(am|pm)\b""").find(text)
                if (ampm != null) {
                    hour = ampm.groupValues[1].toIntOrNull()
                    if (ampm.groupValues[2] == "pm" && hour != null && hour in 1..11) {
                        hour += 12
                    }
                    if (ampm.groupValues[2] == "am" && hour == 12) hour = 0
                }
            }
        }
        if (hour == null || hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }

    /** Repeat pehchano — "NONE"/"DAILY"/"WEEKLY" + hafte ka din (Calendar)। */
    private fun detectRepeat(lower: String): Pair<String, Int> {
        if (lower.contains("প্রতিদিন") || lower.contains("প্রতি দিন") ||
            lower.contains("every day") || lower.contains("daily") ||
            lower.contains("रोज") || lower.contains("हर दिन")
        ) {
            return "DAILY" to 0
        }
        for ((dayName, day) in WEEKDAY_NAMES) {
            if (lower.contains("প্রতি $dayName") ||
                lower.contains("প্রতিসপ্তাহে $dayName") ||
                lower.contains("every $dayName") ||
                lower.contains("हर $dayName")
            ) {
                return "WEEKLY" to day
            }
        }
        return "NONE" to 0
    }

    /** Bengali / Devanagari ank -> Latin ank (regex se pehle)। */
    private fun normalizeDigits(text: String): String =
        text.map { ch ->
            when (ch) {
                '০', '०' -> '0'; '১', '१' -> '1'; '২', '२' -> '2'
                '৩', '३' -> '3'; '৪', '४' -> '4'; '৫', '५' -> '5'
                '৬', '६' -> '6'; '৭', '७' -> '7'; '৮', '८' -> '8'
                '৯', '९' -> '9'
                else -> ch
            }
        }.joinToString("")

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }

    /** Hafte ke din — Bangla / Hindi / English (poora naam hi; chhota roop galat milan deta hai)। */
    private val WEEKDAY_NAMES = listOf(
        "সোমবার" to Calendar.MONDAY, "সোম" to Calendar.MONDAY,
        "monday" to Calendar.MONDAY,
        "सोमवार" to Calendar.MONDAY,
        "মঙ্গলবার" to Calendar.TUESDAY, "মঙ্গল" to Calendar.TUESDAY,
        "tuesday" to Calendar.TUESDAY,
        "मंगलवार" to Calendar.TUESDAY,
        "বুধবার" to Calendar.WEDNESDAY, "বুধ" to Calendar.WEDNESDAY,
        "wednesday" to Calendar.WEDNESDAY,
        "बुधवार" to Calendar.WEDNESDAY,
        "বৃহস্পতিবার" to Calendar.THURSDAY, "বৃহস্পতি" to Calendar.THURSDAY,
        "thursday" to Calendar.THURSDAY,
        "गुरुवार" to Calendar.THURSDAY, "बृहस्पतिवार" to Calendar.THURSDAY,
        "শুক্রবার" to Calendar.FRIDAY, "শুক্র" to Calendar.FRIDAY,
        "friday" to Calendar.FRIDAY,
        "शुक्रवार" to Calendar.FRIDAY,
        "শনিবার" to Calendar.SATURDAY, "শনি" to Calendar.SATURDAY,
        "saturday" to Calendar.SATURDAY,
        "शनिवार" to Calendar.SATURDAY,
        "রবিবার" to Calendar.SUNDAY, "রবি" to Calendar.SUNDAY,
        "sunday" to Calendar.SUNDAY,
        "रविवार" to Calendar.SUNDAY
    )

    /**
     * Shabd-wale sankhya ko ank me badlo ("দশ মিনিট" ->
     * "10 minute") — token ke hisaab se, taaki "একটু"
     * jaise shabdon ke andar ghuskar na kate।
     */
    private fun replaceNumberWords(rawText: String): String {
        val tokens = rawText.split(Regex("""\s+"""))
        return tokens.joinToString(" ") { NUMBER_WORDS[it] ?: it }
    }

    /**
     * Kaam wala hissa — hukm + samay wale shabd hatakar
     * bacha hua। Kuch na bache to khaali (bulane wala
     * kaam poochhega)। v98: repeat / din wale shabd bhi
     * hatao ("প্রতিদিন", "প্রতি সোমবার", "আধ ঘণ্টা")।
     */
    private fun extractTask(rawText: String): String {
        var t = normalizeDigits(replaceNumberWords(rawText))
        // Samay wale hisse pehle hatao (regex)।
        t = t.replace(Regex("""\d{1,3}\s*(minute|ghanta|second|ঘণ্টা|ঘন্টা)"""), " ")
        t = t.replace(Regex("""\d{1,2}[:.]\d{2}"""), " ")
        t = t.replace(Regex("""\d{1,2}\s*(টা|ta|am|pm)"""), " ")
        t = t.replace(Regex("""আধ\s*ঘণ্টা|আধা\s*ঘণ্টা|আধ\s*ঘন্টা|আধা\s*ঘন্টা"""), " ")
        t = t.replace(Regex("""দেড়\s*ঘণ্টা|দেড়\s*ঘন্টা"""), " ")
        t = t.replace(Regex("""half an hour|half hour"""), " ")
        val tokens = t.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() && it !in TASK_STOP_TOKENS }
        return tokens.joinToString(" ").trim()
    }

    /** "10 minute pore" / "aaj sondhya 6tay" jaisi manav-bhasha। */
    private fun whenPhrase(
        atMillis: Long,
        lang: PlayIntent.SpokenLang,
        repeatKind: String = "NONE",
        repeatDay: Int = 0
    ): String {
        val now = System.currentTimeMillis()
        val diffMin = ((atMillis - now) / 60_000L).coerceAtLeast(1L)
        val cal = Calendar.getInstance()
        cal.timeInMillis = atMillis
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val h12 = when (val h = h24 % 12) { 0 -> 12; else -> h }
        val dayPartBn = when (h24) {
            in 0..3 -> "রাতে"
            in 4..11 -> "সকালে"
            in 12..15 -> "দুপুরে"
            in 16..18 -> "বিকেলে"
            else -> "রাতে"
        }
        val dayBn = if (isToday(atMillis)) "আজ" else "কাল"
        val dayHi = if (isToday(atMillis)) "आज" else "कल"
        val dayEn = if (isToday(atMillis)) "today" else "tomorrow"
        // v98: repeat wala hissa — "প্রতিদিন" / "প্রতি সোমবার"।
        val repeatBn = when (repeatKind) {
            "DAILY" -> "প্রতিদিন "
            "WEEKLY" -> "প্রতি ${weekdayNameBn(repeatDay)} "
            else -> ""
        }
        val repeatHi = when (repeatKind) {
            "DAILY" -> "हर दिन "
            "WEEKLY" -> "हर ${weekdayNameHi(repeatDay)} "
            else -> ""
        }
        val repeatEn = when (repeatKind) {
            "DAILY" -> "every day "
            "WEEKLY" -> "every ${weekdayNameEn(repeatDay)} "
            else -> ""
        }
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> {
                if (repeatKind != "NONE") {
                    if (minute == 0) {
                        "$repeatBn$dayPartBn ${toBnDigits(h12.toString())}টায়"
                    } else {
                        "$repeatBn$dayPartBn ${toBnDigits(h12.toString())}টা " +
                            "${toBnDigits(minute.toString())} মিনিটে"
                    }
                } else if (diffMin < 90) {
                    "${toBnDigits(diffMin.toString())} মিনিট পরে"
                } else if (minute == 0) {
                    "$dayBn $dayPartBn ${toBnDigits(h12.toString())}টায়"
                } else {
                    "$dayBn $dayPartBn ${toBnDigits(h12.toString())}টা " +
                        "${toBnDigits(minute.toString())} মিনিটে"
                }
            }
            PlayIntent.SpokenLang.HINDI -> {
                if (repeatKind != "NONE") {
                    if (minute == 0) {
                        "$repeatHi$h12 बजे"
                    } else {
                        "$repeatHi$h12 बजकर $minute मिनट पर"
                    }
                } else if (diffMin < 90) {
                    "$diffMin मिनट बाद"
                } else if (minute == 0) {
                    "$dayHi $h12 बजे"
                } else {
                    "$dayHi $h12 बजकर $minute मिनट पर"
                }
            }
            PlayIntent.SpokenLang.ENGLISH -> {
                if (repeatKind != "NONE") {
                    if (minute == 0) {
                        "$repeatEn at $h12 ${if (h24 < 12) "am" else "pm"}"
                    } else {
                        "$repeatEn at $h12:${minute.toString().padStart(2, '0')} " +
                            if (h24 < 12) "am" else "pm"
                    }
                } else if (diffMin < 90) {
                    "in $diffMin minutes"
                } else if (minute == 0) {
                    "$dayEn at $h12 ${if (h24 < 12) "am" else "pm"}"
                } else {
                    "$dayEn at $h12:${minute.toString().padStart(2, '0')} " +
                        if (h24 < 12) "am" else "pm"
                }
            }
        }
    }

    private fun weekdayNameBn(day: Int): String = when (day) {
        Calendar.MONDAY -> "সোমবার"
        Calendar.TUESDAY -> "মঙ্গলবার"
        Calendar.WEDNESDAY -> "বুধবার"
        Calendar.THURSDAY -> "বৃহস্পতিবার"
        Calendar.FRIDAY -> "শুক্রবার"
        Calendar.SATURDAY -> "শনিবার"
        Calendar.SUNDAY -> "রবিবার"
        else -> ""
    }

    private fun weekdayNameHi(day: Int): String = when (day) {
        Calendar.MONDAY -> "सोमवार"
        Calendar.TUESDAY -> "मंगलवार"
        Calendar.WEDNESDAY -> "बुधवार"
        Calendar.THURSDAY -> "गुरुवार"
        Calendar.FRIDAY -> "शुक्रवार"
        Calendar.SATURDAY -> "शनिवार"
        Calendar.SUNDAY -> "रविवार"
        else -> ""
    }

    private fun weekdayNameEn(day: Int): String = when (day) {
        Calendar.MONDAY -> "Monday"
        Calendar.TUESDAY -> "Tuesday"
        Calendar.WEDNESDAY -> "Wednesday"
        Calendar.THURSDAY -> "Thursday"
        Calendar.FRIDAY -> "Friday"
        Calendar.SATURDAY -> "Saturday"
        Calendar.SUNDAY -> "Sunday"
        else -> ""
    }

    private fun isToday(atMillis: Long): Boolean {
        val a = Calendar.getInstance()
        a.timeInMillis = atMillis
        val b = Calendar.getInstance()
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun toBnDigits(s: String): String =
        s.map { ch ->
            when (ch) {
                '0' -> '০'; '1' -> '১'; '2' -> '২'; '3' -> '৩'; '4' -> '৪'
                '5' -> '৫'; '6' -> '৬'; '7' -> '৭'; '8' -> '৮'; '9' -> '৯'
                else -> ch
            }
        }.joinToString("")

    // ------------------------------------------------------------------
    // Suchi / batiil
    // ------------------------------------------------------------------

    private fun listReminders(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val pending = loadAll(context)
            .filter { it.atMillis > System.currentTimeMillis() }
            .sortedBy { it.atMillis }
        val watches = loadWatches(context)
        if (pending.isEmpty() && watches.isEmpty()) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কোনো মনে করানো সেট করা নেই।"
                    PlayIntent.SpokenLang.HINDI ->
                        "कोई reminder नहीं लगा है।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "No reminders are set."
                },
                true
            )
        }
        val lines = pending.mapIndexed { i, r ->
            "${toBnDigitsIfBn(i + 1, lang)}. ${r.text} — " +
                "${whenPhrase(r.atMillis, lang, r.repeatKind, r.repeatDay)}"
        }.toMutableList()
        // v98: message wali nazar bhi gin kar sunao।
        for (w in watches) {
            val taskPart = if (w.task.isNotEmpty()) " — ${w.task}" else ""
            lines.add(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "${w.name} মেসেজ করলেই মনে করিয়ে দেবো$taskPart"
                    PlayIntent.SpokenLang.HINDI ->
                        "${w.name} के मैसेज करते ही याद दिला दूँगी$taskPart"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "When ${w.name} messages, I'll remind you$taskPart"
                }
            )
        }
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "মনে করানোর তালিকা: ${lines.joinToString(" ")}"
                PlayIntent.SpokenLang.HINDI -> "Reminder की सूची: ${lines.joinToString(" ")}"
                PlayIntent.SpokenLang.ENGLISH -> "Reminders: ${lines.joinToString(" ")}"
            },
            true
        )
    }

    /** v98: number se batiil — list me dikhaya gaya number (1 se shuru)। */
    private fun cancelByIndex(
        context: Context,
        index: Int,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val pending = loadAll(context)
            .filter { it.atMillis > System.currentTimeMillis() }
            .sortedBy { it.atMillis }
        if (index < 1 || index > pending.size) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "ওই নম্বরে কোনো মনে করানো নেই — তালিকাটা শুনে নম্বর বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "उस नंबर पर कोई reminder नहीं है — सूची सुनकर नंबर बताओ।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "There's no reminder with that number — listen to the list first."
                },
                true
            )
        }
        val target = pending[index - 1]
        cancelScheduled(context, target.id)
        removeById(context, target.id)
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "বাতিল করে দিয়েছি — ${target.text}।"
                PlayIntent.SpokenLang.HINDI ->
                    "रद्द कर दिया — ${target.text}।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Cancelled — ${target.text}."
            },
            true
        )
    }

    /** v98: message wali saari nazar batiil। */
    private fun cancelAllWatches(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val watches = loadWatches(context)
        saveWatches(context, emptyList())
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    if (watches.isEmpty()) "বাতিল করার মতো কোনো মেসেজ মনে করানো নেই।"
                    else "মেসেজের সব মনে করানো (${toBnDigits(watches.size.toString())}টা) বাতিল করে দিয়েছি।"
                PlayIntent.SpokenLang.HINDI ->
                    if (watches.isEmpty()) "रद्द करने लायक कोई मैसेज reminder नहीं है।"
                    else "मैसेज के सारे reminder (${watches.size}) रद्द कर दिए।"
                PlayIntent.SpokenLang.ENGLISH ->
                    if (watches.isEmpty()) "There's no message reminder to cancel."
                    else "Cancelled all ${watches.size} message reminders."
            },
            true
        )
    }

    private fun toBnDigitsIfBn(n: Int, lang: PlayIntent.SpokenLang): String =
        if (lang == PlayIntent.SpokenLang.BENGALI) toBnDigits(n.toString()) else n.toString()

    private fun cancelLatest(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val all = loadAll(context).toMutableList()
        if (all.isEmpty()) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "বাতিল করার মতো কোনো মনে করানো নেই।"
                    PlayIntent.SpokenLang.HINDI ->
                        "रद्द करने लायक कोई reminder नहीं है।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "There's no reminder to cancel."
                },
                true
            )
        }
        val latest = all.maxByOrNull { it.id }!!
        cancelScheduled(context, latest.id)
        removeById(context, latest.id)
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "বাতিল করে দিয়েছি — ${latest.text}।"
                PlayIntent.SpokenLang.HINDI ->
                    "रद्द कर दिया — ${latest.text}।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Cancelled — ${latest.text}."
            },
            true
        )
    }

    private fun cancelAll(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val all = loadAll(context)
        for (r in all) cancelScheduled(context, r.id)
        saveAll(context, emptyList())
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    if (all.isEmpty()) "বাতিল করার মতো কোনো মনে করানো নেই।"
                    else "সব মনে করানো (${toBnDigits(all.size.toString())}টা) বাতিল করে দিয়েছি।"
                PlayIntent.SpokenLang.HINDI ->
                    if (all.isEmpty()) "रद्द करने लायक कोई reminder नहीं है।"
                    else "सारे reminder (${all.size}) रद्द कर दिए।"
                PlayIntent.SpokenLang.ENGLISH ->
                    if (all.isEmpty()) "There's no reminder to cancel."
                    else "Cancelled all ${all.size} reminders."
            },
            true
        )
    }

    // ------------------------------------------------------------------
    // v98: MESSAGE WALI NAZAR — "[name] message kare to yaad dilana"
    // ------------------------------------------------------------------

    /** "যখন [name] মেসেজ করবে তখন মনে করিয়ে দিও" jaisa hukm hai kya। */
    private fun isPersonWatchRequest(text: String): Boolean {
        val lower = text.lowercase(java.util.Locale.ROOT)
        val hasWhen = lower.contains("যখন") || lower.contains("जब") ||
            lower.contains("when")
        val hasMessage = lower.contains("মেসেজ") || lower.contains("মেসেজ করবে") ||
            lower.contains("message") || lower.contains("मैसेज") ||
            lower.contains("मेसेज")
        val hasRemind = lower.contains("মনে করিয়ে") || lower.contains("মনে করাও") ||
            lower.contains("remind") || lower.contains("याद दिला")
        return hasWhen && hasMessage && hasRemind
    }

    /** Nazar lagao — naam + (ho to) kaam nikaalkar godam me rakho। */
    private fun setPersonWatch(
        context: Context,
        text: String,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val name = extractWatchName(text)
        if (name.isEmpty()) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কার মেসেজের অপেক্ষা করবো? নামটা বলো — যেমন 'যখন রাহুল মেসেজ করবে তখন মনে করিয়ে দিও'।"
                    PlayIntent.SpokenLang.HINDI ->
                        "किसके मैसेज का इंतज़ार करूँ? नाम बताओ — जैसे 'जब राहुल मैसेज करे तो याद दिला दो'।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Whose message should I watch for? Tell me the name — like 'when Rahul messages, remind me'."
                },
                true
            )
        }
        // Notification access na ho to nazar kabhi bajegi hi
        // nahi — pehle hi sach bol do, jhootha "ho gaya" nahi।
        if (!NotificationCommands.isAccessGranted(context)) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "নোটিফিকেশন অনুমতি না থাকলে এটা কাজ করবে না — আগে Messages কার্ড থেকে অনুমতি দিন, তারপর আবার বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "Notification की अनुमति के बिना यह काम नहीं करेगा — पहले Messages कार्ड से अनुमति दो, फिर बोलो।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "This won't work without notification access — grant it from the Messages card first, then ask again."
                },
                true
            )
        }
        val task = extractWatchTask(text)
        val watches = loadWatches(context).toMutableList()
        watches.removeAll {
            it.name.lowercase(java.util.Locale.ROOT) ==
                name.lowercase(java.util.Locale.ROOT)
        }
        watches.add(PersonWatch(System.currentTimeMillis(), name, task))
        saveWatches(context, watches)
        // Naam notification me dikhta hai kya — na dikhe to
        // imaandaar ishara (script alag ho sakta hai)।
        val seen = NotificationStore.findBySender(context, name) != null
        val hint = if (!seen) {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    " (নামটা নোটিফিকেশনে যেমন দেখায় তেমনই হতে হবে)"
                PlayIntent.SpokenLang.HINDI ->
                    " (नाम वैसा ही होना चाहिए जैसा notification में दिखता है)"
                PlayIntent.SpokenLang.ENGLISH ->
                    " (the name must match how it appears in notifications)"
            }
        } else ""
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ঠিক আছে, $name মেসেজ করলেই মনে করিয়ে দেবো" +
                        (if (task.isNotEmpty()) " — $task" else "") + "।$hint"
                PlayIntent.SpokenLang.HINDI ->
                    "ठीक है, $name के मैसेज करते ही याद दिला दूँगी" +
                        (if (task.isNotEmpty()) " — $task" else "") + "।$hint"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Okay, I'll remind you as soon as $name messages" +
                        (if (task.isNotEmpty()) " — $task" else "") + ".$hint"
            },
            true
        )
    }

    /** "যখন [name] মেসেজ করবে" me se naam nikaalo। */
    private fun extractWatchName(rawText: String): String {
        val text = normalizeDigits(replaceNumberWords(rawText))
        val patterns = listOf(
            Regex("""যখন\s+(.+?)\s+(মেসেজ|message)"""),
            Regex("""जब\s+(.+?)\s+(मैसेज|मेसेज)"""),
            Regex("""when\s+(.+?)\s+(messages|message)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(text.lowercase(java.util.Locale.ROOT))
            if (match != null) {
                val name = match.groupValues[1]
                    .split(Regex("""\s+"""))
                    .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
                    .filter { it.isNotEmpty() && it !in WATCH_NAME_STOP }
                    .joinToString(" ").trim()
                    // "রাহুলকে" -> "রাহুল" (karak hatao, warna
                    // notification wala naam kabhi nahi milega)।
                    .removeSuffix("কে").removeSuffix("को").trim()
                if (name.isNotEmpty()) return name
            }
        }
        return ""
    }

    /** "তখন [kaam]" wala hissa — na ho to khaali (message hi yaad hai)। */
    private fun extractWatchTask(rawText: String): String {
        val lower = rawText.lowercase(java.util.Locale.ROOT)
        // Pehle "তখন" (sabse aam), phir "तो", phir "then" —
        // sthiti se nahi, kram se (galat shabd ke andar
        // chhupa "तो"/"then" kaam na kaat de)।
        val thenIdx = when {
            lower.contains("তখন") -> lower.indexOf("তখন") + "তখন".length
            lower.contains("तो") -> lower.indexOf("तो") + "तो".length
            lower.contains("then") -> lower.indexOf("then") + "then".length
            else -> return ""
        }
        val after = rawText.substring(thenIdx)
        return extractTask(after)
    }

    private val WATCH_NAME_STOP = setOf(
        "আমাকে", "আমায়", "কে", "কেউ", "মেসেজ", "message", "ম্যাসেজ",
        "যখন", "जब", "when", "করবে", "করলে", "করে", "আসলে", "এলে"
    )

    /**
     * v98: message aaya — kisi nazar se mile to bajao।
     * [JarvisNotificationListener] se bulaya jata hai।
     * Mile to nazar hat jati hai (ek baar ki) aur sach
     * me yaad dilaya jata hai (notification + awaaz)।
     */
    fun onMessageReceived(context: Context, sender: String, text: String) {
        try {
            val needle = sender.lowercase(java.util.Locale.ROOT).trim()
            if (needle.length < 2) return
            val watches = loadWatches(context)
            val hit = watches.firstOrNull { watch ->
                val name = watch.name.lowercase(java.util.Locale.ROOT).trim()
                name.length >= 2 &&
                    (needle.contains(name) || name.contains(needle))
            } ?: return
            saveWatches(context, watches.filter { it.id != hit.id })
            firePersonWatch(context, hit, sender, text)
        } catch (t: Throwable) {
            // Nazar bajna kabhi crash nahi karega।
        }
    }

    /** Nazar baji — notification dikhao + awaaz se bolo। */
    private fun firePersonWatch(
        context: Context,
        watch: PersonWatch,
        sender: String,
        messageText: String
    ) {
        val speakText = if (watch.task.isNotEmpty()) {
            "মনে করিয়ে দিচ্ছি — ${watch.name} মেসেজ করেছে। ${watch.task}"
        } else {
            "${watch.name} মেসেজ করেছে — $messageText"
        }
        try {
            val nm = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    WATCH_CHANNEL_ID, "মেসেজ মনে করানো",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPi = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, WATCH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("মনে করিয়ে দিচ্ছি — ${watch.name}")
                .setContentText(speakText)
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .build()
            nm.notify(
                WATCH_NOTIF_ID_BASE + (System.currentTimeMillis() % 10_000L).toInt(),
                notification
            )
        } catch (t: Throwable) {
            // Notification na dikhe to bhi boli chalegi।
        }
        try {
            val speaker = TtsSpeaker(context.applicationContext)
            speaker.onUtteranceFinished = {
                try {
                    speaker.shutdown()
                } catch (t: Throwable) {
                    // Band karna bonus hai।
                }
            }
            speaker.speak(speakText)
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    try {
                        speaker.shutdown()
                    } catch (t: Throwable) {
                        // Band karna bonus hai।
                    }
                },
                20_000L
            )
        } catch (t: Throwable) {
            // Boli na chale to notification hi yaad hai।
        }
    }

    // ------------------------------------------------------------------
    // Nazar wali godam (SharedPreferences)
    // ------------------------------------------------------------------

    fun loadWatches(context: Context): List<PersonWatch> {
        val out = ArrayList<PersonWatch>()
        try {
            val raw = watchPrefs(context).getString(KEY_WATCHES, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                val name = obj.optString("name", "")
                if (id != 0L && name.isNotEmpty()) {
                    out.add(PersonWatch(id, name, obj.optString("task", "")))
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    fun saveWatches(context: Context, watches: List<PersonWatch>) {
        try {
            val array = JSONArray()
            for (w in watches) {
                val obj = JSONObject()
                obj.put("id", w.id)
                obj.put("name", w.name)
                obj.put("task", w.task)
                array.put(obj)
            }
            watchPrefs(context).edit()
                .putString(KEY_WATCHES, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agli baar phir koshish hogi।
        }
    }

    private fun watchPrefs(context: Context) =
        context.getSharedPreferences(PREFS_WATCHES, Context.MODE_PRIVATE)

    private const val PREFS_WATCHES = "jarvis_person_watches"
    private const val KEY_WATCHES = "watches_json"
    private const val WATCH_CHANNEL_ID = "jarvis_person_watch_channel"
    private const val WATCH_NOTIF_ID_BASE = 59800

    // ------------------------------------------------------------------
    // Alarm lagana / hatana
    // ------------------------------------------------------------------

    /** AlarmManager me lagao — exact mile to exact, warna inexact। */
    fun schedule(context: Context, reminder: Reminder): Boolean {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return false
            val pi = pendingIntent(context, reminder)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, reminder.atMillis, pi
                    )
                } else {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, reminder.atMillis, pi
                    )
                }
            } else {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reminder.atMillis, pi
                )
            }
            true
        } catch (se: SecurityException) {
            // Exact ka haq nahi — inexact se ek aur koshish।
            try {
                val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return false
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, reminder.atMillis,
                    pendingIntent(context, reminder)
                )
                true
            } catch (t: Throwable) {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Alarm + PendingIntent, dono hatayo। */
    fun cancelScheduled(context: Context, id: Long) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            val pi = pendingIntent(
                context, Reminder(id, "", System.currentTimeMillis())
            )
            am.cancel(pi)
            pi.cancel()
        } catch (t: Throwable) {
            // Hatana bonus hai — entry mitna asal hai।
        }
    }

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, reminder.id)
            putExtra(EXTRA_TEXT, reminder.text)
        }
        val requestCode = (reminder.id and 0x7FFFFFFFL).toInt()
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ------------------------------------------------------------------
    // Godam (SharedPreferences)
    // ------------------------------------------------------------------

    fun loadAll(context: Context): List<Reminder> {
        val out = ArrayList<Reminder>()
        try {
            val raw = prefs(context).getString(KEY_REMINDERS, null) ?: return out
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", 0L)
                val text = obj.optString("text", "")
                val at = obj.optLong("at", 0L)
                if (id != 0L && text.isNotEmpty() && at > 0L) {
                    // v98: purani entry me "rep"/"day" nahi hota —
                    // default ek baar wala (NONE)।
                    out.add(
                        Reminder(
                            id, text, at,
                            obj.optString("rep", "NONE"),
                            obj.optInt("day", 0)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    fun saveAll(context: Context, reminders: List<Reminder>) {
        try {
            val array = JSONArray()
            for (r in reminders) {
                val obj = JSONObject()
                obj.put("id", r.id)
                obj.put("text", r.text)
                obj.put("at", r.atMillis)
                obj.put("rep", r.repeatKind)
                obj.put("day", r.repeatDay)
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_REMINDERS, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agli baar phir koshish hogi।
        }
    }

    /** Id se ek yaad dhoondho (receiver / boot ke liye)। */
    fun findById(context: Context, id: Long): Reminder? =
        loadAll(context).firstOrNull { it.id == id }

    /**
     * v98: repeat wali yaad ko aage badhao — ek kadam
     * (DAILY = +1 din, WEEKLY = +7 din)। Ek baar wali
     * jaisi ki taisi rehti hai।
     */
    fun nextOccurrence(reminder: Reminder): Long {
        val step = when (reminder.repeatKind) {
            "DAILY" -> 86_400_000L
            "WEEKLY" -> 7 * 86_400_000L
            else -> return reminder.atMillis
        }
        return reminder.atMillis + step
    }

    /**
     * v98: repeat wali yaad ko bhavishya me le aao —
     * phone band rehte chhooti hui baariyan aage badhao
     * (series na toote)। Ek baar wali / pehle se bhavishya
     * wali jaisi ki taisi।
     */
    fun rollForwardToFuture(
        reminder: Reminder,
        now: Long = System.currentTimeMillis()
    ): Reminder {
        if (reminder.repeatKind == "NONE" || reminder.atMillis > now) {
            return reminder
        }
        var at = reminder.atMillis
        var guard = 0
        while (at <= now && guard < 370) {
            at = nextOccurrence(reminder.copy(atMillis = at))
            guard++
        }
        return reminder.copy(atMillis = at)
    }

    /** Id wali entry hatao — mile to wohi lautao, warna null। */
    fun removeById(context: Context, id: Long): Reminder? {
        val all = loadAll(context).toMutableList()
        val found = all.firstOrNull { it.id == id } ?: return null
        all.removeAll { it.id == id }
        saveAll(context, all)
        return found
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val NUMBER_WORDS = mapOf(
        "এক" to "1", "দুই" to "2", "দু" to "2", "তিন" to "3",
        "চার" to "4", "পাঁচ" to "5", "ছয়" to "6", "সাত" to "7",
        "আট" to "8", "নয়" to "9", "দশ" to "10", "এগারো" to "11",
        "বারো" to "12", "পনেরো" to "15", "পনের" to "15",
        "বিশ" to "20", "ত্রিশ" to "30",
        "एक" to "1", "दो" to "2", "तीन" to "3", "चार" to "4",
        "पाँच" to "5", "पांच" to "5", "छह" to "6", "सात" to "7",
        "आठ" to "8", "नौ" to "9", "दस" to "10", "ग्यारह" to "11",
        "बारह" to "12", "पंद्रह" to "15", "बीस" to "20", "तीस" to "30",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12",
        "fifteen" to "15", "twenty" to "20", "thirty" to "30"
    )

    private val TASK_STOP_TOKENS = setOf(
        "মনে", "করিয়ে", "করাও", "দিও", "দাও", "দেবে", "আমাকে", "আমায়",
        "পরে", "baad", "after", "in", "at", "কাল", "tomorrow", "আগামীকাল",
        "সকাল", "সকালে", "দুপুর", "দুপুরে", "বিকেল", "বিকেলে",
        "সন্ধ্যা", "সন্ধ্যায়", "রাত", "রাতে", "বাজে", "baje",
        "remind", "reminder", "me", "yaad", "dila",
        "याद", "दिला", "mujhe", "please", "টা", "টায়", "ta", "am", "pm",
        "একটু", "তো",
        // v98: repeat / din wale shabd kaam ka hissa nahi।
        "প্রতিদিন", "প্রতি", "প্রতিসপ্তাহে", "every", "daily", "day",
        "সোমবার", "সোম", "মঙ্গলবার", "মঙ্গল", "বুধবার", "বুধ",
        "বৃহস্পতিবার", "বৃহস্পতি", "শুক্রবার", "শুক্র",
        "শনিবার", "শনি", "রবিবার", "রবি",
        "monday", "mon", "tuesday", "tue", "wednesday", "wed",
        "thursday", "thu", "friday", "fri", "saturday", "sat",
        "sunday", "sun",
        "सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "बृहस्पतिवार",
        "शुक्रवार", "शनिवार", "रविवार", "रोज", "हर",
        "আধ", "দেড়", "half", "hour"
    )
}

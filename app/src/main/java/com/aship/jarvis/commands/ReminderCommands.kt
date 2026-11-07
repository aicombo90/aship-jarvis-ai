package com.aship.jarvis.commands

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

    /** Ek yaad — id + kaam + kab (millis)। */
    data class Reminder(val id: Long, val text: String, val atMillis: Long)

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
        val atMillis = parseTime(text)
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
        val reminder = Reminder(System.currentTimeMillis(), task, atMillis)
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
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ঠিক আছে, ${whenPhrase(atMillis, lang)} মনে করিয়ে দেবো — $task।"
                PlayIntent.SpokenLang.HINDI ->
                    "ठीक है, ${whenPhrase(atMillis, lang)} याद दिला दूँगी — $task।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Okay, I'll remind you ${whenPhrase(atMillis, lang)} — $task."
            },
            true
        )
    }

    /**
     * Samay nikaalo — pehle "X minute/ghanta baad" wala
     * apekshik roop, phir ghadi wala roop ("৬টায়",
     * "6:30", "at 6 pm")। Na mile to null।
     */
    private fun parseTime(rawText: String): Long? {
        val text = replaceNumberWords(rawText)
        val now = System.currentTimeMillis()

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
            return now + delay
        }

        // --- Ghadi: "6:30" / "৬টায়" / "at 6 pm" ---
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
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (tomorrow) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        } else if (cal.timeInMillis <= now) {
            // Aaj ka woh samay beet gaya — kal wahi samay।
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

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
     * kaam poochhega)।
     */
    private fun extractTask(rawText: String): String {
        var t = replaceNumberWords(rawText)
        // Samay wale hisse pehle hatao (regex)।
        t = t.replace(Regex("""\d{1,3}\s*(minute|ghanta|second|ঘণ্টা|ঘন্টা)"""), " ")
        t = t.replace(Regex("""\d{1,2}[:.]\d{2}"""), " ")
        t = t.replace(Regex("""\d{1,2}\s*(টা|ta|am|pm)"""), " ")
        val tokens = t.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() && it !in TASK_STOP_TOKENS }
        return tokens.joinToString(" ").trim()
    }

    /** "10 minute pore" / "aaj sondhya 6tay" jaisi manav-bhasha। */
    private fun whenPhrase(atMillis: Long, lang: PlayIntent.SpokenLang): String {
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
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> {
                if (diffMin < 90) {
                    "${toBnDigits(diffMin.toString())} মিনিট পরে"
                } else if (minute == 0) {
                    "$dayBn $dayPartBn ${toBnDigits(h12.toString())}টায়"
                } else {
                    "$dayBn $dayPartBn ${toBnDigits(h12.toString())}টা " +
                        "${toBnDigits(minute.toString())} মিনিটে"
                }
            }
            PlayIntent.SpokenLang.HINDI -> {
                if (diffMin < 90) {
                    "$diffMin मिनट बाद"
                } else if (minute == 0) {
                    "$dayHi $h12 बजे"
                } else {
                    "$dayHi $h12 बजकर $minute मिनट पर"
                }
            }
            PlayIntent.SpokenLang.ENGLISH -> {
                if (diffMin < 90) {
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
        if (pending.isEmpty()) {
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
            "${toBnDigitsIfBn(i + 1, lang)}. ${r.text} — ${whenPhrase(r.atMillis, lang)}"
        }.joinToString(" ")
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI -> "মনে করানোর তালিকা: $lines"
                PlayIntent.SpokenLang.HINDI -> "Reminder की सूची: $lines"
                PlayIntent.SpokenLang.ENGLISH -> "Reminders: $lines"
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
                    out.add(Reminder(id, text, at))
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
                array.put(obj)
            }
            prefs(context).edit().putString(KEY_REMINDERS, array.toString()).apply()
        } catch (t: Throwable) {
            // Na bache to agli baar phir koshish hogi।
        }
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
        "একটু", "তো"
    )
}

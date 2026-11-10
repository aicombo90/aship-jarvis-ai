package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.PhoneActions
import com.aship.jarvis.ai.ProactiveRoutines
import java.util.Calendar
import java.util.Locale

/**
 * v100: PROACTIVE PROPOSAL — Maya khud prastav
 * karti hai, kaam sirf user ke HAAN par hota hai।
 * Do mezbaan (saamne wali screen + background wake
 * service) yahi ek object bulate hain, bilkul
 * [ComboCommands] / [RuleCommands] ki tarah।
 *
 * Teen kaam:
 *  1. ADMIN — "প্রস্তাব বন্ধ করো" / "প্রস্তাব চালু
 *     করো" (saare prastav), "আমার রুটিন কী কী"
 *     (seekhi routinein + ginati), "'X'-এর প্রস্তাব
 *     আর কোরো না" / "আবার প্রস্তাব করো" (ek routine)।
 *     Sirf saaf prastav/routine wale roop pakadta
 *     hai — "প্রস্তাব" / "রুটিন" shabd ke bina kabhi
 *     nahi, isliye aam hukm surakshit hain।
 *  2. JAWAB — latka prastav ho to agli baat uska
 *     HAAN/NA hai, naya hukm nahi ([tryAnswerProposal])।
 *     HAAN par kaam chalta hai (gaana / combo / app /
 *     yaad — sirf ye chaar, kabhi paise / call /
 *     message / setting nahi); NA par prastav 3 din
 *     rukega; kuch aur bole to prastav chup-chaap
 *     hat jayega aur aam hukm chalega।
 *  3. SAWAAL — prastav ka roop ([proposalQuestion]):
 *     "এখন সকাল ৭টা — 'কিশোর কুমারের গান' চালিয়ে
 *     দেবো?" — samay + kaam + sawal, user ki bhasha me।
 *
 * Gaana chalana host ka kaam hai (uske apne
 * runDirectYouTubePlay se — natija wahi bolta hai),
 * isliye HAAN wale gaane par [playSong] lambda
 * chalakar [ProposalAnswer.HandledElsewhere] milta
 * hai; baaki teeno kaam yahin chalte hain aur
 * [ProposalAnswer.Spoken] me jawab milta hai।
 */
object ProactiveCommands {

    /** Latke prastav ke jawab ka natija — mezbaan isi se bolta hai। */
    sealed class ProposalAnswer {
        /** Koi latka prastav nahi, ya baat HAAN/NA nahi — aam raasta। */
        object NotAnAnswer : ProposalAnswer()

        /** Jawab bola jayega (kaam ho gaya / manaahi / inkaar)। */
        class Spoken(val line: String) : ProposalAnswer()

        /** Kaam host ke raaste se chal gaya (gaana) — wohi bolega। */
        object HandledElsewhere : ProposalAnswer()
    }

    // ---------------------------------------------------------------
    // 1. ADMIN — prastav chalu/band, routine suchi
    // ---------------------------------------------------------------

    /**
     * Prastav/routine wala admin hukm ho to bola jaane
     * wala jawab ([lang] me), warna null। Kabhi
     * exception nahi — gadbad par null।
     */
    fun tryHandleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        return try {
            handleAdmin(context, rawText, lang)
        } catch (t: Throwable) {
            null
        }
    }

    private fun handleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang
    ): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)
        if (!mentionsProposal(lower) && !mentionsRoutine(lower)) return null

        // (a) Ek routine ka prastav band karna —
        // "'X'-এর প্রস্তাব আর কোরো না"। Naam ke bina
        // kaunsa, ye poochhna hi imaandaar hai।
        if (mentionsProposal(lower) &&
            DISABLE_ONE_MARKERS.any { lower.contains(it) }
        ) {
            val mentioned = ProactiveRoutines.findMentioned(context, text)
            if (mentioned == null) {
                return when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কোন রুটিনের প্রস্তাব বন্ধ করবো? নামটা বলো — যেমন 'গানটার প্রস্তাব আর কোরো না'।"
                    PlayIntent.SpokenLang.HINDI ->
                        "किस routine के प्रस्ताव बंद करूँ? नाम बताओ — जैसे 'गाने के प्रस्ताव और मत करना'।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Which routine should I stop proposing? Tell me its name."
                }
            }
            ProactiveRoutines.setRoutineEnabled(context, mentioned.key, false)
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ঠিক আছে, '${mentioned.label}'-এর প্রস্তাব আর করবো না।"
                PlayIntent.SpokenLang.HINDI ->
                    "ठीक है, '${mentioned.label}' के प्रस्ताव और नहीं करूँगी।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Okay, I won't propose '${mentioned.label}' anymore."
            }
        }

        // (b) Ek routine ka prastav wapas chalana —
        // "'X'-এর প্রস্তাব আবার করো"।
        if (mentionsProposal(lower) &&
            ENABLE_ONE_MARKERS.any { lower.contains(it) }
        ) {
            val mentioned = ProactiveRoutines.findMentioned(context, text)
            if (mentioned == null) {
                return when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কোন রুটিনের প্রস্তাব আবার করবো? নামটা বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "किस routine के प्रस्ताव फिर करूँ? नाम बताओ।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Which routine should I propose again? Tell me its name."
                }
            }
            ProactiveRoutines.setRoutineEnabled(context, mentioned.key, true)
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ঠিক আছে, '${mentioned.label}'-এর প্রস্তাব আবার করবো।"
                PlayIntent.SpokenLang.HINDI ->
                    "ठीक है, '${mentioned.label}' के प्रस्ताव फिर करूँगी।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Okay, I'll propose '${mentioned.label}' again."
            }
        }

        // (c) Saare prastav chalu karna — "প্রস্তাব চালু করো"।
        if (mentionsProposal(lower) &&
            ENABLE_ALL_MARKERS.any { lower.contains(it) }
        ) {
            ProactiveRoutines.setGlobalEnabled(context, true)
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "প্রস্তাব চালু করে দিয়েছি — সময় হলে নিজে থেকে বলবো।"
                PlayIntent.SpokenLang.HINDI ->
                    "प्रस्ताव चालू कर दिए हैं — समय होने पर ख़ुद बोलूँगी।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Proposals are on — I'll speak up when it's time."
            }
        }

        // (d) Saare prastav band karna — "প্রস্তাব বন্ধ করো"।
        if (mentionsProposal(lower) &&
            DISABLE_ALL_MARKERS.any { lower.contains(it) }
        ) {
            ProactiveRoutines.setGlobalEnabled(context, false)
            ProactiveRoutines.clearPending(context)
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "প্রস্তাব বন্ধ করে দিয়েছি — আর নিজে থেকে কিছু বলবো না।"
                PlayIntent.SpokenLang.HINDI ->
                    "प्रस्ताव बंद कर दिए हैं — अब ख़ुद कुछ नहीं बोलूँगी।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Proposals are off — I won't speak up on my own anymore."
            }
        }

        // (e) Routine suchi — "আমার রুটিন কী কী"।
        if (mentionsRoutine(lower) &&
            LIST_MARKERS.any { lower.contains(it) }
        ) {
            return listReply(context, lang)
        }
        return null
    }

    /** Seekhi routinein + ginati — aawaz me sunane layak। */
    private fun listReply(
        context: Context,
        lang: PlayIntent.SpokenLang
    ): String {
        val learned = ProactiveRoutines.learned(context)
        val learning = ProactiveRoutines.readAll(context)
            .count { it.enabled && it.count in 1..2 }
        if (learned.isEmpty()) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    if (learning > 0) {
                        "এখনো কোনো রুটিন পুরোপুরি শেখা হয়নি — $learning টা শিখছি, ৩ বার হলেই প্রস্তাব করবো।"
                    } else {
                        "এখনো কোনো রুটিন শেখা হয়নি — একই কাজ একই সময়ে ৩ বার করলে শিখে নেবো।"
                    }
                PlayIntent.SpokenLang.HINDI ->
                    if (learning > 0) {
                        "अभी कोई routine पूरी तरह नहीं सीखी है — $learning सीख रही हूँ, 3 बार होते ही प्रस्ताव करूँगी।"
                    } else {
                        "अभी कोई routine नहीं सीखी है — एक ही काम एक ही समय पर 3 बार करोगे तो सीख लूँगी।"
                    }
                PlayIntent.SpokenLang.ENGLISH ->
                    if (learning > 0) {
                        "No routine is fully learned yet — I'm learning $learning; I'll propose once one reaches 3 times."
                    } else {
                        "No routine learned yet — do the same thing at the same time 3 times and I'll learn it."
                    }
            }
        }
        val lines = learned
            .sortedByDescending { it.count }
            .mapIndexed { index, routine ->
                "${index + 1}. '${routine.label}' — ${routine.count} বার, " +
                    timePhrase(
                        ProactiveRoutines.modeHour(routine),
                        0, lang
                    )
            }.joinToString("; ")
        val offNote = if (!ProactiveRoutines.isGlobalEnabled(context)) {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI -> " (প্রস্তাব এখন বন্ধ আছে)"
                PlayIntent.SpokenLang.HINDI -> " (प्रस्ताव अभी बंद हैं)"
                PlayIntent.SpokenLang.ENGLISH -> " (proposals are currently off)"
            }
        } else ""
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "শেখা রুটিনগুলো: $lines।$offNote"
            PlayIntent.SpokenLang.HINDI -> "सीखी हुई routine: $lines।$offNote"
            PlayIntent.SpokenLang.ENGLISH -> "Learned routines: $lines.$offNote"
        }
    }

    // ---------------------------------------------------------------
    // 2. JAWAB — latke prastav ka HAAN/NA
    // ---------------------------------------------------------------

    /**
     * Latka prastav ho to is baat ka natija — HAAN
     * par kaam, NA par 3 din ki chhutti, kuch aur
     * bole to prastav hatkar [ProposalAnswer.NotAnAnswer]
     * (mezbaan aam hukm chalaye)। Kabhi exception
     * nahi — gadbad par NotAnAnswer।
     *
     * [playSong] — mezbaan ka gaana chalane wala
     * raasta (uske apne runDirectYouTubePlay se —
     * natija wahi bolega, isliye HandledElsewhere)।
     */
    fun tryAnswerProposal(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        fromBackgroundService: Boolean,
        allowScreenshot: Boolean,
        playSong: (String) -> Unit
    ): ProposalAnswer {
        return try {
            answerProposal(
                context, engine, rawText, lang,
                fromBackgroundService, allowScreenshot, playSong
            )
        } catch (t: Throwable) {
            ProposalAnswer.NotAnAnswer
        }
    }

    private fun answerProposal(
        context: Context,
        engine: CommandEngine,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        fromBackgroundService: Boolean,
        allowScreenshot: Boolean,
        playSong: (String) -> Unit
    ): ProposalAnswer {
        val pending = ProactiveRoutines.pendingProposal(context)
            ?: return ProposalAnswer.NotAnAnswer
        val text = rawText.trim()
        if (text.isEmpty()) return ProposalAnswer.NotAnAnswer
        // NA — 3 din is routine ka prastav rukega।
        if (isNoAnswer(text)) {
            ProactiveRoutines.markDeclined(context, pending)
            return ProposalAnswer.Spoken(declineLine(lang))
        }
        // HAAN — kaam chalao (sirf chaar surakshit tarah)।
        if (isYesAnswer(text)) {
            ProactiveRoutines.clearPending(context)
            return when (pending.kind) {
                ProactiveRoutines.Kind.SONG -> {
                    playSong(pending.arg)
                    ProposalAnswer.HandledElsewhere
                }
                ProactiveRoutines.Kind.COMBO -> {
                    val line = ComboCommands.tryRunCombo(
                        context, engine, pending.arg, lang, allowScreenshot
                    )
                    if (line != null) {
                        ProposalAnswer.Spoken(line)
                    } else {
                        ProposalAnswer.Spoken(comboGoneLine(lang, pending.label))
                    }
                }
                ProactiveRoutines.Kind.APP -> {
                    ProposalAnswer.Spoken(
                        runAppProposal(
                            context, pending, fromBackgroundService, lang
                        )
                    )
                }
                ProactiveRoutines.Kind.ALARM -> {
                    ProposalAnswer.Spoken(
                        runAlarmProposal(context, pending, lang)
                    )
                }
            }
        }
        // Kuch aur bola — prastav ka pal beet gaya;
        // aam hukm chale (mezbaan wahi karega)।
        ProactiveRoutines.clearPending(context)
        return ProposalAnswer.NotAnAnswer
    }

    /**
     * Pakka HAAN — taakat wale shabd ("হ্যাঁ" / "yes")
     * ho to haan; warna sirf chhota saa haami wala
     * vakya ("চালাও" / "করো" / "ঠিক আছে") ho to haan।
     * Lamba hukm ("গান চালাও") kabhi HAAN nahi — woh
     * aam hukm hai, prastav ka jawab nahi।
     */
    fun isYesAnswer(rawText: String): Boolean {
        val tokens = tokensOf(rawText)
        if (tokens.isEmpty()) return false
        if (tokens.any { it in YES_STRONG_TOKENS }) return true
        val clean = rawText.trim()
            .trim('।', '.', '!', '?', ' ', ',', '—', '-')
            .trim()
            .lowercase(Locale.ROOT)
        if (clean in YES_SHORT_PHRASES) return true
        return tokens.size <= 2 && tokens.all { it in YES_TOKENS }
    }

    /**
     * Pakka NA — "না" / "থাক" / "no" / "পরে" poora
     * shabd ho to na। Tukda kabhi nahi ("নাম" me
     * "না" chhupa hai, woh na nahi)।
     */
    fun isNoAnswer(rawText: String): Boolean {
        val tokens = tokensOf(rawText)
        return tokens.any { it in NO_TOKENS }
    }

    /** Baat ko shabd-tukdon me todo (viram hatakar)। */
    private fun tokensOf(rawText: String): List<String> {
        return rawText.lowercase(Locale.ROOT)
            .split(Regex("[\\s,।.!?;:'\"()\\[\\]-]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Taakat wale HAAN shabd — inke hote hi haan। */
    private val YES_STRONG_TOKENS = setOf(
        "হ্যাঁ", "হ্যা", "হ্যাঁ।",
        "हाँ", "हां",
        "yes", "yeah", "yep", "ok", "okay", "sure"
    )

    /** Chhote haami wale shabd — sirf 2 shabd tak ki baat me haan। */
    private val YES_TOKENS = setOf(
        "হ্যাঁ", "হ্যা", "হ্যাঁ।",
        "हाँ", "हां",
        "yes", "yeah", "yep", "ok", "okay", "sure",
        "চালাও", "চালিয়ে", "দাও", "দিও", "করো", "করে",
        "লাগাও", "লাগিয়ে", "খুলে", "খোলো",
        "चालाओ", "करो", "दो", "लगा", "खोलो",
        "play", "run", "start", "open", "set"
    )

    /** Poori baat yahi ho to haan। */
    private val YES_SHORT_PHRASES = setOf(
        "চালাও", "চালিয়ে দাও", "চালিয়ে দিও", "করো", "করে দাও",
        "দাও", "দিও", "লাগিয়ে দাও", "খুলে দাও", "ঠিক আছে",
        "চালিয়ে নাও", "চালু করো",
        "चलाओ", "चला दो", "करो", "कर दो", "ठीक है",
        "yes", "ok", "okay", "sure", "play it"
    )

    /** NA wale shabd — poora shabd ho tabhi na। */
    private val NO_TOKENS = setOf(
        "না", "থাক", "নয়", "নাই", "পরে",
        "नहीं", "नही", "मत", "रहने",
        "no", "nope", "not"
    )

    // ---------------------------------------------------------------
    // HAAN par kaam chalana — sirf chaar surakshit tarah
    // ---------------------------------------------------------------

    /** APP wala prastav — app kholo, sachha natija bolo। */
    private fun runAppProposal(
        context: Context,
        routine: ProactiveRoutines.Routine,
        fromBackgroundService: Boolean,
        lang: PlayIntent.SpokenLang
    ): String {
        val pkg = routine.arg.trim()
        val opened = if (fromBackgroundService) {
            try {
                PhoneActions.openAppFromBackground(context, pkg) ==
                    LaunchOutcome.LAUNCHED
            } catch (t: Throwable) {
                false
            }
        } else {
            try {
                PhoneActions.openApp(context, pkg)
            } catch (t: Throwable) {
                false
            }
        }
        return if (opened) {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.label}' খুলে দিয়েছি।"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.label}' खोल दिया है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Opened ${routine.label}."
            }
        } else {
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.label}' খুলতে পারলাম না — একবার নিজে চেষ্টা করে দেখো।"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.label}' खुल नहीं पाया — एक बार ख़ुद कोशिश करके देखो।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I couldn't open ${routine.label} — please try once yourself."
            }
        }
    }

    /**
     * ALARM wala prastav — wahi kaam wahi samay par
     * yaad lagao (aaj samay bacha ho to aaj, warna
     * kal)। Wahi yaad pehle se lagi ho to jhootha
     * "laga diya" nahi — wahi sach bolo।
     */
    private fun runAlarmProposal(
        context: Context,
        routine: ProactiveRoutines.Routine,
        lang: PlayIntent.SpokenLang
    ): String {
        val task = routine.arg.substringBeforeLast("|").trim()
        val timePart = routine.arg.substringAfterLast("|", "").trim()
        val hourMin = timePart.split(":")
        val hour = hourMin.getOrNull(0)?.toIntOrNull()
        val minute = hourMin.getOrNull(1)?.toIntOrNull() ?: 0
        if (task.isEmpty() || hour == null || hour !in 0..23 ||
            minute !in 0..59
        ) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "মনে করানোটা কখন লাগাবো বুঝতে পারলাম না — সময়টা আবার বলো।"
                PlayIntent.SpokenLang.HINDI ->
                    "Reminder कब लगाऊँ, समझ नहीं पाया — समय फिर से बताओ।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I couldn't work out when to set that reminder — please tell me the time again."
            }
        }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis() + 60_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val atMillis = cal.timeInMillis
        // Wahi yaad wahi din pehle se lagi ho to dobara nahi।
        val existing = ReminderCommands.loadAll(context).firstOrNull { r ->
            r.text.equals(task, ignoreCase = true) &&
                kotlin.math.abs(r.atMillis - atMillis) < 5L * 60L * 1000L
        }
        if (existing != null) {
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'$task'-এর মনে করানো ${timePhrase(hour, minute, lang)} আগে থেকেই লাগানো আছে।"
                PlayIntent.SpokenLang.HINDI ->
                    "'$task' का reminder ${timePhrase(hour, minute, lang)} पहले से लगा है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "The reminder for '$task' ${timePhrase(hour, minute, lang)} is already set."
            }
        }
        val reminder = ReminderCommands.Reminder(
            System.currentTimeMillis(), task, atMillis, "NONE", 0
        )
        val all = ReminderCommands.loadAll(context).toMutableList()
        all.add(reminder)
        ReminderCommands.saveAll(context, all)
        if (!ReminderCommands.schedule(context, reminder)) {
            ReminderCommands.removeById(context, reminder.id)
            return when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "মনে করানোটা লাগাতে পারলাম না — একবার আবার চেষ্টা করো।"
                PlayIntent.SpokenLang.HINDI ->
                    "Reminder नहीं लग पाया — एक बार फिर कोशिश करो।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "I couldn't set that reminder — please try once more."
            }
        }
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ঠিক আছে, $task — ${timePhrase(hour, minute, lang)} মনে করিয়ে দেবো।"
            PlayIntent.SpokenLang.HINDI ->
                "ठीक है, $task — ${timePhrase(hour, minute, lang)} याद दिला दूँगी।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Okay, I'll remind you about $task ${timePhrase(hour, minute, lang)}."
        }
    }

    // ---------------------------------------------------------------
    // 3. SAWAAL — prastav ka roop + samay wale vakya
    // ---------------------------------------------------------------

    /**
     * Prastav ka sawal — "এখন সকাল ৭টা — 'কিশোর
     * কুমারের গান' চালিয়ে দেবো?" ([lang] me)।
     */
    fun proposalQuestion(
        routine: ProactiveRoutines.Routine,
        lang: PlayIntent.SpokenLang
    ): String {
        val hour = ProactiveRoutines.modeHour(routine)
        val prefix = if (hour in 0..23) hourPhrase(hour, lang) else ""
        val body = when (routine.kind) {
            ProactiveRoutines.Kind.SONG -> when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.arg}' গানটা চালিয়ে দেবো?"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.arg}' गाना चला दूँ?"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Shall I play '${routine.arg}'?"
            }
            ProactiveRoutines.Kind.COMBO -> when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.label}' কম্বোটা চালিয়ে দেবো?"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.label}' कॉम्बो चला दूँ?"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Shall I run the '${routine.label}' combo?"
            }
            ProactiveRoutines.Kind.APP -> when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.label}' খুলে দেবো?"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.label}' खोल दूँ?"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Shall I open ${routine.label}?"
            }
            ProactiveRoutines.Kind.ALARM -> when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "'${routine.label}'-এর মনে করানোটা লাগিয়ে দেবো?"
                PlayIntent.SpokenLang.HINDI ->
                    "'${routine.label}' का reminder लगा दूँ?"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Shall I set the reminder for '${routine.label}'?"
            }
        }
        return if (prefix.isEmpty()) body else "$prefix — $body"
    }

    /** "এখন সকাল ৭টা" jaisa samay wala hissa। */
    private fun hourPhrase(hour: Int, lang: PlayIntent.SpokenLang): String {
        val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> {
                val part = when (hour) {
                    in 0..3 -> "রাতে"
                    in 4..11 -> "সকালে"
                    in 12..15 -> "দুপুরে"
                    in 16..18 -> "বিকেলে"
                    else -> "রাতে"
                }
                "এখন $part ${toBnDigits(h12.toString())}টা"
            }
            PlayIntent.SpokenLang.HINDI -> {
                val part = when (hour) {
                    in 0..3 -> "रात"
                    in 4..11 -> "सुबह"
                    in 12..15 -> "दोपहर"
                    in 16..18 -> "शाम"
                    else -> "रात"
                }
                "अभी $part $h12 बजे हैं"
            }
            PlayIntent.SpokenLang.ENGLISH ->
                "It's $h12 ${if (hour < 12) "am" else "pm"}"
        }
    }

    /** "সকাল ৭টায়" / "at 7 am" jaisa samay wala hissa (yaad / suchi ke liye)। */
    private fun timePhrase(
        hour: Int,
        minute: Int,
        lang: PlayIntent.SpokenLang
    ): String {
        if (hour !in 0..23 || minute !in 0..59) return ""
        val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> {
                val part = when (hour) {
                    in 0..3 -> "রাতে"
                    in 4..11 -> "সকালে"
                    in 12..15 -> "দুপুরে"
                    in 16..18 -> "বিকেলে"
                    else -> "রাতে"
                }
                if (minute == 0) {
                    "$part ${toBnDigits(h12.toString())}টায়"
                } else {
                    "$part ${toBnDigits(h12.toString())}টা " +
                        "${toBnDigits(minute.toString())} মিনিটে"
                }
            }
            PlayIntent.SpokenLang.HINDI -> {
                if (minute == 0) "$h12 बजे" else "$h12 बजकर $minute मिनट पर"
            }
            PlayIntent.SpokenLang.ENGLISH -> {
                val ampm = if (hour < 12) "am" else "pm"
                if (minute == 0) "at $h12 $ampm"
                else "at $h12:${minute.toString().padStart(2, '0')} $ampm"
            }
        }
    }

    private fun toBnDigits(s: String): String =
        s.map { ch ->
            when (ch) {
                '0' -> '০'; '1' -> '১'; '2' -> '২'; '3' -> '৩'; '4' -> '৪'
                '5' -> '৫'; '6' -> '৬'; '7' -> '৭'; '8' -> '৮'; '9' -> '৯'
                else -> ch
            }
        }.joinToString("")

    /** "না" par chhota jawab — inkaar, hamesha vinamra। */
    private fun declineLine(lang: PlayIntent.SpokenLang): String =
        when (lang) {
            PlayIntent.SpokenLang.BENGALI ->
                "ঠিক আছে — এখন থাক।"
            PlayIntent.SpokenLang.HINDI ->
                "ठीक है — अभी रहने दो।"
            PlayIntent.SpokenLang.ENGLISH ->
                "Okay — leaving it for now."
        }

    /** Combo ab bandha hi nahi (mit gaya) — imaandaar jawab। */
    private fun comboGoneLine(
        lang: PlayIntent.SpokenLang,
        label: String
    ): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "'$label' কম্বোটা আর মনে নেই — আবার শিখিয়ে দাও।"
        PlayIntent.SpokenLang.HINDI ->
            "'$label' वाला कॉम्बो अब याद नहीं है — फिर से सिखा दो।"
        PlayIntent.SpokenLang.ENGLISH ->
            "I don't remember the '$label' combo anymore — please teach it again."
    }

    /** Bhasha tag ("bn-IN" / "hi-IN" / ...) se jawab ki bhasha। */
    fun langFromTag(tag: String): PlayIntent.SpokenLang {
        val lower = tag.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("bn") -> PlayIntent.SpokenLang.BENGALI
            lower.startsWith("hi") -> PlayIntent.SpokenLang.HINDI
            else -> PlayIntent.SpokenLang.ENGLISH
        }
    }

    // ---------------------------------------------------------------
    // Pehchan — sirf saaf prastav/routine wale roop
    // ---------------------------------------------------------------

    /** "প্রস্তাব" ka zikr hai kya (Bangla/Hindi/English)। */
    private fun mentionsProposal(lower: String): Boolean =
        PROPOSAL_MENTION_MARKERS.any { lower.contains(it) }

    private val PROPOSAL_MENTION_MARKERS = listOf(
        "প্রস্তাব", "প্রস্তাবগুলো", "প্রস্তাবটা",
        "प्रस्ताव", "proposal", "suggest"
    )

    /** "রুটিন" ka zikr hai kya। */
    private fun mentionsRoutine(lower: String): Boolean =
        ROUTINE_MENTION_MARKERS.any { lower.contains(it) }

    private val ROUTINE_MENTION_MARKERS = listOf(
        "রুটিন", "रूटीन", "routine"
    )

    /** Ek routine ka prastav band karne wale roop। */
    private val DISABLE_ONE_MARKERS = listOf(
        "আর প্রস্তাব কোরো না", "আর প্রস্তাব করো না",
        "প্রস্তাব কোরো না", "প্রস্তাব করো না",
        "আর বলো না", "আর বলবে না",
        "और प्रस्ताव मत करना", "प्रस्ताव मत करना",
        "don't propose", "stop proposing"
    )

    /** Ek routine ka prastav wapas chalane wale roop। */
    private val ENABLE_ONE_MARKERS = listOf(
        "আবার প্রস্তাব করো", "আবার প্রস্তাব কোরো",
        "ফের প্রস্তাব করো", "আবার বলো",
        "फिर से प्रस्ताव करो", "फिर प्रस्ताव करो",
        "propose again"
    )

    /** Saare prastav chalane wale roop। */
    private val ENABLE_ALL_MARKERS = listOf(
        "প্রস্তাব চালু করো", "প্রস্তাব চালিয়ে দাও",
        "প্রস্তাব চালু", "প্রস্তাব অন করো",
        "प्रस्ताव चालू करो", "प्रस्ताव चालू",
        "turn on proposals", "enable proposals",
        "proposals on"
    )

    /** Saare prastav band karne wale roop। */
    private val DISABLE_ALL_MARKERS = listOf(
        "প্রস্তাব বন্ধ করো", "প্রস্তাব বন্ধ",
        "প্রস্তাব অফ করো", "প্রস্তাব থামাও",
        "प्रस्ताव बंद करो", "प्रस्ताव बंद",
        "turn off proposals", "disable proposals",
        "proposals off", "stop proposals"
    )

    /** Routine suchi poochne wale roop। */
    private val LIST_MARKERS = listOf(
        "কী কী", "কি কি", "কী", "কি",
        "বলো", "বলে দাও", "বল",
        "আছে", "মনে আছে",
        "क्या", "बताओ",
        "what", "list", "my"
    )
}

package com.aship.jarvis.commands

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.OverlayLauncher
import java.net.URLEncoder

/**
 * v59: naam bolkar call / message — OFFLINE, deterministic.
 *
 * "মাকে ফোন করো" / "অমুককে মেসেজ করো" — naam pehle
 * v56 wale seekhe hue contacts (PhoneLearner) me dhoondho,
 * na mile to live ContactsProvider se (v29 wala hi raasta —
 * [PhoneLearner.scanContacts])। Milan v29 wali teen seedhi
 * + kareeb-shabd (fuzzy) wali chaauthi seedhi।
 *
 * v29 ka pakka faisla yahin bhi hai: call KHUD nahi lagta
 * (CALL_PHONE permission jaan-boojh kar NAHI li gayi) —
 * dialer number bhar kar khulta hai, call wala button user
 * dabata hai। Message bhi SMS/WhatsApp composer khulta hai,
 * bhejta user khud hai।
 */
object ContactCallCommands {

    /**
     * Call / message wala hukm ho to chalao, warna null —
     * aam raasta bilkul pehle jaisa chalega। [text] =
     * normalize hua dispatch text।
     */
    fun tryHandle(
        context: Context,
        text: String,
        fromBackgroundService: Boolean
    ): CommandResult? {
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val tokenSet = tokens.toSet()

        val hasMessageWord = tokenSet.any { it in MESSAGE_WORDS } ||
            text.contains("মেসেজ")
        val hasCallWord = tokenSet.any { it in CALL_WORDS } ||
            tokens.any { it.contains("ফোন") }
        val hasVerb = tokenSet.any { it in ACTION_VERBS } ||
            tokens.any { it.contains("কর") || it.contains("कर") }
        if (!hasVerb) return null
        // Message wale shabd hon to message hi hai — call
        // wala raasta us par kabhi nahi chalega।
        val isMessage = hasMessageWord
        val isCall = !isMessage && hasCallWord
        if (!isMessage && !isCall) return null

        val lang = PlayIntent.requestLanguage(text)
        val name = extractName(tokens, isMessage)
        // "ফোন বন্ধ করো" / "ফোন চালু করো" — ফোনটাকে
        // নিয়েই হুকুম, কাউকে ফোন করা নয়; ye call hukm
        // hi nahi — aam raasta (imaandaar "nahi kar
        // sakta") hi chalega।
        if (name in DEVICE_ONLY_WORDS) return null
        if (name.isEmpty()) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        if (isMessage) "কাকে মেসেজ করবো? নামটা বলো।"
                        else "কাকে ফোন করবো? নামটা বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        if (isMessage) "किसको मैसेज करना है? नाम बताओ।"
                        else "किसको फ़ोन करना है? नाम बताओ।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        if (isMessage) "Who should I message? Tell me the name."
                        else "Who should I call? Tell me the name."
                },
                true
            )
        }

        val lookup = findContact(context, name)
        return when (lookup) {
            is Lookup.PermissionDenied -> CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "কন্টাক্ট দেখার অনুমতি নেই — অ্যাপ খুলে একবার অনুমতি দাও, " +
                            "তাহলে নাম ধরে ফোন করতে পারবো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "Contacts देखने की अनुमति नहीं है — ऐप खोलकर एक बार " +
                            "अनुमति दे दो, फिर नाम बोलकर कॉल कर पाऊँगी।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "I don't have permission to see contacts — open the app " +
                            "and allow it once, then I can call by name."
                },
                true
            )
            is Lookup.NotFound -> CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "ওই নামে কাউকে পেলাম না — START চেপে কন্টাক্ট শিখিয়ে " +
                            "নিলে নাম ধরে ফোন করতে পারবো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "'$name' नाम का कोई नंबर नहीं मिला — START दबाकर " +
                            "contacts सिखा लो, फिर नाम बोलकर कॉल कर पाऊँगी।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "I couldn't find anyone named '$name' — press START " +
                            "to learn your contacts, then I can call by name."
                },
                true
            )
            is Lookup.Many -> CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI ->
                        "একই নামে কয়েকজন আছে — ${candidateList(lookup.matches)}। " +
                            "পুরো নামটা বলো।"
                    PlayIntent.SpokenLang.HINDI ->
                        "इस नाम के कई लोग मिले — ${candidateList(lookup.matches)}। " +
                            "पूरा नाम बताओ।"
                    PlayIntent.SpokenLang.ENGLISH ->
                        "Several people share that name — " +
                            "${candidateList(lookup.matches)}. Tell me the full name."
                },
                true
            )
            is Lookup.One -> {
                val person = lookup.person
                if (isMessage) {
                    openMessageComposer(
                        context, text, person, fromBackgroundService, lang
                    )
                } else {
                    openDialer(
                        context, person, fromBackgroundService, lang
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Naam nikaalna
    // ------------------------------------------------------------------

    /**
     * Hukm wale shabdon ko hatakar bacha hua naam — "মাকে"
     * ka "কে" (karm-karak) aur "আমার" jaise bhare shabd bhi
     * hat jate hain। "মা কে ফোন করো" me alag "কে" token
     * bhi isi se saaf hota hai।
     */
    private fun extractName(tokens: List<String>, isMessage: Boolean): String {
        val stop = NAME_STOP_TOKENS +
            (if (isMessage) MESSAGE_WORDS else CALL_WORDS) +
            ACTION_VERBS
        val kept = ArrayList<String>()
        for (token in tokens) {
            if (token in stop) continue
            if (token.contains("ফোন")) continue
            if (token.contains("কর") || token.contains("कर")) continue
            var t = token
            // "মাকে" -> "মা", "অমুককে" -> "অমুক"।
            if (t.endsWith("কে") && t.length > 2) t = t.dropLast(1)
            if (t.isNotEmpty()) kept.add(t)
        }
        return kept.joinToString(" ").trim()
    }

    // ------------------------------------------------------------------
    // Khoj: seekha hua pehle, live uske baad
    // ------------------------------------------------------------------

    private sealed class Lookup {
        object PermissionDenied : Lookup()
        object NotFound : Lookup()
        class One(val person: PhoneLearner.LearnedContact) : Lookup()
        class Many(val matches: List<PhoneLearner.LearnedContact>) : Lookup()
    }

    private fun findContact(context: Context, name: String): Lookup {
        val learned = try {
            PhoneLearner.readContacts(context)
        } catch (t: Throwable) {
            emptyList()
        }
        var matches = matchContacts(learned, name)
        if (matches.isNotEmpty()) return singleOrMany(matches)
        // Seekha hua khaali ya naam usme nahi — live dekho।
        // Permission na ho to scan khaali lautata hai; farq
        // batane ke liye permission yahin jaanch lo।
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Lookup.PermissionDenied
        }
        val live = try {
            PhoneLearner.scanContacts(context)
        } catch (t: Throwable) {
            emptyList()
        }
        matches = matchContacts(live, name)
        if (matches.isEmpty()) return Lookup.NotFound
        return singleOrMany(matches)
    }

    private fun singleOrMany(
        matches: List<PhoneLearner.LearnedContact>
    ): Lookup {
        // Ek hi naam ke kai number hon to woh ek hi vyakti —
        // pehla (mobile wala kram) chuno, "kai log" nahi।
        val distinctNames = matches.map { normalizeName(it.name) }.distinct()
        return if (distinctNames.size <= 1) {
            Lookup.One(matches[0])
        } else {
            // Har naam se ek — pehla number wala pratinidhi।
            val reps = ArrayList<PhoneLearner.LearnedContact>()
            for (m in matches) {
                if (reps.none { normalizeName(it.name) == normalizeName(m.name) }) {
                    reps.add(m)
                }
            }
            Lookup.Many(reps)
        }
    }

    /**
     * v29 wali teen seedhi (exact -> shuru -> andar) +
     * chaauthi kareeb-shabd wali seedhi (levenshtein)।
     * Har seedhi saaf-naam aur jagah-hate-naam, dono par।
     */
    private fun matchContacts(
        contacts: List<PhoneLearner.LearnedContact>,
        query: String
    ): List<PhoneLearner.LearnedContact> {
        val queryNorm = normalizeName(query)
        val querySquash = squashName(queryNorm)
        if (queryNorm.isEmpty()) return emptyList()
        val tiers: List<(String, String) -> Boolean> = listOf(
            { norm, squash -> norm == queryNorm || squash == querySquash },
            { norm, squash ->
                norm.startsWith(queryNorm) || squash.startsWith(querySquash)
            },
            { norm, squash ->
                norm.contains(queryNorm) || squash.contains(querySquash)
            }
        )
        for (tier in tiers) {
            val matched = contacts.filter { c ->
                val norm = normalizeName(c.name)
                tier(norm, squashName(norm))
            }
            if (matched.isNotEmpty()) return matched
        }
        // Chaauthi seedhi: kareeb suna naam (1-3 akshar ka farq)।
        val allowed = when {
            queryNorm.length >= 7 -> 3
            queryNorm.length >= 5 -> 2
            else -> 1
        }
        val fuzzy = contacts.filter { c ->
            val norm = normalizeName(c.name)
            levenshtein(norm, queryNorm) in 1..allowed ||
                levenshtein(squashName(norm), querySquash) in 1..allowed
        }
        return fuzzy
    }

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

    private fun normalizeName(raw: String): String =
        raw.lowercase(java.util.Locale.ROOT).trim()
            .replace(Regex("""\s+"""), " ")

    private fun squashName(normalized: String): String =
        normalized.replace(" ", "")

    /** "Naam — number ke aakhiri 4 ank 1234; ..." (v29 wala roop)। */
    private fun candidateList(
        matches: List<PhoneLearner.LearnedContact>
    ): String {
        val candidates = matches.take(4).joinToString("; ") {
            "${it.name} — number ke aakhiri 4 ank ${lastFourDigits(it.number)}"
        }
        val more = if (matches.size > 4) " (aur ${matches.size - 4} aur bhi hain)" else ""
        return candidates + more
    }

    private fun lastFourDigits(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= 4) digits.takeLast(4) else digits
    }

    // ------------------------------------------------------------------
    // Dialer / SMS / WhatsApp kholna
    // ------------------------------------------------------------------

    /** tel: URI ke liye saaf number — '+' aur ank rehne do। */
    private fun cleanDialNumber(number: String): String =
        number.filter { it == '+' || it.isDigit() }

    private fun openDialer(
        context: Context,
        person: PhoneLearner.LearnedContact,
        fromBackgroundService: Boolean,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("tel:" + cleanDialNumber(person.number))
        }
        val dispatchError = dispatchIntent(context, intent, fromBackgroundService, lang)
        if (dispatchError != null) return dispatchError
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "ডায়ালার খুলে দিয়েছি — ${person.name} (${person.number})। " +
                        "কল বোতাম চাপলেই লাগবে।"
                PlayIntent.SpokenLang.HINDI ->
                    "डायलर खोल दिया है — ${person.name} (${person.number})। " +
                        "कॉल वाला बटन दबाते ही लग जाएगा।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Dialer opened — ${person.name} (${person.number}). " +
                        "Just tap the call button."
            },
            true
        )
    }

    private fun openMessageComposer(
        context: Context,
        text: String,
        person: PhoneLearner.LearnedContact,
        fromBackgroundService: Boolean,
        lang: PlayIntent.SpokenLang
    ): CommandResult {
        val body = extractMessageBody(text)
        // WhatsApp ka zikr ho to pehle wahi — v30 wala wa.me
        // raasta; app na ho to SMS par giro।
        if (text.split(Regex("""\s+""")).any { it == "whatsapp" }) {
            val wa = openWhatsAppChat(context, person, body, fromBackgroundService, lang)
            if (wa != null) return wa
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.parse("smsto:" + cleanDialNumber(person.number))
            if (body.isNotEmpty()) putExtra("sms_body", body)
        }
        val dispatchError = dispatchIntent(context, intent, fromBackgroundService, lang)
        if (dispatchError != null) return dispatchError
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "মেসেজ লেখার স্ক্রিন খুলে দিয়েছি — ${person.name}।"
                PlayIntent.SpokenLang.HINDI ->
                    "मैसेज लिखने वाली स्क्रीन खोल दी है — ${person.name}।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Message screen opened for ${person.name}."
            },
            true
        )
    }

    /**
     * wa.me link se WhatsApp chat — sandesh pehle se bhara
     * hua, bhejna user khud dabata hai। App na ho to null
     * (bulane wala SMS par gir jata hai)।
     */
    private fun openWhatsAppChat(
        context: Context,
        person: PhoneLearner.LearnedContact,
        body: String,
        fromBackgroundService: Boolean,
        lang: PlayIntent.SpokenLang
    ): CommandResult? {
        val digits = person.number.filter { it.isDigit() }
        val waNumber = when {
            digits.length == 10 -> "91$digits"
            digits.length > 10 -> digits
            else -> return null
        }
        val url = "https://wa.me/$waNumber" +
            if (body.isNotEmpty()) "?text=" + URLEncoder.encode(body, "UTF-8") else ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.whatsapp")
        }
        val hasHandler = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) return null
        val dispatchError = dispatchIntent(context, intent, fromBackgroundService, lang)
        if (dispatchError != null) return dispatchError
        return CommandResult(
            when (lang) {
                PlayIntent.SpokenLang.BENGALI ->
                    "হোয়াটসঅ্যাপে ${person.name}-এর চ্যাট খুলে দিয়েছি।"
                PlayIntent.SpokenLang.HINDI ->
                    "WhatsApp पर ${person.name} की चैट खोल दी है।"
                PlayIntent.SpokenLang.ENGLISH ->
                    "Opened ${person.name}'s WhatsApp chat."
            },
            true
        )
    }

    /**
     * Intent asal me bhejo — saamne ho to seedha, background
     * service se ho to overlay bridge (v29 wala hi niyam)।
     * Dispatch ho jaye to null, warna imaandaar jawab।
     */
    private fun dispatchIntent(
        context: Context,
        intent: Intent,
        fromBackgroundService: Boolean,
        lang: PlayIntent.SpokenLang
    ): CommandResult? {
        val hasHandler = try {
            intent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) {
            return CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI -> "ওই অ্যাপটা খুলতে পারলাম না।"
                    PlayIntent.SpokenLang.HINDI -> "वह ऐप नहीं खुल पाया।"
                    PlayIntent.SpokenLang.ENGLISH -> "That app couldn't be opened."
                },
                true
            )
        }
        if (fromBackgroundService) {
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> null
                LaunchOutcome.PERMISSION_NEEDED -> CommandResult(
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI ->
                            "এটা খুলতে 'Display over other apps' অনুমতি লাগবে — " +
                                "অ্যাপ খুলে একবার অনুমতি দাও।"
                        PlayIntent.SpokenLang.HINDI ->
                            "इसे खोलने के लिए 'Display over other apps' अनुमति " +
                                "चाहिए — ऐप खोलकर एक बार अनुमति दे दो।"
                        PlayIntent.SpokenLang.ENGLISH ->
                            "Opening this needs the 'Display over other apps' " +
                                "permission — allow it once from the app."
                    },
                    true
                )
                LaunchOutcome.LOCKED -> CommandResult(
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI ->
                            "ফোন লক আছে — আগে আনলক করো, তারপর খুলে দেবো।"
                        PlayIntent.SpokenLang.HINDI ->
                            "फ़ोन लॉक है — पहले अनलॉक कर लो, फिर खोल देती हूँ।"
                        PlayIntent.SpokenLang.ENGLISH ->
                            "The phone is locked — unlock it first, then I'll open it."
                    },
                    true
                )
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED -> CommandResult(
                    when (lang) {
                        PlayIntent.SpokenLang.BENGALI -> "ওই অ্যাপটা খুলতে পারলাম না।"
                        PlayIntent.SpokenLang.HINDI -> "वह ऐप नहीं खुल पाया।"
                        PlayIntent.SpokenLang.ENGLISH -> "That app couldn't be opened."
                    },
                    true
                )
            }
        }
        return try {
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            CommandResult(
                when (lang) {
                    PlayIntent.SpokenLang.BENGALI -> "ওই অ্যাপটা খুলতে পারলাম না।"
                    PlayIntent.SpokenLang.HINDI -> "वह ऐप नहीं खुल पाया।"
                    PlayIntent.SpokenLang.ENGLISH -> "That app couldn't be opened."
                },
                true
            )
        }
    }

    /**
     * "অমুককে মেসেজ করো কাল আসছি" — kriya ke baad wala
     * hissa hi sandesh। "যে"/"কি" se shuru ho to woh bhi
     * hat jata hai। Kuch na ho to khaali (sirf praapak)।
     */
    private fun extractMessageBody(text: String): String {
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() }
        var verbIdx = -1
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t in ACTION_VERBS || t.contains("কর") || t.contains("कर")) {
                verbIdx = i
            }
        }
        if (verbIdx < 0 || verbIdx + 1 >= tokens.size) return ""
        val body = tokens.drop(verbIdx + 1)
            .dropWhile { it == "যে" || it == "কি" || it == "ki" || it == "বলে" }
            .joinToString(" ")
            .trim()
        return body
    }

    private val CALL_WORDS = setOf(
        "phone", "call", "কল",
        // "ফোন" token ke andar bhi milta hai (ফোনটা) — isliye
        // contains wali jaanch alag se hai (upar dekho)।
    )

    private val MESSAGE_WORDS = setOf(
        "messages", "message", "sms", "এসএমএস", "whatsapp"
    )

    private val ACTION_VERBS = setOf(
        "করো", "করুন", "কর", "দাও", "লাগাও", "লাগিয়ে",
        "karo", "koro", "do", "lagao", "dial", "ডায়াল",
        "करो", "कर", "लगाओ", "दो"
    )

    private val NAME_STOP_TOKENS = setOf(
        "আমার", "আমাকে", "আমায়", "কে", "কো",
        "mera", "mere", "meri", "mujhe", "my",
        "को", "एकটা", "please", "একটু", "তো"
    )

    /** Naam ki jagah sirf ye shabd hon to call hukm hi nahi। */
    private val DEVICE_ONLY_WORDS = setOf(
        "বন্ধ", "band", "off", "চালু", "chalu", "on"
    )
}

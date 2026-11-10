package com.aship.jarvis.commands

import android.content.Context
import com.aship.jarvis.ai.DialectMap
import com.aship.jarvis.ai.PlayIntent
import java.util.Locale

/**
 * v97: DIALECT — "Maya ko meri boli
 * sikhana" (v50 [TeachCommands] ke saath,
 * uske badle me nahi)।
 *
 * Teen khaas roop:
 *  - SHIKHANA: "'X' শুনলে 'Y' বুঝবে" — X
 *    suna to Y samjho (bol-chaal ka shabd
 *    seekh jata hai, [DialectMap] me bandh
 *    jata hai)। "বললে…বুঝবে" bhi yahin
 *    aata hai — "বুঝবে" hi iski pehchan hai;
 *    TeachCommands wala "'X' বললে Y" (bina
 *    "বুঝবে" ke) iske paas kabhi nahi aata।
 *    Isiliye dispatch me ye TeachCommands se
 *    PEHLE chalta hai।
 *  - SUDHAAR: "না, আমি বলেছি 'X'" — pichli
 *    suni hui baat se X tak ka jod khud
 *    bandh jata hai, agli baar se wahi X
 *    samjha jayega।
 *  - SUCHITRA / BHULNA: "ডায়ালেক্ট কী কী
 *    শেখানো আছে" / "ভুলে যাও <shabd>"।
 *
 * Kuch na pehchana to null (aam raasta)।
 * Sab kuch try/catch me hai — admin shabd
 * galat ho to asal kaam kabhi nahi rukta।
 */
object DialectCommands {

    // ---------------------------------------------------------------
    // Pravesh
    // ---------------------------------------------------------------

    /**
     * [tryHandleAdmin] ki surakshit khidki — jo
     * samajh na aaye uska jawab null hi mile।
     */
    fun tryHandleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        lastHeard: String?
    ): String? {
        return try {
            handleAdmin(context, rawText, lang, lastHeard)
        } catch (t: Throwable) {
            null
        }
    }

    /** Suni hui baat kisi bandhe bol-chaal se mile to uska asal hukm; warna null। */
    fun translateOrNull(context: Context, rawText: String): String? {
        return try {
            DialectMap.translate(context, rawText)
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Bandha hua kaam
    // ---------------------------------------------------------------

    private fun handleAdmin(
        context: Context,
        rawText: String,
        lang: PlayIntent.SpokenLang,
        lastHeard: String?
    ): String? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)

        // 1) SHIKHANA — "'X' শুনলে 'Y' বুঝবে" (ya "বললে…বুঝবে")।
        parseDialectTeach(text, lower)?.let { (spoken, command) ->
            if (DialectMap.dialectKey(spoken) == DialectMap.dialectKey(command)) {
                return reply(
                    lang,
                    "ওটাই তো বলেছ — '$spoken' মানে '$command', এটা নতুন করে শেখার দরকার নেই।",
                    "'$spoken' का मतलब '$command' है — इसे अलग से सीखने की ज़रूरत नहीं।",
                    "'$spoken' already means '$command' — nothing new to learn."
                )
            }
            DialectMap.save(context, spoken, command)
            return reply(
                lang,
                "শিখে নিলাম — '$spoken' শুনলে '$command' বুঝব।",
                "सीख लिया — '$spoken' सुनूँगी तो '$command' समझूँगी।",
                "Learned — when I hear '$spoken' I'll understand '$command'."
            )
        }

        // 2) SUDHAAR — "না, আমি বলেছি 'X'" (pichli suni baat se jod bandho)।
        parseCorrection(text, lower)?.let { intended ->
            val heard = lastHeard?.trim().orEmpty()
            if (heard.isEmpty() || isWakeOnly(heard)) {
                return reply(
                    lang,
                    "আগের কথাটা মনে নেই — কোন কথাটা ভুল শুনেছিলাম, সেটা আরেকবার বলো।",
                    "पिछली बात याद नहीं — कौन सी बात गलत सुनी थी, वह एक बार फिर बताओ।",
                    "I don't remember the last thing I heard — tell me again which one I got wrong."
                )
            }
            if (DialectMap.dialectKey(heard) == DialectMap.dialectKey(intended)) {
                return reply(
                    lang,
                    "ওটাই তো শুনেছিলাম — '$intended'।",
                    "वही तो सुना था — '$intended'।",
                    "That's what I heard — '$intended'."
                )
            }
            DialectMap.save(context, heard, intended)
            return reply(
                lang,
                "শিখে নিলাম — '$heard' শুনলে '$intended' বুঝব।",
                "सीख लिया — '$heard' सुनूँगी तो '$intended' समझूँगी।",
                "Learned — when I hear '$heard' I'll understand '$intended'."
            )
        }

        // 3) SUCHITRA — "ডায়ালেক্ট কী কী শেখানো আছে"।
        if (isDialectList(lower)) {
            val all = DialectMap.readAll(context)
            if (all.isEmpty()) {
                return reply(
                    lang,
                    "এখনো কোনো ডায়ালেক্ট শেখানো হয়নি। \"শিখে নাও: 'X' শুনলে 'Y' বুঝবে\" বলে শেখাতে পারো।",
                    "अभी कोई बोली नहीं सिखाई गई है। \"শিখে নাও: 'X' শুনলে 'Y' বুঝবে\" कहकर सिखा सकते हो।",
                    "No dialect words have been taught yet. Teach one with \"শিখে নাও: 'X' শুনলে 'Y' বুঝবে\"."
                )
            }
            val shown = all.takeLast(10)
            val joined = shown.joinToString(" | ") { "'${it.spoken}' → '${it.command}'" }
            return reply(
                lang,
                "ডায়ালেক্টে শেখানো কথা: $joined",
                "बोली में सिखाए गए शब्द: $joined",
                "Dialect words I've learned: $joined"
            )
        }

        // 4) BHULNA — "ভুলে যাও <shabd>" (sirf bandhe bol-chaal ke liye;
        //    baaki bhoolna TeachCommands / niyam wale haathon me hai)।
        if (lower.contains("ভুলে যাও") || lower.contains("भूल जाओ")) {
            DialectMap.findMentioned(context, text)?.let { mapping ->
                DialectMap.delete(context, mapping.spoken)
                return reply(
                    lang,
                    "ভুলে গেলাম — '${mapping.spoken}' আর '${mapping.command}' বলে বুঝব না।",
                    "भूल गई — '${mapping.spoken}' को अब '${mapping.command}' नहीं समझूँगी।",
                    "Forgotten — I won't understand '${mapping.spoken}' as '${mapping.command}' anymore."
                )
            }
            // Bandha bol-chaal nahi — aage (TeachCommands) ki baari।
            return null
        }

        return null
    }

    // ---------------------------------------------------------------
    // Roop-parakh
    // ---------------------------------------------------------------

    /** "'X' শুনলে 'Y' বুঝবে" (ya "বললে…বুঝবে") → (X, Y)। Kuch na mile to null। */
    private fun parseDialectTeach(
        text: String,
        lower: String
    ): Pair<String, String>? {
        // "বুঝবে" hi is roop ki pehchan hai (TeachCommands ke
        // "'X' বললে Y" me ye shabd kabhi nahi aata)।
        if (!lower.contains("বুঝবে") && !lower.contains("বুঝব") &&
            !lower.contains("bujhbe")
        ) return null
        val hearMarker = DIALECT_HEAR_MARKERS.firstOrNull { lower.contains(it) }
            ?: return null
        val hearAt = lower.indexOf(hearMarker)
        val understandAt = lower.indexOf("বুঝবে", hearAt).let {
            if (it >= 0) it else lower.indexOf("বুঝব", hearAt).let { b ->
                if (b >= 0) b else lower.indexOf("bujhbe", hearAt)
            }
        }
        if (understandAt < 0 || understandAt <= hearAt) return null
        var spoken = text.substring(0, hearAt).trim()
        for (starter in TEACH_STARTERS) {
            if (spoken.lowercase(Locale.ROOT).startsWith(starter)) {
                spoken = spoken.substring(starter.length).trim()
            }
        }
        var command = text.substring(hearAt + hearMarker.length, understandAt).trim()
        for (prefix in COMMAND_PREFIXES) {
            if (command.lowercase(Locale.ROOT).startsWith(prefix)) {
                command = command.substring(prefix.length).trim()
            }
        }
        spoken = stripQuotes(spoken)
        command = stripQuotes(command)
        if (spoken.isEmpty() || command.isEmpty()) return null
        if (spoken.length > 60 || command.length > 120) return null
        if (spoken.split(Regex("\\s+")).size > 5) return null
        if (command.split(Regex("\\s+")).size > 12) return null
        return spoken to command
    }

    /** "না, আমি বলেছি 'X'" → X (iraada)। Sudhaar ka sanket na ho to null। */
    private fun parseCorrection(
        text: String,
        lower: String
    ): String? {
        if (!hasCorrectionCue(lower)) return null
        val marker = CORRECTION_MARKERS.firstOrNull { lower.contains(it) }
            ?: return null
        val at = lower.lastIndexOf(marker) + marker.length
        val intended = stripQuotes(text.substring(at).trim())
        if (intended.isEmpty() || intended.length > 120) return null
        if (intended.split(Regex("\\s+")).size > 12) return null
        return intended
    }

    /** Sudhaar ka sanket — galat-suna sanket ya seedha "আমি বলেছি" se shuru। */
    private fun hasCorrectionCue(lower: String): Boolean {
        if (lower.startsWith("আমি বলেছি") ||
            lower.startsWith("আমি বলেছিলাম") ||
            lower.startsWith("আমি বলছিলাম") ||
            lower.startsWith("আমি বলেছিলে")
        ) return true
        val words = lower
            .split(Regex("[\\s।.,!?,;:—\\-\"'‘’“”()]+"))
            .filter { it.isNotEmpty() }
        if (words.any { it in CORRECTION_CUE_WORDS }) return true
        return CORRECTION_CUE_PHRASES.any { lower.contains(it) }
    }

    /** "ডায়ালেক্ট কী কী শেখানো আছে" / "আমার কথা কী কী শিখেছ" jaisa roop? */
    private fun isDialectList(lower: String): Boolean {
        val mentionsDialect = lower.contains("ডায়ালেক্ট") ||
            lower.contains("dialect") ||
            lower.contains("আমার কথা")
        if (!mentionsDialect) return false
        return lower.contains("কী কী") || lower.contains("কি কি") ||
            lower.contains("তালিকা") || lower.contains("list") ||
            lower.contains("শিখেছ") || lower.contains("শেখানো আছে")
    }

    /** Sirf pukaar-naam (মায়া/তারা) hi tha — pichli baat nahi maano। */
    private fun isWakeOnly(heard: String): Boolean {
        val key = DialectMap.dialectKey(heard)
        return key.isEmpty() || key in setOf("মায়া", "তারা", "maya", "tara")
    }

    private fun stripQuotes(raw: String): String {
        return raw.trim()
            .trim('\'', '"', '‘', '’', '“', '”', ' ', '।', ',', '!', '?', ':', '-', '—')
            .trim()
    }

    /** Bhasha ke hisaab se jawab chuno। */
    private fun reply(
        lang: PlayIntent.SpokenLang,
        bn: String,
        hi: String,
        en: String
    ): String {
        return when (lang) {
            PlayIntent.SpokenLang.HINDI -> hi
            PlayIntent.SpokenLang.ENGLISH -> en
            else -> bn
        }
    }

    // ---------------------------------------------------------------
    // Shabdaavali
    // ---------------------------------------------------------------

    /** Sikhane wali baat se pehle ke shabd — bol-chaal se alag karo। */
    private val TEACH_STARTERS = listOf(
        "শিখে নাও", "শিখে রাখো", "শেখাও", "শিখিয়ে দাও",
        "মনে রেখো", "মনে রাখো", "আমার কথায়", "আমার কথা", "আমি"
    )

    /** Hukm se pehle ke shabd — asal hukm se alag karo। */
    private val COMMAND_PREFIXES = listOf(
        "তুমি", "তুই", "আপনি"
    )

    /** "শুনলে" ke roop — lehja wala "সুনলে" bhi (শ→স wala badal)। */
    private val DIALECT_HEAR_MARKERS = listOf(
        "শুনলে", "শুনলেই", "সুনলে", "বললে", "বললেই"
    )

    /** Sudhaar ka sanket — "আমি বলেছি" se pehle aane wale shabd (poore shabd, tukde nahi)। */
    private val CORRECTION_CUE_WORDS = setOf(
        "না", "নাহ", "ভুল", "নহি", "নहीं", "गलत", "no", "wrong", "not"
    )

    /** Sudhaar ka sanket — poore vakya-ansh। */
    private val CORRECTION_CUE_PHRASES = listOf(
        "ভুল শুনেছ", "ভুল শুনলে", "ভুল শুনছো", "गलत सुना"
    )

    /** Sudhaar me iraade se pehle aane wale shabd। */
    private val CORRECTION_MARKERS = listOf(
        "বলেছি", "বলেছিলাম", "বলছিলাম", "বলেছিলে",
        "कहा था", "कहा है", "बोला था", "बोला है",
        "i said", "i meant"
    )
}

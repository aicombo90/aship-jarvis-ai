package com.aship.jarvis.commands

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.aship.jarvis.actions.LaunchOutcome
import com.aship.jarvis.actions.OverlayLauncher
import java.net.URLEncoder
import java.util.Locale

/**
 * v99: UPI PAYMENT ASSIST — Maya kabhi khud paise NAHI bhejti.
 *
 * Voice: "₹X পাঠাও [name]" / "[name]-কে ₹X পাঠাও" — Maya
 * standard UPI intent banati hai (`upi://pay?pa=...&pn=...
 * &am=...&cu=INR`) aur UPI app khol deti hai, paise + paane
 * wala pehle se bhara hua। Aakhiri kadam (UPI PIN) user
 * khud UPI app ke andar dalta hai — Maya PIN kabhi nahi
 * dekhti, nahi mangti, nahi bachati, nahi log karti। Koi
 * bank khata / card bhi kabhi nahi chhooti।
 *
 * - Paane wala: naam (sikhe hue contacts, v59 — pehle
 *   [UpiPayees] me uski UPI ID, na mile to contact se
 *   naam pehchan kar ID maangi jati hai) ya seedha boli
 *   hui UPI ID ("[upi-id]-তে ₹X পাঠাও")।
 * - Rashi: Bangla / Devanagari ank + shabdon wali rashi
 *   ("পাঁচশো টাকা", "দুই হাজার") — ₹0 ya ₹1,00,000 se
 *   zyada ho to kholne se pehle dohra kar poochti hai
 *   ("হ্যাঁ পাঠাও" se hi khulta hai); rashi na mile to
 *   poochti hai ("কত টাকা পাঠাবো?") aur aadha payment
 *   bandh rakhti hai।
 * - UPI app: system chooser (GPay / PhonePe / Paytm —
 *   user khud chune) ya sikhaya hua default app ("UPI
 *   অ্যাপ হিসেবে [app] ব্যবহার করো")।
 * - Imaandaari: UPI app hi na ho to "nahi mila" — kabhi
 *   jhootha "bhej diya" nahi। Kholna bhi tabhi "khul gaya"
 *   jab intent asal me chala।
 */
object UpiCommands {

    /** Ek baar me itne se zyada — kholne se pehle pakka poochna. */
    const val CONFIRM_ABOVE = 100000.0

    /** UPI me sabse chhoti rashi — isse kam "rashi hi nahi". */
    const val MIN_AMOUNT = 1.0

    /** UPI app kholne ka natija — imaandaar jawab ke liye. */
    enum class OpenOutcome {
        /** UPI app khul gaya (chooser bhi). */
        OPENED,

        /** Phone me koi UPI app hi nahi hai. */
        NO_APP,

        /** Background se kholne ke liye overlay anumati chahiye. */
        PERMISSION_NEEDED,

        /** Phone lock hai. */
        LOCKED,

        /** Kholne ki koshish hui, par chala nahi. */
        FAILED
    }

    // ------------------------------------------------------------------
    // UPI ID
    // ------------------------------------------------------------------

    /**
     * UPI ID ka roop: "naam@bank" — ek "@", dono taraf
     * kuch ho, sirf a-z 0-9 . _ - + @, domain me kam se
     * kam ek akshar ho (sirf ank wala domain UPI nahi)।
     */
    fun isValidUpiId(id: String): Boolean {
        val clean = id.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean.contains(" ")) return false
        if (clean.count { it == '@' } != 1) return false
        val local = clean.substringBefore('@')
        val domain = clean.substringAfter('@')
        if (local.isEmpty() || domain.isEmpty()) return false
        if (local.length > 64 || domain.length > 64) return false
        if (!local.all { it.isLetterOrDigit() || it in "._-+" }) return false
        if (!domain.all { it.isLetterOrDigit() || it in ".-" }) return false
        if (!domain.any { it.isLetter() }) return false
        return true
    }

    /**
     * Bolkar suni UPI ID ko likhne wale roop me lao —
     * "rahul at okhdfcbank" -> "rahul@okhdfcbank",
     * "rahul dot com" jaisi "dot" bhi "." banti hai।
     * Poora vakya de do, saaf vakya milega।
     */
    fun normalizeSpokenUpiId(text: String): String {
        var t = text.lowercase(Locale.ROOT)
        t = t
            .replace(" at ", "@")
            .replace(" অ্যাট ", "@")
            .replace(" एट ", "@")
            .replace(" dot ", ".")
            .replace(" ডট ", ".")
            .replace(" डॉट ", ".")
        return t
    }

    /**
     * Vakya me boli hui UPI ID nikaalo — "@" wala pehla
     * token jo UPI ID jaisa ho। Bolkar sune "paytm-তে"
     * me "-তে" ID ka hissa nahi — vibhakti hatakar bhi
     * aazmao। Na mile to null।
     */
    fun extractUpiId(rawText: String): String? {
        val text = normalizeSpokenUpiId(rawText)
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() }
        for (token in tokens) {
            if (!token.contains("@")) continue
            UpiPayees.cleanUpiId(token)?.let { return it }
            stripPostposition(token)?.let { stripped ->
                UpiPayees.cleanUpiId(stripped)?.let { return it }
            }
        }
        return null
    }

    /**
     * Token ke sheshe lagi Bangla / Hindi vibhakti
     * ("-তে", "-কে", "-এ") hatao — "9876543210@paytm-তে"
     * bolne par "-তে" ID ka hissa nahi hota। Na hate to null।
     */
    private fun stripPostposition(token: String): String? {
        val suffixes = listOf(
            "-তে", "-কে", "-এ", "-টা", "-টি", "-র", "-এর",
            "-को", "-में", "-से", "-पर", "-का"
        )
        for (suffix in suffixes) {
            if (token.endsWith(suffix)) {
                return token.dropLast(suffix.length)
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Rashi (amount)
    // ------------------------------------------------------------------

    /** Bangla / Devanagari ank -> Latin ank. */
    fun normalizeDigits(text: String): String =
        text.map { ch ->
            when (ch) {
                '০', '०' -> '0'; '১', '१' -> '1'; '২', '२' -> '2'
                '৩', '३' -> '3'; '৪', '४' -> '4'; '৫', '५' -> '5'
                '৬', '६' -> '6'; '৭', '७' -> '7'; '৮', '८' -> '8'
                '৯', '९' -> '9'
                else -> ch
            }
        }.joinToString("")

    /** Latin ank -> Bangla ank (Bangla jawab ke liye)। */
    fun toBnDigits(s: String): String =
        s.map { ch ->
            when (ch) {
                '0' -> '০'; '1' -> '১'; '2' -> '২'; '3' -> '৩'; '4' -> '৪'
                '5' -> '৫'; '6' -> '৬'; '7' -> '৭'; '8' -> '৮'; '9' -> '৯'
                else -> ch
            }
        }.joinToString("")

    /**
     * Vakya se rashi (rupaye) nikaalo — pehle ank wale
     * roop ("৫০০", "500", "1,500", "250.50"), phir
     * shabdon wale roop ("পাঁচশো", "দুই হাজার")।
     * Phone number jaisi lambi ank-mala (8+ ank) rashi
     * nahi — woh chhoot jati hai। Na mile to null।
     */
    fun parseAmount(rawText: String): Double? {
        val text = normalizeDigits(rawText.lowercase(Locale.ROOT))
        // 1) Ank wale roop — paise wale shabd ke paas wala
        //    pehle, warna pehla munasib ank।
        val moneyWords = setOf(
            "টাকা", "taka", "रुपये", "रुपए", "रुपया",
            "rupee", "rupees", "rs", "inr", "₹"
        )
        val tokens = text.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?', '"', '\'') }
            .filter { it.isNotEmpty() }
        val numberRegex = Regex("""^(\d[\d,]{0,6}(?:\.\d{1,2})?)$""")
        var firstNumber: Double? = null
        for (i in tokens.indices) {
            val m = numberRegex.matchEntire(tokens[i]) ?: continue
            val digitsOnly = m.groupValues[1].replace(",", "").substringBefore('.')
            if (digitsOnly.length > 7) continue
            val value = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: continue
            if (firstNumber == null) firstNumber = value
            val prevIsMoney = i > 0 && tokens[i - 1] in moneyWords
            val nextIsMoney = i + 1 < tokens.size && tokens[i + 1] in moneyWords
            if (prevIsMoney || nextIsMoney) return value
        }
        // 2) Shabdon wale roop — "পাঁচশো", "দুই হাজার", "এক লাখ"।
        val wordAmount = parseWordAmount(tokens)
        if (wordAmount != null) return wordAmount
        return firstNumber
    }

    /** Shabdon wali rashi — fused roop + gunak (হাজার / লাখ / শো)। */
    private fun parseWordAmount(tokens: List<String>): Double? {
        for (i in tokens.indices) {
            val t = tokens[i]
            // Fused roop: "পাঁচশো" = 500, "আড়াই হাজার" = 2500।
            FUSED_AMOUNTS[t]?.let { return it }
            // "আড়াই হাজার" / "দেড় হাজার" — aadha gunak + gunak।
            HALF_AMOUNTS[t]?.let { half ->
                val mult = tokens.getOrNull(i + 1)?.let { MULTIPLIERS[it] }
                if (mult != null) return half * mult
                return half
            }
            // Ank-shabd + gunak: "দুই হাজার" = 2000, "পাঁচ শো" = 500।
            val base = NUMBER_WORDS[t]
            if (base != null) {
                val next = tokens.getOrNull(i + 1)
                val mult = next?.let { MULTIPLIERS[it] }
                if (mult != null) return base * mult
                // "একশো" jaisa akela ank-shabd rashi nahi —
                // "পাঁচ টাকা" me "পাঁচ" akela hi rashi hai।
                if (next != null && next in MONEY_WORDS) return base.toDouble()
            }
        }
        return null
    }

    private val MONEY_WORDS = setOf(
        "টাকা", "taka", "रुपये", "रुपए", "रुपया",
        "rupee", "rupees", "rs", "inr", "₹"
    )

    /** Fused roop — ek hi token me poori rashi। */
    private val FUSED_AMOUNTS = mapOf(
        "একশো" to 100.0, "একশ" to 100.0,
        "দুইশো" to 200.0, "দুইশ" to 200.0,
        "তিনশো" to 300.0, "তিনশ" to 300.0,
        "চারশো" to 400.0, "চারশ" to 400.0,
        "পাঁচশো" to 500.0, "পাঁচশ" to 500.0,
        "ছয়শো" to 600.0, "ছয়শ" to 600.0,
        "সাতশো" to 700.0, "সাতশ" to 700.0,
        "আটশো" to 800.0, "আটশ" to 800.0,
        "নয়শো" to 900.0, "নয়শ" to 900.0,
        "सौ" to 100.0
    )

    /** Aadha roop — gunak ke saath judta hai। */
    private val HALF_AMOUNTS = mapOf(
        "আড়াই" to 2.5, "দেড়" to 1.5,
        "ढाई" to 2.5, "डेढ़" to 1.5
    )

    /** Gunak — pehle wala ank-shabd isse guna hota hai। */
    private val MULTIPLIERS = mapOf(
        "হাজার" to 1000.0, "हज़ार" to 1000.0, "हजार" to 1000.0,
        "thousand" to 1000.0,
        "লাখ" to 100000.0, "लाख" to 100000.0, "lakh" to 100000.0,
        "শো" to 100.0, "শত" to 100.0, "सौ" to 100.0,
        "hundred" to 100.0
    )

    /** Ank wale shabd — Bangla / Hindi / English। */
    private val NUMBER_WORDS = mapOf(
        "এক" to 1.0, "দুই" to 2.0, "তিন" to 3.0, "চার" to 4.0,
        "পাঁচ" to 5.0, "ছয়" to 6.0, "সাত" to 7.0, "আট" to 8.0,
        "নয়" to 9.0, "দশ" to 10.0, "এগারো" to 11.0, "বারো" to 12.0,
        "পনেরো" to 15.0, "বিশ" to 20.0, "পঁচিশ" to 25.0,
        "তিরিশ" to 30.0, "চল্লিশ" to 40.0, "পঞ্চাশ" to 50.0,
        "ষাট" to 60.0, "সত্তর" to 70.0, "আশি" to 80.0, "নব্বই" to 90.0,
        "एक" to 1.0, "दो" to 2.0, "तीन" to 3.0, "चार" to 4.0,
        "पाँच" to 5.0, "छह" to 6.0, "सात" to 7.0, "आठ" to 8.0,
        "नौ" to 9.0, "दस" to 10.0, "पंद्रह" to 15.0, "बीस" to 20.0,
        "पच्चीस" to 25.0, "तीस" to 30.0, "चालीस" to 40.0,
        "पचास" to 50.0, "साठ" to 60.0, "सत्तर" to 70.0,
        "अस्सी" to 80.0, "नब्बे" to 90.0,
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0,
        "five" to 5.0, "six" to 6.0, "seven" to 7.0, "eight" to 8.0,
        "nine" to 9.0, "ten" to 10.0, "fifteen" to 15.0,
        "twenty" to 20.0, "thirty" to 30.0, "forty" to 40.0,
        "fifty" to 50.0
    )

    /**
     * Rashi wale saare shabd — naam nikalte waqt hatane ke
     * liye (CommandEngine ka drop set)। Ank-shabd + gunak +
     * fused roop + aadha roop + paise wale shabd।
     */
    val AMOUNT_TOKENS: Set<String> =
        NUMBER_WORDS.keys + MULTIPLIERS.keys + FUSED_AMOUNTS.keys +
            HALF_AMOUNTS.keys + MONEY_WORDS

    /** Rashi UPI URI wale roop me — "500" ya "500.50"। */
    fun formatAmountUri(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            "%.2f".format(Locale.US, amount)
        }
    }

    /** Rashi bolne wale roop me — bhasha ke hisab se ank। */
    fun formatAmountSpoken(amount: Double, lang: PlayIntent.SpokenLang): String {
        val plain = formatAmountUri(amount)
        return when (lang) {
            PlayIntent.SpokenLang.BENGALI -> "₹${toBnDigits(plain)}"
            PlayIntent.SpokenLang.HINDI -> "₹$plain"
            PlayIntent.SpokenLang.ENGLISH -> "₹$plain"
        }
    }

    // ------------------------------------------------------------------
    // UPI intent + app kholna
    // ------------------------------------------------------------------

    /** Standard UPI intent URI — sirf paise + paane wala + mudra। */
    fun buildUpiUri(payeeUpiId: String, payeeName: String, amount: Double): String {
        val pa = URLEncoder.encode(payeeUpiId, "UTF-8")
        val pn = URLEncoder.encode(payeeName, "UTF-8")
        val am = formatAmountUri(amount)
        return "upi://pay?pa=$pa&pn=$pn&am=$am&cu=INR"
    }

    /**
     * UPI app kholo — default app sikhaya ho to seedha wohi,
     * warna system chooser (GPay / PhonePe / Paytm — user
     * khud chune)। Background service se ho to overlay
     * bridge (v29 wala hi niyam)। Koi UPI app na ho to
     * NO_APP — kabhi jhootha "khul gaya" nahi।
     */
    fun openUpiApp(
        context: Context,
        upiUri: String,
        fromBackgroundService: Boolean,
        defaultAppName: String?
    ): OpenOutcome {
        val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(upiUri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Pehle jaancho — koi UPI app hai hi kya (chooser
        // khud hamesha "mil jata" hai, isliye usse pehle
        // asal intent par jaanch zaroori hai)।
        val hasHandler = try {
            baseIntent.resolveActivity(context.packageManager) != null
        } catch (e: Exception) {
            false
        }
        if (!hasHandler) return OpenOutcome.NO_APP

        var intent = baseIntent
        if (!defaultAppName.isNullOrBlank()) {
            // Sikhaya hua default app — label se milao, mile
            // to seedha usi ka package, na mile to chooser।
            val pkg = findUpiAppPackage(context, baseIntent, defaultAppName)
            if (pkg != null) {
                intent = Intent(baseIntent).apply { setPackage(pkg) }
            }
        }
        if (intent.`package` == null) {
            intent = Intent.createChooser(intent, "UPI অ্যাপ বেছে নাও").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (fromBackgroundService) {
            return when (OverlayLauncher.launch(context, intent)) {
                LaunchOutcome.LAUNCHED -> OpenOutcome.OPENED
                LaunchOutcome.PERMISSION_NEEDED -> OpenOutcome.PERMISSION_NEEDED
                LaunchOutcome.LOCKED -> OpenOutcome.LOCKED
                LaunchOutcome.NOT_AVAILABLE, LaunchOutcome.FAILED -> OpenOutcome.FAILED
            }
        }
        return try {
            context.startActivity(intent)
            OpenOutcome.OPENED
        } catch (e: Exception) {
            OpenOutcome.FAILED
        }
    }

    /** UPI intent sambhalne wale apps me se naam wala package dhoondho। */
    private fun findUpiAppPackage(
        context: Context,
        intent: Intent,
        appName: String
    ): String? {
        return try {
            val wanted = appName.lowercase(Locale.ROOT).trim()
            val matches = context.packageManager.queryIntentActivities(intent, 0)
            matches.firstOrNull { info ->
                val label = try {
                    info.loadLabel(context.packageManager)?.toString()
                        ?.lowercase(Locale.ROOT).orEmpty()
                } catch (e: Exception) {
                    ""
                }
                label.contains(wanted) || wanted.contains(label)
            }?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Contact se naam pehchanna (v59 wala raasta, chhota roop)
    // ------------------------------------------------------------------

    /**
     * Naam se contact dhoondho — pehle sikhe hue contacts
     * (PhoneLearner), na mile to live ContactsProvider।
     * Milan: exact -> shuru -> andar -> kareeb-shabd।
     * Permission na ho to null (bulane wala sach bolega)।
     */
    fun findContact(context: Context, name: String): PhoneLearner.LearnedContact? {
        val learned = try {
            PhoneLearner.readContacts(context)
        } catch (t: Throwable) {
            emptyList()
        }
        matchContacts(learned, name).firstOrNull()?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val live = try {
            PhoneLearner.scanContacts(context)
        } catch (t: Throwable) {
            emptyList()
        }
        return matchContacts(live, name).firstOrNull()
    }

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
        val allowed = when {
            queryNorm.length >= 7 -> 3
            queryNorm.length >= 5 -> 2
            else -> 1
        }
        return contacts.filter { c ->
            val norm = normalizeName(c.name)
            levenshtein(norm, queryNorm) in 1..allowed ||
                levenshtein(squashName(norm), querySquash) in 1..allowed
        }
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
        raw.lowercase(Locale.ROOT).trim()
            .replace(Regex("""\s+"""), " ")

    private fun squashName(normalized: String): String =
        normalized.replace(" ", "")
}

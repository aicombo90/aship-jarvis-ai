package com.aship.jarvis.commands

import android.content.Context
import android.util.Base64

/**
 * v101: SMART CLIPBOARD MANAGER — copy ki hui likhai
 * ka bhandar (FavSites wala hi dhang: prefs me ek
 * line — ek entry, zyada se zyada [MAX_ENTRIES])।
 *
 * - Har nayi copy (watcher se) yahan aati hai — taaza
 *   sabse upar; wahi likhai dobara copy ho to upar
 *   aa jati hai, duplicate nahi banta।
 * - OTP / password jaisi likhai KABHI nahi bachti
 *   ([isSensitive]) — ye pakka wada hai, UI me bhi
 *   likha hai ("OTP/পাসওয়ার্ডের মতো লেখা সেভ হয় না")।
 * - Shreni (category) khud lagti hai ([autoCategory]):
 *   link / phone / UPI / address / code / text — user
 *   chahe to apni shreni sikha sakta hai (userCategory
 *   auto wali par jeetti hai)।
 * - Sab kuch sirf phone me hai (local prefs) — koi
 *   upload / cloud nahi।
 *
 * Line ka roop: base64(text)|category|timeMillis|
 * userCategory — text me newline / '|' ho bhi to
 * base64 me woh kabhi nahi aata, isliye parse pakka hai।
 */
object ClipboardStore {

    /** Ek user kitni copy ki hui likhai rakh sakta hai. */
    const val MAX_ENTRIES = 100

    /** "হ্যাঁ" se saaf karne ki mohlat — itni der baad pending saaf khatm. */
    const val PENDING_CLEAR_FRESH_MS = 10 * 60 * 1000L

    private const val PREFS_NAME = "jarvis_clipboard"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_PENDING_CLEAR_AT = "pending_clear_at"

    /** Shreniyan — andar English key, bolne/dikhane par bhasha badalti hai. */
    const val CAT_LINK = "link"
    const val CAT_PHONE = "phone"
    const val CAT_UPI = "upi"
    const val CAT_ADDRESS = "address"
    const val CAT_CODE = "code"
    const val CAT_TEXT = "text"

    /** Saari shreniyan — chhaantne (filter) wale dialog ke liye. */
    val ALL_CATEGORIES: List<String> = listOf(
        CAT_LINK, CAT_PHONE, CAT_UPI, CAT_ADDRESS, CAT_CODE, CAT_TEXT
    )

    /** Ek bachi hui copy — likhai + shreni + samay (+ user ki shreni). */
    data class ClipboardEntry(
        val text: String,
        val category: String,
        val timeMillis: Long,
        val userCategory: String? = null
    ) {
        /** Asli shreni — user ki sikhayi hui ho to wahi, warna auto wali. */
        fun effectiveCategory(): String = userCategory ?: category
    }

    /** Bachane par kya hua. */
    enum class SaveOutcome {
        /** Nayi likhai sabse upar aa gayi. */
        SAVED,

        /** Wahi likhai pehle se thi — upar le aaye (duplicate nahi). */
        MOVED_TO_TOP,

        /** OTP / password jaisi likhai — bachayi hi nahi. */
        SKIPPED_SENSITIVE,

        /** Khaali likhai — bachane layak kuch nahi. */
        EMPTY
    }

    private val UPI_LIKE_REGEX = Regex("^[A-Za-z0-9._+\\-]{2,}@[A-Za-z]{2,}$")

    private val ADDRESS_WORDS = listOf(
        "road", "রোড", "street", "lane", "গলি", "পাড়া", "para",
        "গ্রাম", "gram", "নগর", "nagar", "বাজার", "bazar",
        "pin", "পিন", "থানা", "thana", "জেলা", "district",
        "বাড়ি", "house", "colony", "কলোনি", "village", "post", "পোস্ট"
    )

    /**
     * Likhai ko shreni do — link / UPI / phone / address /
     * code / text, is kram me (pehli milne wali jeetti hai)।
     */
    fun autoCategory(raw: String): String {
        val text = raw.trim()
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("www.")
        ) {
            return CAT_LINK
        }
        if (UPI_LIKE_REGEX.matches(text)) return CAT_UPI
        val stripped = UpiCommands.normalizeDigits(text)
            .replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
            .removePrefix("+")
        if (stripped.isNotEmpty() && stripped.all { it.isDigit() } &&
            stripped.length in 10..13
        ) {
            return CAT_PHONE
        }
        if (isAddressLike(text)) return CAT_ADDRESS
        if (text.length in 4..64 && text.none { it.isWhitespace() }) {
            val hasDigit = text.any { it.isDigit() }
            val hasLetter = text.any { it.isLetter() }
            if (hasDigit && (hasLetter || text.length >= 9)) return CAT_CODE
        }
        return CAT_TEXT
    }

    /** Pata jaisi likhai — lambi + 6 ank ka PIN + pata wala shabd. */
    private fun isAddressLike(text: String): Boolean {
        if (text.length < 25) return false
        val lower = text.lowercase()
        if (!ADDRESS_WORDS.any { lower.contains(it) }) return false
        return Regex("\\d{6}").containsMatchIn(UpiCommands.normalizeDigits(text))
    }

    /**
     * OTP / password jaisi likhai hai kya — aisi likhai
     * KABHI nahi bachti (best effort):
     * - sirf 4-8 ank — akele khada code (OTP);
     * - "otp"/"ওটিপি" shabd + ank;
     * - ek tukda (8-32 akshar, na jagah na '@' na link),
     *   upar+neeche+ank, ya ank+chinh (password jaisa)।
     * UPI ID ('@' wali), link aur phone number isse
     * kabhi nahi pakde jate — woh alag shreni hain।
     */
    fun isSensitive(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        val ascii = UpiCommands.normalizeDigits(text)
        if (ascii.length in 4..8 && ascii.all { it.isDigit() }) return true
        val lower = text.lowercase()
        if ((lower.contains("otp") || lower.contains("ওটিপি")) &&
            ascii.any { it.isDigit() }
        ) {
            return true
        }
        if (text.length in 8..32 && text.none { it.isWhitespace() } &&
            !text.contains("@") && !lower.startsWith("http") &&
            !lower.startsWith("www.")
        ) {
            val hasUpper = text.any { it.isUpperCase() }
            val hasLower = text.any { it.isLowerCase() }
            val hasDigit = text.any { it.isDigit() }
            val hasSymbol = text.any { !it.isLetterOrDigit() }
            if ((hasUpper && hasLower && hasDigit) ||
                (hasDigit && hasSymbol && (hasUpper || hasLower))
            ) {
                return true
            }
        }
        return false
    }

    private fun encode(entry: ClipboardEntry): String {
        val b64 = Base64.encodeToString(
            entry.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return listOf(
            b64, entry.category, entry.timeMillis.toString(),
            entry.userCategory ?: ""
        ).joinToString("|")
    }

    private fun decode(line: String): ClipboardEntry? {
        return try {
            val parts = line.split("|")
            if (parts.size < 3) return null
            val text = String(
                Base64.decode(parts[0], Base64.DEFAULT), Charsets.UTF_8
            )
            val category = parts[1].ifEmpty { CAT_TEXT }
            val time = parts[2].toLongOrNull() ?: return null
            val userCategory = parts.getOrNull(3)?.takeIf { it.isNotEmpty() }
            ClipboardEntry(text, category, time, userCategory)
        } catch (t: Throwable) {
            null
        }
    }

    /** Poori suchi — taaza sabse pehle (rakhne ke kram me). */
    fun loadAll(context: Context): List<ClipboardEntry> {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ENTRIES, null) ?: return emptyList()
            raw.split("\n").mapNotNull { line ->
                val cut = line.trim()
                if (cut.isEmpty()) null else decode(cut)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun storeAll(context: Context, entries: List<ClipboardEntry>) {
        try {
            val raw = entries.joinToString("\n") { encode(it) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, raw)
                .apply()
        } catch (t: Throwable) {
            // Bachana bonus hai — hukm isse nahi rukta.
        }
    }

    /** Copy ki hui likhai bachao — taaza sabse upar, duplicate nahi. */
    fun save(context: Context, rawText: String): SaveOutcome {
        val text = rawText.trim()
        if (text.isEmpty()) return SaveOutcome.EMPTY
        if (isSensitive(text)) return SaveOutcome.SKIPPED_SENSITIVE
        val entries = loadAll(context).toMutableList()
        val existing = entries.indexOfFirst { it.text == text }
        if (existing >= 0) {
            val old = entries.removeAt(existing)
            entries.add(0, old.copy(timeMillis = System.currentTimeMillis()))
            storeAll(context, entries)
            return SaveOutcome.MOVED_TO_TOP
        }
        entries.add(
            0,
            ClipboardEntry(text, autoCategory(text), System.currentTimeMillis())
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
        storeAll(context, entries)
        return SaveOutcome.SAVED
    }

    /** Kitni likhai bachi hai. */
    fun count(context: Context): Int = loadAll(context).size

    /** Shreni ke hisab se ginati — card/dialog ke liye. */
    fun countByCategory(context: Context): Map<String, Int> =
        loadAll(context).groupingBy { it.effectiveCategory() }.eachCount()

    /** Suchi me is entry ki jagah (likhai + samay se pehchan). */
    fun indexOf(context: Context, entry: ClipboardEntry): Int =
        loadAll(context).indexOfFirst {
            it.text == entry.text && it.timeMillis == entry.timeMillis
        }

    /** Nambar se entry hatao — mili to true. */
    fun removeAt(context: Context, index: Int): Boolean {
        val entries = loadAll(context).toMutableList()
        if (index < 0 || index >= entries.size) return false
        entries.removeAt(index)
        storeAll(context, entries)
        return true
    }

    /** Entry ki shreni user ki sikhayi hui se badlo. */
    fun setUserCategory(context: Context, index: Int, category: String): Boolean {
        if (category !in ALL_CATEGORIES) return false
        val entries = loadAll(context).toMutableList()
        if (index < 0 || index >= entries.size) return false
        entries[index] = entries[index].copy(userCategory = category)
        storeAll(context, entries)
        return true
    }

    /** Entry ki likhai badlo (shreni dobara auto — user ki ho to wahi rahe). */
    fun updateText(context: Context, index: Int, newText: String): Boolean {
        val text = newText.trim()
        if (text.isEmpty()) return false
        val entries = loadAll(context).toMutableList()
        if (index < 0 || index >= entries.size) return false
        val old = entries[index]
        entries[index] = old.copy(
            text = text,
            category = autoCategory(text),
            timeMillis = System.currentTimeMillis()
        )
        storeAll(context, entries)
        return true
    }

    /** Saari likhai saaf karo. */
    fun clear(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENTRIES, "")
                .apply()
        } catch (t: Throwable) {
            // Saaf karna bonus hai — hukm isse nahi rukta.
        }
    }

    /** "খালি করো" ki darkhwast — "হ্যাঁ" ka intezaar shuru. */
    fun requestClear(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_PENDING_CLEAR_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Pending bonus hai — hukm isse nahi rukta.
        }
    }

    /** "হ্যাঁ" abhi bhi maani jayegi kya (10 minute ke andar)? */
    fun isPendingClearFresh(context: Context): Boolean {
        return try {
            val at = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_PENDING_CLEAR_AT, 0L)
            at > 0L && System.currentTimeMillis() - at < PENDING_CLEAR_FRESH_MS
        } catch (t: Throwable) {
            false
        }
    }

    /** Pending saaf ki darkhwast hatao. */
    fun clearPending(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING_CLEAR_AT)
                .apply()
        } catch (t: Throwable) {
            // Pending bonus hai — hukm isse nahi rukta.
        }
    }
}

package com.aship.jarvis.commands

import android.content.Context

/**
 * v99: UPI PAYEES — kisko paise bhejne hain, uski UPI ID.
 *
 * Maya kabhi khud paise NAHI bhejti (security) — sirf UPI
 * app kholti hai, paise + paane wala pehle se bhara hua,
 * aakhiri kadam (PIN) user khud UPI app me dalta hai।
 * Isliye yahan sirf naam -> UPI ID bandhta hai (phone me
 * hi, [PREFS_NAME], zyada se zyada [MAX_PAYEES] log) —
 * koi PIN, bank khata, card kabhi NAHI bandhta, kabhi
 * NAHI bola jata, kabhi NAHI log hota।
 *
 * - SIKHAO: "UPI ID শেখাও রাহুল rahul@okhdfcbank" (naam +
 *   ID dono bole) — ID galat roop ki ho to mana (bina "@"
 *   ke, jagah wali)।
 * - KHOLO: "[naam]-কে ₹X পাঠাও" — naam yahan mile to uski
 *   UPI ID se UPI intent banta hai; na mile to Maya ID
 *   maangti hai (pehle contact se naam pehchan kar)।
 * - HATAO: "UPI ID মুছে ফেলো [naam]"।
 * - BOLO: "UPI তালিকা বলো" — naam gin kar sunata hai।
 *
 * Ek hi file me teeno bandhan: payees (naam -> UPI ID),
 * default UPI app ka naam (chooser ki bajay seedha wohi
 * app khole — user ne sikhaya ho to), aur aadha reh gaya
 * payment (pending: paise ya naam baad me mile to use
 * poora karke khola ja sake)।
 */
object UpiPayees {

    /** Ek user kitne logon ki UPI ID rakh sakta hai. */
    const val MAX_PAYEES = 50

    private const val PREFS_NAME = "jarvis_upi_payees"
    private const val KEY_PAYEES = "payees"
    private const val KEY_DEFAULT_APP = "default_app"
    private const val KEY_PENDING = "pending"

    /** Bacha hua natija — sikhane par kya hua. */
    enum class SaveOutcome {
        /** Naya payee suchi me aa gaya. */
        SAVED,

        /** Naam pehle se tha — UPI ID badal di. */
        UPDATED,

        /** UPI ID UPI ID hi nahi lagi (bina "@" / khaali). */
        INVALID,

        /** Suchi bhari hui hai (50) — pehle kuch hatao. */
        FULL
    }

    /** Aadha reh gaya payment — paise / naam me se ek baad me mila. */
    data class PendingPayment(
        /** UPI ID (pa) — ho to; na ho to naam se dhoondhna hai. */
        val upiId: String?,
        /** Dikhane wala naam — ho to. */
        val displayName: String?,
        /** Rashi (rupaye) — ho to; na ho to poochni hai. */
        val amount: Double?,
        /** Kab bandha (epoch millis) — purana pending dobara istemal nahi hota. */
        val savedAt: Long = 0L
    ) {
        /** Kuch to bacha ho — warna pending hi nahi. */
        val hasAny: Boolean get() = upiId != null || displayName != null || amount != null

        /** UPI app kholne layak — paise + paane wala dono hon. */
        val isComplete: Boolean get() = !upiId.isNullOrEmpty() && amount != null

        /** Taaza hai kya — 10 minute ke andar bandha ho to hi poora karo. */
        val isFresh: Boolean
            get() = savedAt > 0 &&
                System.currentTimeMillis() - savedAt <= 10 * 60 * 1000L
    }

    /** Naam saaf karo — khoj hamesha isi roop se milti hai. */
    private fun cleanName(name: String): String =
        name.trim().lowercase()

    /**
     * UPI ID saaf karo — bolkar suni "rahul at okhdfcbank"
     * wali shakal bhi yahin theek hoti hai (" at " -> "@",
     * " dot " -> ".")। Saaf roop: chhote akshar, bina jagah,
     * ek "@", dono taraf kuch ho ("naam@bank")।
     */
    fun cleanUpiId(raw: String): String? {
        var id = raw.trim().lowercase()
        if (id.isEmpty()) return null
        id = id
            .replace(" at ", "@")
            .replace(" অ্যাট ", "@")
            .replace(" एट ", "@")
            .replace(" dot ", ".")
            .replace(" ডট ", ".")
            .replace(" डॉट ", ".")
            .replace(" ", "")
        if (!UpiCommands.isValidUpiId(id)) return null
        return id
    }

    /** Poori suchi — dikhane wala naam -> UPI ID (rakhne ke kram me)। */
    fun loadAll(context: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PAYEES, null) ?: return out
            for (line in raw.split("\n")) {
                val cut = line.trim()
                if (cut.isEmpty()) continue
                val bar = cut.indexOf('|')
                if (bar <= 0) continue
                val name = cut.substring(0, bar).trim()
                val upiId = cut.substring(bar + 1).trim()
                if (name.isNotEmpty() && upiId.isNotEmpty()) {
                    out[name] = upiId
                }
            }
        } catch (t: Throwable) {
            // Suchi na padh paye to khaali mano — hukm isse nahi rukta.
        }
        return out
    }

    private fun storeAll(context: Context, payees: Map<String, String>) {
        try {
            val raw = payees.entries.joinToString("\n") { (name, upiId) ->
                "$name|$upiId"
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PAYEES, raw)
                .apply()
        } catch (t: Throwable) {
            // Bachana bonus hai — hukm isse nahi rukta.
        }
    }

    /** Payee sikhao (naam + UPI ID)। Pehle se naam ho to ID badalti hai। */
    fun save(context: Context, name: String, upiIdRaw: String): SaveOutcome {
        val clean = cleanName(name)
        if (clean.isEmpty()) return SaveOutcome.INVALID
        val upiId = cleanUpiId(upiIdRaw) ?: return SaveOutcome.INVALID
        val payees = loadAll(context)
        val existingKey = payees.keys.firstOrNull { cleanName(it) == clean }
        if (existingKey == null && payees.size >= MAX_PAYEES) {
            return SaveOutcome.FULL
        }
        if (existingKey != null) {
            payees.remove(existingKey)
        }
        payees[name.trim()] = upiId
        storeAll(context, payees)
        return if (existingKey != null) SaveOutcome.UPDATED else SaveOutcome.SAVED
    }

    /**
     * Naam se payee dhoondho — pehle poora naam, phir
     * hissa (na ya naam ka hissa)। Na mile to null।
     */
    fun find(context: Context, name: String): Pair<String, String>? {
        val clean = cleanName(name)
        if (clean.isEmpty()) return null
        val payees = loadAll(context)
        for ((storedName, upiId) in payees) {
            if (cleanName(storedName) == clean) {
                return storedName to upiId
            }
        }
        for ((storedName, upiId) in payees) {
            if (cleanName(storedName).contains(clean) || clean.contains(cleanName(storedName))) {
                return storedName to upiId
            }
        }
        return null
    }

    /** Naam se payee hatao — mili to true। */
    fun remove(context: Context, name: String): Boolean {
        val clean = cleanName(name)
        if (clean.isEmpty()) return false
        val payees = loadAll(context)
        val key = payees.keys.firstOrNull {
            cleanName(it) == clean || cleanName(it).contains(clean)
        } ?: return false
        payees.remove(key)
        storeAll(context, payees)
        return true
    }

    /** Sirf naam — bolkar sunane ke liye, rakhne ke kram me। */
    fun listNames(context: Context): List<String> = loadAll(context).keys.toList()

    // ------------------------------------------------------------------
    // Default UPI app (chooser ki bajay seedha wohi app)
    // ------------------------------------------------------------------

    /** User ne sikhaya ho to us UPI app ka naam, warna null (chooser khulega)। */
    fun getDefaultApp(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEFAULT_APP, null)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    fun setDefaultApp(context: Context, appName: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEFAULT_APP, appName.trim())
                .apply()
        } catch (t: Throwable) {
            // Bachana bonus hai — hukm isse nahi rukta.
        }
    }

    // ------------------------------------------------------------------
    // Aadha reh gaya payment (pending)
    // ------------------------------------------------------------------

    /** Aadha payment bandho — paise / naam me se jo mila ho. */
    fun savePending(context: Context, pending: PendingPayment) {
        try {
            val raw = listOf(
                pending.upiId.orEmpty(),
                pending.displayName.orEmpty(),
                pending.amount?.toString().orEmpty(),
                System.currentTimeMillis().toString()
            ).joinToString("|")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING, raw)
                .apply()
        } catch (t: Throwable) {
            // Bachana bonus hai — hukm isse nahi rukta.
        }
    }

    /** Bandha aadha payment lo — kuch na ho to null. */
    fun loadPending(context: Context): PendingPayment? {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PENDING, null) ?: return null
            val parts = raw.split("|")
            if (parts.size < 3) return null
            val upiId = parts[0].trim().takeIf { it.isNotEmpty() }
            val displayName = parts[1].trim().takeIf { it.isNotEmpty() }
            val amount = parts[2].trim().toDoubleOrNull()
            val savedAt = parts.getOrNull(3)?.trim()?.toLongOrNull() ?: 0L
            val pending = PendingPayment(upiId, displayName, amount, savedAt)
            if (!pending.hasAny) null else pending
        } catch (t: Throwable) {
            null
        }
    }

    /** Aadha payment saaf karo (khul gaya / bhool gaya / radd hua)। */
    fun clearPending(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING)
                .apply()
        } catch (t: Throwable) {
            // Saaf karna bonus hai — hukm isse nahi rukta.
        }
    }
}

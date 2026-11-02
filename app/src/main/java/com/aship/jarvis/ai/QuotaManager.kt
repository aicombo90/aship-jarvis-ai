package com.aship.jarvis.ai

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import java.time.ZoneId

/**
 * v43: Gemini kota ka hisaab-rakhwala (Quota Manager).
 *
 * Free Gemini seema is app ki sabse tang jagah hai —
 * din bhar ke agent/screen kadam usi se chalte hain.
 * Ye class sirf APP KI APNI GINATI rakhti hai: aaj
 * (Pacific tareekh ke hisaab se — Google ka free
 * RPD aadhi-raat Pacific par reset hota hai) kis model
 * par kitni koshish hui, kaun sa model aaj 429 dekar
 * "aaj ke liye khatm" ho gaya, aur kaun sa (Lite) model
 * is project ke liye hai hi nahi (404) — taaki
 * [GeminiBrain] ki kataar unhe dobara thokar na mare.
 *
 * Ye ginati Google ka official bacha-hua kota NAHI
 * hai aur kahin aise pesh bhi nahi hoti — ye sirf is
 * phone par is app ne khud jo koshishen ki, unka
 * register hai. Asli seema Google ke paas hai; ye
 * hisaab sirf raasta chunne (routing) aur screen par
 * imaandaar report dikhane ke kaam aata hai.
 *
 * Haalat SharedPreferences "jarvis_quota" me band
 * hoti hai. Tareekh badalte hi (Pacific aadhi-raat)
 * saari ginati aur jhande khud saaf ho jate hain —
 * naye din ki kataar phir poori khuli milti hai.
 *
 * v48: 429 par pehle THAND (cooldown), din-bhar ki
 * rok tabhi jab thand bhi na sambhale. Live test
 * (2026-11-02) ne dikhaya: mukhya model sirf 6
 * ginati-wali koshishon me "aaj sesh" ho gaya — ye
 * roz-wali seema (RPD) kam, minute-wali raftaar
 * seema (RPM) zyada lagti hai; din-bhar band karna
 * uska galat ilaaj tha. Naya niyam: din ka PEHLA
 * 429 = [COOLDOWN_MS] ki thand (model kataar se
 * utni der bahar, "struck" daag ke saath). Thand
 * beetne par model aam kram me phir aazmaya jata
 * hai — safal ho to daag dhul jata hai; PHIR 429
 * mile to tab, aur sirf tab, din-bhar wala
 * exhausted jhanda lagta hai (purana vyavhaar).
 * Thand ka samay bhi isi sangrah me bandhta hai,
 * isliye din badalne par woh bhi saath saaf hota
 * hai.
 */
object QuotaManager {

    private const val PREFS_NAME = "jarvis_quota"
    private const val KEY_DATE = "quota_date"

    /** v48: pehle 429 par itni der ki thand (10 minute). */
    private const val COOLDOWN_MS = 10 * 60 * 1000L

    private fun countKey(model: String) = "count:$model"
    private fun exhaustedKey(model: String) = "exhausted:$model"
    private fun unavailableKey(model: String) = "unavailable:$model"

    /** v48: model aaj ek 429 kha chuka hai kya (thand wala daag). */
    private fun struckKey(model: String) = "struck:$model"

    /** v48: model ki thand kab tak hai (epoch millis; 0 = thand nahi). */
    private fun cooldownKey(model: String) = "cooldown:$model"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Aaj ki Pacific tareekh (yyyy-MM-dd) — kota-din ki chaabi. */
    private fun pacificToday(): String = try {
        LocalDate.now(ZoneId.of("America/Los_Angeles")).toString()
    } catch (e: Exception) {
        // Ghadi/zone ki gadbad par system tareekh hi sahi.
        LocalDate.now().toString()
    }

    /**
     * Kota-din badal gaya ho to purani ginati/jhande
     * saaf karke nayi tareekh bandh do. Har sarvajanik
     * raasta pehle yahi bulata hai.
     */
    private fun ensureToday(prefs: SharedPreferences) {
        val today = pacificToday()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit().clear().putString(KEY_DATE, today).apply()
        }
    }

    /** Ek model par ek HTTP koshish hui — ginati badhao. */
    @Synchronized
    fun recordAttempt(context: Context, model: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        val key = countKey(model)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /**
     * Model ne 429 diya — v48 wala do-kadam wala niyam:
     *  - din ka PEHLA 429: sirf [COOLDOWN_MS] ki thand
     *    (struck daag + thand ka samay bandh do) —
     *    din-bhar ki rok NAHI;
     *  - thand ke baad phir aazmaye jaane par DUSRA
     *    429: tab din-bhar wala "khatm" nishan
     *    (exhausted), bilkul v43 jaisa.
     * Pehle se exhausted model par dobara kuch nahi.
     */
    @Synchronized
    fun record429(context: Context, model: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        if (prefs.getBoolean(exhaustedKey(model), false)) return
        if (prefs.getBoolean(struckKey(model), false)) {
            // Thand ke baad wali jaanch bhi 429 de gayi —
            // ye asal me din-wali seema hai.
            prefs.edit()
                .putBoolean(exhaustedKey(model), true)
                .putLong(cooldownKey(model), 0L)
                .apply()
        } else {
            prefs.edit()
                .putBoolean(struckKey(model), true)
                .putLong(
                    cooldownKey(model),
                    System.currentTimeMillis() + COOLDOWN_MS
                )
                .apply()
        }
    }

    /**
     * v48: model ne safal jawab diya — thand wala
     * daag dhul jata hai (raftaar-seema sambhal gayi
     * hai; kal ka naya 429 phir "pehla" mana jayega).
     * Din-bhar wala exhausted nishan isse NAHI hatta
     * — woh model kataar se bahar hi rehta hai, isliye
     * uska safal hona waise bhi ho hi nahi sakta.
     */
    @Synchronized
    fun recordSuccess(context: Context, model: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        prefs.edit()
            .putBoolean(struckKey(model), false)
            .putLong(cooldownKey(model), 0L)
            .apply()
    }

    /** v48: model is pal thand me hai kya (429 ke baad ki bandhi muddat). */
    @Synchronized
    fun isCoolingDown(context: Context, model: String): Boolean {
        val prefs = prefs(context)
        ensureToday(prefs)
        return prefs.getLong(cooldownKey(model), 0L) >
            System.currentTimeMillis()
    }

    /**
     * Model is project ke liye hai hi nahi (Lite ka 404 /
     * model-not-found 400) — aaj ke liye "uplabdh nahi"
     * nishan lagao, taaki kataar use phir na aazmaye.
     */
    @Synchronized
    fun recordUnavailable(context: Context, model: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        prefs.edit().putBoolean(unavailableKey(model), true).apply()
    }

    /** Model aaj 429 dekar khatm ho chuka hai kya. */
    @Synchronized
    fun isExhausted(context: Context, model: String): Boolean {
        val prefs = prefs(context)
        ensureToday(prefs)
        return prefs.getBoolean(exhaustedKey(model), false)
    }

    /** Model aaj ke liye uplabdh-nahi nishan wala hai kya. */
    @Synchronized
    fun isUnavailable(context: Context, model: String): Boolean {
        val prefs = prefs(context)
        ensureToday(prefs)
        return prefs.getBoolean(unavailableKey(model), false)
    }

    /**
     * Kataar ko is model ko abhi chhod dena chahiye
     * kya — din-bhar khatm, uplabdh-nahi, ya v48 wali
     * thand me. (Thand beet jaye to model khud kataar
     * me wapas aa jata hai — jaanch ka mauka use yahi
     * niyam deta hai.)
     */
    fun isSkippedToday(context: Context, model: String): Boolean =
        isExhausted(context, model) || isUnavailable(context, model) ||
            isCoolingDown(context, model)

    /** Screen wali "Quota hisaab" line ke liye kul jod. */
    data class Counts(
        val total: Int,
        val main: Int,
        val lite: Int,
        val mainExhausted: Boolean,
        /** v48: mukhya kataar ka pehla model is pal thand me hai (din-bhar khatm NAHI). */
        val mainCoolingDown: Boolean = false
    )

    /**
     * Aaj ki ginati ka jod: kul koshishen, unme mukhya
     * (main) modelon par kitni aur Lite modelon par
     * kitni, aur mukhya kataar ka pehla model aaj 429
     * se khatm hai kya. Modelon ki soochi [GeminiBrain]
     * se aati hai — kataar wahi tay karta hai.
     */
    @Synchronized
    fun counts(context: Context): Counts {
        val prefs = prefs(context)
        ensureToday(prefs)
        var main = 0
        var lite = 0
        for (model in GeminiBrain.MAIN_MODELS) {
            main += prefs.getInt(countKey(model), 0)
        }
        for (model in GeminiBrain.LITE_MODELS) {
            lite += prefs.getInt(countKey(model), 0)
        }
        val headMain = GeminiBrain.MAIN_MODELS.firstOrNull()
        val mainExhausted = headMain?.let {
            prefs.getBoolean(exhaustedKey(it), false)
        } ?: false
        // v48: sir ka mukhya model thand me ho (aur
        // din-bhar khatm na ho) to line me uska alag
        // nishan — "ektu pore" wala.
        val mainCoolingDown = if (mainExhausted) {
            false
        } else {
            headMain?.let {
                prefs.getLong(cooldownKey(it), 0L) >
                    System.currentTimeMillis()
            } ?: false
        }
        return Counts(main + lite, main, lite, mainExhausted, mainCoolingDown)
    }
}

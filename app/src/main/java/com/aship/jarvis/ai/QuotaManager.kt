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
 */
object QuotaManager {

    private const val PREFS_NAME = "jarvis_quota"
    private const val KEY_DATE = "quota_date"

    private fun countKey(model: String) = "count:$model"
    private fun exhaustedKey(model: String) = "exhausted:$model"
    private fun unavailableKey(model: String) = "unavailable:$model"

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

    /** Model ne 429 diya — aaj ke liye "khatm" nishan lagao. */
    @Synchronized
    fun record429(context: Context, model: String) {
        val prefs = prefs(context)
        ensureToday(prefs)
        prefs.edit().putBoolean(exhaustedKey(model), true).apply()
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

    /** Kataar ko is model ko aaj chhod dena chahiye kya. */
    fun isSkippedToday(context: Context, model: String): Boolean =
        isExhausted(context, model) || isUnavailable(context, model)

    /** Screen wali "Quota hisaab" line ke liye kul jod. */
    data class Counts(
        val total: Int,
        val main: Int,
        val lite: Int,
        val mainExhausted: Boolean
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
        val mainExhausted = GeminiBrain.MAIN_MODELS.firstOrNull()?.let {
            prefs.getBoolean(exhaustedKey(it), false)
        } ?: false
        return Counts(main + lite, main, lite, mainExhausted)
    }
}

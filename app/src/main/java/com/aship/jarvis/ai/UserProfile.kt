package com.aship.jarvis.ai

import android.content.Context

/**
 * v50: MALIK KA NAAM — uski apni pehchan wali
 * godam (v31 ki tathya-yaad se ALAG aur uski
 * 50-wali seema se BAHAR)।
 *
 * User khud bolkar naam bandhta hai —
 * "আমার নাম <নাম>, মনে রেখো" — aur woh
 * SharedPreferences "jarvis_profile" me
 * "user_name" par band rahta hai, app band
 * hone / phone restart hone par bhi। Naam:
 *  - mastishk (AgentBrain) ko har daud ke
 *    sandarbh me jata hai, taaki Maya/Tara
 *    beech-beech me naam lekar pukarein;
 *  - session ki vida-line ("ঠিক আছে, <নাম>")
 *    me bhi judta hai।
 * Wake wali pakki haan-line ("জি বস, বলো")
 * me naam jaan-boojh kar NAHI judta — woh
 * line bandhi hui hai (user ka pakka hukm)।
 *
 * Naam phone se bahar kahin nahi jata —
 * sirf Gemini ko jaane wale sandarbh me,
 * tathya-yaad ki tarah, shamil hota hai।
 * Padhna-likhna poori tarah exception-safe।
 */
object UserProfile {

    /** Bandha hua naam; na ho to null। */
    fun getName(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_USER_NAME, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /** Naam bandho (naya naam purane ko badal deta hai — sudhaar yahi hai)। */
    fun setName(context: Context, name: String) {
        val clean = name.trim().take(MAX_NAME_CHARS)
        if (clean.isEmpty()) return
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_USER_NAME, clean)
                .apply()
        } catch (t: Throwable) {
            // Naam bonus hai — asal kaam isse nahi rukta.
        }
    }

    /** Naam mitao ("নাম ভুলে যাও")। */
    fun clearName(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_USER_NAME)
                .apply()
        } catch (t: Throwable) {
            // Waisa hi — chup-chaap aage badho.
        }
    }

    /**
     * AgentBrain ke instruction ke liye naam
     * wala block — naam bandha ho tabhi, warna
     * null (mastishk ka bartav bilkul pehle
     * jaisa)।
     */
    fun nameInstruction(context: Context): String? {
        val name = getName(context) ?: return null
        return "USER NAME — the user's name is \"$name\". Address " +
            "the user by this name occasionally and naturally — " +
            "in greetings and closing lines, not in every sentence."
    }

    private const val PREFS_NAME = "jarvis_profile"
    private const val KEY_USER_NAME = "user_name"

    /** Ek naam ki adhiktam lambai (aksharon me)। */
    private const val MAX_NAME_CHARS = 40
}

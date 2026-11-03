package com.aship.jarvis.voice

import android.content.Context

/**
 * v51 (item 6a): mic ka ek-malik hisaab (arbiter).
 *
 * v50 tak saamne wali screen (MainActivity) aur wake
 * service dono apne-apne recognizer chalate the;
 * service ka chal raha command session sirf wake
 * sunne ko rokta tha, poora session nahi — dono taraf
 * se mic takraane par recognizer-busy wale bip chakra
 * (beep cycles) bante the. Ab jo bhi mic leta hai woh
 * yahan apna naam bandh deta hai, aur dusra paksh use
 * dekhkar rukta/mic chhodta hai.
 *
 * Bandhan SharedPreferences me hai (dono paksh alag
 * components hain, ek hi process hone par bhi ye
 * sabse seedha raasta hai). Purana bandhan
 * ([STALE_MS] se zyada) apne aap bekaar maana jata
 * hai — koi paksh beech me mar jaye to mic hamesha
 * ke liye band nahi rehta.
 */
object MicArbiter {

    const val OWNER_FOREGROUND = "fg"
    const val OWNER_SERVICE = "svc"

    private const val PREFS_NAME = "jarvis_mic_arbiter"
    private const val KEY_OWNER = "owner"
    private const val KEY_SINCE_MS = "since_ms"
    private const val STALE_MS = 25_000L

    fun claim(context: Context, owner: String) {
        try {
            prefs(context).edit()
                .putString(KEY_OWNER, owner)
                .putLong(KEY_SINCE_MS, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Bandhan na bandhe to purana bartao — roko mat.
        }
    }

    fun release(context: Context, owner: String) {
        try {
            val p = prefs(context)
            if (p.getString(KEY_OWNER, null) == owner) {
                p.edit().remove(KEY_OWNER).remove(KEY_SINCE_MS).apply()
            }
        } catch (t: Throwable) {
            // Saaf na ho to purana bandhan umar se khud bekaar hoga.
        }
    }

    /** Taaza malik ka naam, ya bandhan purana ho to null. */
    fun owner(context: Context): String? {
        return try {
            val p = prefs(context)
            val owner = p.getString(KEY_OWNER, null) ?: return null
            val since = p.getLong(KEY_SINCE_MS, 0L)
            if (System.currentTimeMillis() - since > STALE_MS) null else owner
        } catch (t: Throwable) {
            null
        }
    }

    fun isForegroundOwned(context: Context): Boolean =
        owner(context) == OWNER_FOREGROUND

    fun isServiceOwned(context: Context): Boolean =
        owner(context) == OWNER_SERVICE

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

package com.aship.jarvis.commands

import android.content.Context

/**
 * v57: SCREENSHOT-VISION — ডেটা বাটন চিহ্ন দেখে শেখা।
 *
 * Gemini ek baar panel ki tasveer dekhkar mobile-data
 * wala button uske CHINH (upar-neeche teer) se pehchan
 * leta hai — tile par "Data" likha ho ya na ho. Wahi
 * seekha hua button yahan (fon ke bheetar,
 * SharedPreferences me) hamesha ke liye bandha rehta hai:
 * chihn ka varnan + screen par jagah (0..1000, chaudaai/
 * lambai ke hisse me — isliye tile kahin bhi sarke,
 * dobara dhoondha ja sakta hai).
 *
 * Jagah kabhi PAKKA pata nahi hoti: tile sarkane par
 * toggle wala raasta pehle seekhi hui jagah dabakar
 * haalat padhta hai, woh na badle to taazi tasveer se
 * Gemini phir dhoondhta hai aur jagah NAHI kar deta hai.
 *
 * Sab padhna/likhna exception-safe: kuch na mile to
 * khaali, kabhi crash nahi. PhoneLearner wala hi andaaz।
 */
object DataButtonLearner {

    /** Ek seekha hua data button — chihn + jagah (0..1000)। */
    data class LearnedDataButton(
        val x: Int,
        val y: Int,
        val icon: String,
        val learnedAt: Long
    )

    /** Seekha hua button yaad rakho (nayi seekh purani ko badal deti hai)। */
    fun save(context: Context, x: Int, y: Int, icon: String) {
        try {
            prefs(context).edit()
                .putInt(KEY_X, x.coerceIn(0, 1000))
                .putInt(KEY_Y, y.coerceIn(0, 1000))
                .putString(KEY_ICON, icon.take(MAX_ICON_CHARS))
                .putLong(KEY_LEARNED_AT, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Na bache to agle seekhne par phir koshish hogi.
        }
    }

    /** Seekha hua button padho — kabhi seekha hi na ho to null। */
    fun read(context: Context): LearnedDataButton? {
        return try {
            val prefs = prefs(context)
            if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y)) {
                return null
            }
            LearnedDataButton(
                prefs.getInt(KEY_X, -1),
                prefs.getInt(KEY_Y, -1),
                prefs.getString(KEY_ICON, "") ?: "",
                prefs.getLong(KEY_LEARNED_AT, 0L)
            ).takeIf { it.x in 0..1000 && it.y in 0..1000 }
        } catch (t: Throwable) {
            null
        }
    }

    /** Seekha hua button bhula do। */
    fun clear(context: Context) {
        try {
            prefs(context).edit().clear().apply()
        } catch (t: Throwable) {
            // Saaf na ho to purana hi rahega — koi nuksaan nahi.
        }
    }

    /** Data button seekha hua hai ya nahi। */
    fun isLearned(context: Context): Boolean {
        return try {
            read(context) != null
        } catch (t: Throwable) {
            false
        }
    }

    /** "উপর-নিচ তীরের চিহ্ন" jaisi ek line — screen par dikhane ke liye। */
    fun summaryLine(context: Context): String {
        val learned = read(context) ?: return ""
        return if (learned.icon.isNotEmpty()) learned.icon else "ডেটা বাটন"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "jarvis_data_button"
    private const val KEY_X = "x_1000"
    private const val KEY_Y = "y_1000"
    private const val KEY_ICON = "icon_desc"
    private const val KEY_LEARNED_AT = "learned_at"
    private const val MAX_ICON_CHARS = 80
}

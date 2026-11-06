package com.aship.jarvis.commands

import android.content.Context
import java.util.Locale

/**
 * v53: HISTORY WALE HUKM — do chhote, surakshit,
 * poore offline hukm:
 *  1. "আবার করো" — aakhiri hukm phir chalao (repeat
 *     last command)। Aakhiri hukm khud "আবার করো"
 *     ho to usse chhod kar usse pehle wala chalega —
 *     isliye kabhi loop nahi banta।
 *  2. "ইতিহাস মুছে দাও" — command history saaf karo।
 *
 * Dono [MainActivity] me pakde jate hain — mastishk
 * (online) tak jayenge hi nahi। Koi call/message/
 * payment/delete jaisa kaam inme hai hi nahi।
 */
object HistoryCommands {

    /** Baat "আবার করো" wali ho to true — poori baat ek repeat hukm ho। */
    fun isRepeatCommand(rawText: String): Boolean {
        return try {
            val norm = normalize(rawText) ?: return false
            REPEAT_PHRASES.contains(norm)
        } catch (t: Throwable) {
            false
        }
    }

    /** Baat history saaf karne wali ho to true। */
    fun isClearHistoryCommand(rawText: String): Boolean {
        return try {
            val norm = normalize(rawText) ?: return false
            CLEAR_PHRASES.contains(norm)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Phir chalane layak aakhiri hukm — sabse naya jo
     * khud repeat hukm na ho। Koi na ho to null।
     */
    fun lastRepeatable(context: Context): String? =
        CommandHistory.list(context).firstOrNull { !isRepeatCommand(it) }

    /**
     * Baat ko ek saaf roop me lao: aas-paas ki khaali
     * jagah aur peeche wala viram hatao, chhota karo।
     * Khaali bache to null।
     */
    private fun normalize(rawText: String): String? {
        val text = rawText.trim()
            .trimEnd('।', '.', '!', '?', ',', ' ', '—', '-')
            .trim()
            .lowercase(Locale.ROOT)
        return if (text.isEmpty()) null else text
    }

    /** "আবার করো" wale saaf roop — teeno bhashaon me। */
    private val REPEAT_PHRASES = setOf(
        // Bangla
        "আবার করো", "আবার কর", "আবার চালাও", "আবার চালিয়ে দাও",
        "আবার সেইটা করো", "আবার ওইটা করো", "আগেরটা আবার করো",
        "আগের কমান্ড আবার করো", "আগের কমান্ডটা আবার চালাও",
        // Hindi
        "फिर से करो", "फिर से चलाओ", "पिछला वाला फिर से करो",
        // English
        "do it again", "repeat that", "repeat last command",
        "run it again", "again"
    )

    /** History saaf karne wale saaf roop — teeno bhashaon me। */
    private val CLEAR_PHRASES = setOf(
        // Bangla
        "ইতিহাস মুছে দাও", "ইতিহাস মুছে ফেলো", "ইতিহাস সাফ করো",
        "ইতিহাস মুছো", "হিস্ট্রি মুছে দাও", "হিস্ট্রি সাফ করো",
        // Hindi
        "इतिहास मिटा दो", "इतिहास साफ़ करो",
        // English
        "clear history", "delete history", "clear command history"
    )

    /** Phir chalane layak kuch na ho to imaandaar jawab। */
    fun emptyReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "এখনো কোনো কমান্ড দেওয়া হয়নি — আবার করার মতো কিছু নেই।"
        PlayIntent.SpokenLang.HINDI ->
            "अभी तक कोई कमांड नहीं दिया गया — दोहराने के लिए कुछ नहीं है।"
        PlayIntent.SpokenLang.ENGLISH ->
            "No command given yet — there's nothing to repeat."
    }

    /** History saaf hone par jawab। */
    fun clearedReply(lang: PlayIntent.SpokenLang): String = when (lang) {
        PlayIntent.SpokenLang.BENGALI ->
            "ইতিহাস মুছে দেওয়া হয়েছে।"
        PlayIntent.SpokenLang.HINDI ->
            "इतिहास मिटा दिया गया है।"
        PlayIntent.SpokenLang.ENGLISH ->
            "History cleared."
    }
}

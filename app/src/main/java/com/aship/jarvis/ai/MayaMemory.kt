package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray

/**
 * v31: Maya ki lambi-yaad (long-term memory) — SIRF
 * phone par bandhi chhoti si godam.
 *
 * User jo baatein "yaad rakho / মনে রাখো" bolkar
 * bandhta hai woh yahin rehti hain: SharedPreferences
 * "jarvis_memory" me "facts" naam par ek JSON array
 * (baatein = saade text ki panktiyaan, purani pehle,
 * nayi aakhir me). Baatein phone se BAHAR kahin nahi
 * jatin — na code me, na reporton (jarvis_diag) me;
 * sirf Gemini ko jaane wale sandarbh (AgentBrain ka
 * instruction-block) me shamil hoti hain, taaki
 * niji sawalon ka jawab Maya bina auzaar ke de sake.
 *
 * Seemayein bandhi hui hain: zyada se zyada
 * [MAX_FACTS] baatein (usse aage sabse purani hat-ti
 * hai) aur har baat [MAX_FACT_CHARS] aksharon tak.
 * Padhna-likhna poori tarah exception-safe hai —
 * yaad ki gadbad asal jawab ko kabhi nahi rokti.
 */
object MayaMemory {

    /** Is samay bandhi hui saari baatein (purani pehle, nayi aakhir me). */
    fun readFacts(context: Context): List<String> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FACTS, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val fact = array.optString(i).trim()
                if (fact.isNotEmpty()) out.add(fact)
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Poori suchi wapas bandho — kaat-chaant (khali
     * panktiyaan hatana, akshar-seema, [MAX_FACTS] ki
     * seema par sabse purani hatana) yahin hoti hai,
     * taaki koyi bhi bulane wala godam ko bigaad na sake.
     */
    fun writeFacts(context: Context, facts: List<String>) {
        try {
            val capped = facts
                .map { cleanFact(it) }
                .filter { it.isNotEmpty() }
                .takeLast(MAX_FACTS)
            val array = JSONArray()
            capped.forEach { array.put(it) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FACTS, array.toString())
                .apply()
        } catch (t: Throwable) {
            // Yaad bonus hai; asal jawab wala raasta alag hai.
        }
    }

    /** Ek baat ko seema me saaf karo: kinare kaato, [MAX_FACT_CHARS] tak rakho. */
    fun cleanFact(raw: String): String = raw.trim().take(MAX_FACT_CHARS)

    /**
     * AgentBrain ke liye taiyaar sandarbh-block: gini
     * hui baatein, nayi aakhir me. Block zyada lamba ho
     * to sabse purani baatein chhodkar aakhiri (~1500
     * akshar) wali rakhi jati hain — nayi baatein
     * kabhi nahi kat-ti. Yaad khali ho to null.
     */
    fun contextBlock(context: Context): String? {
        val facts = readFacts(context)
        if (facts.isEmpty()) return null
        val picked = mutableListOf<String>()
        var length = 0
        for (fact in facts.asReversed()) {
            // Pankti ki lagbhag laagat: sankhya + ") " + nayi line.
            val lineLength = fact.length + 8
            if (picked.isNotEmpty() &&
                length + lineLength > MAX_BLOCK_CHARS
            ) {
                break
            }
            picked.add(fact)
            length += lineLength
        }
        picked.reverse()
        val body = picked.mapIndexed { index, fact ->
            "${index + 1}) $fact"
        }.joinToString("\n")
        return "User ki yaad rakhi hui baatein " +
            "(sirf phone par band hain):\n" + body
    }

    private const val PREFS_NAME = "jarvis_memory"
    private const val KEY_FACTS = "facts"

    /** Bandhi baaton ki adhiktam sankhya — aage sabse purani hat-ti hai. */
    const val MAX_FACTS = 50

    /** Ek baat ki adhiktam lambai (aksharon me). */
    const val MAX_FACT_CHARS = 200

    /** Sandarbh-block ki lagbhag adhiktam lambai (aksharon me). */
    private const val MAX_BLOCK_CHARS = 1500
}

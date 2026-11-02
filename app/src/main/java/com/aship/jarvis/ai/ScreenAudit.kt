package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v46: SCREEN-KAAMON KI AUDIT-PARCHI — har screen
 * task (safal, asafal, ruka, atka, ya suraksha se
 * mana hua) app ke andar wali file me ek record
 * chhodta hai, taaki baad me jaancha ja sake ki
 * Maya ne screen par asal me kya-kya kiya tha.
 *
 * File: filesDir/screen_audit.jsonl — ek line = ek
 * kaam ka ek JSON record:
 * {"ts":<epoch-ms>,"task":"<user ke apne shabd>",
 *  "events":["3. tap_text \"...\" -> OK", ...],
 *  "outcome":"<saveDiag wala natija-naam>"}
 *
 * Kya KABHI nahi jata:
 * - type / type_into se LIKHA gaya asal text — kadmon
 *   ki lines khud ScreenAgent bandhta hai aur woh
 *   wahan sirf "type (the text you specified)" /
 *   khaane ke naam tak rehti hain; yahan wohi lines
 *   bina badle aati hain.
 * - user ke ask_user JAWAB ka text — yahan sirf
 *   "jawab aaya / nahi aaya" band hota hai (neeche
 *   [buildEvents]).
 * - koi tasveer / base64 — ginati tak nahi, kuch nahi.
 *
 * Bandobost: aakhiri [MAX_RECORDS] record hi rehte
 * hain (file dobara likhkar kataar ghumti hai)।
 * Audit ka har kaam try/catch ke andar hai — file
 * kharab ho, jagah na ho, kuch bhi ho: record na
 * banna chalega, par asal screen-kaam iski wajah se
 * kabhi nahi rukega.
 *
 * Saath me "jarvis_diag" ki "last_screen_audit" par
 * ek chhoti line bhi band hoti hai — MainActivity
 * use "Screen audit (aakhiri):" bankar dikhati hai
 * (v43 ki quota line wala hi andaaz)।
 */
object ScreenAudit {

    private const val FILE_NAME = "screen_audit.jsonl"

    /** File me aakhiri itne hi kaamon ke record rehte hain. */
    private const val MAX_RECORDS = 20

    private const val DIAG_PREFS_NAME = "jarvis_diag"
    private const val KEY_LAST_SCREEN_AUDIT = "last_screen_audit"
    private const val MAX_DIAG_TASK_CHARS = 40

    /**
     * Ek kaam ka record jodo + report line bandho.
     * [history] ScreenAgent ki kadam-kataar hai
     * ([buildEvents] use saaf karke ghatnayein banata
     * hai); [outcomeLabel] saveDiag wala natija-naam
     * hai — na ho to "ENDED" band hota hai. Ye call
     * kabhi exception nahi phenkti.
     */
    fun append(
        context: Context,
        task: String,
        history: List<String>,
        outcomeLabel: String?
    ) {
        try {
            val events = buildEvents(history)
            val outcome = outcomeLabel
                ?.takeIf { it.isNotBlank() } ?: "ENDED"
            val record = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("task", task)
                .put("events", JSONArray(events))
                .put("outcome", outcome)
            val file = File(context.filesDir, FILE_NAME)
            val kept = ArrayList<String>()
            if (file.exists()) {
                file.forEachLine { line ->
                    if (line.isNotBlank()) kept.add(line)
                }
            }
            kept.add(record.toString())
            val trimmed = if (kept.size > MAX_RECORDS) {
                kept.subList(kept.size - MAX_RECORDS, kept.size)
            } else {
                kept
            }
            file.writeText(
                trimmed.joinToString(separator = "\n", postfix = "\n")
            )
            // Main screen ki chhoti report line — andaaz
            // last_screen_run wala hi: <kaam> — <n> actions,
            // <natija>। "n" sirf asal kadam-lines (:ank se
            // shuru) ginkar banta hai.
            val actions = events.count {
                it.firstOrNull()?.isDigit() == true
            }
            val diagValue = task.take(MAX_DIAG_TASK_CHARS) +
                " — " + actions + " actions, " + outcome
            context.getSharedPreferences(
                DIAG_PREFS_NAME, Context.MODE_PRIVATE
            ).edit()
                .putString(KEY_LAST_SCREEN_AUDIT, diagValue)
                .apply()
        } catch (t: Throwable) {
            // Audit bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    /**
     * Kadam-kataar se audit ghatnayein — kram wahi jo
     * asal me hua tha:
     * - ank se shuru lines = asal kadam (tap/type/...)
     *   apne natije (OK/FAILED) ke saath, jaisi hain;
     * - "↳ result:" lines = v44 ki kadam-jaanch ka
     *   faisla (screen badli / nahi badli);
     * - "System note:" lines = pushti maangi/mili/
     *   nahi mili, khud-sudhaar (recovery), suraksha
     *   rok — sab jaisi hain (inme kabhi likha hua
     *   text nahi hota);
     * - "You asked the user:" lines KABHI jaisi hain
     *   waisi nahi jaati (sawaal aur jawab dono user/
     *   model ki baat hai) — sirf "jawab aaya / nahi
     *   aaya" band hota hai.
     * Baaki lines (model ke kharab jawab wali ghanti
     * aadi) audit me nahi aati.
     */
    fun buildEvents(history: List<String>): List<String> {
        val events = ArrayList<String>()
        for (line in history) {
            when {
                line.startsWith("You asked the user:") -> events.add(
                    if (line.contains("the user answered:")) {
                        "ask_user -> answered"
                    } else {
                        "ask_user -> NO answer received"
                    }
                )
                line.firstOrNull()?.isDigit() == true -> events.add(line)
                line.startsWith("↳ result:") -> events.add(line)
                line.startsWith("System note:") -> events.add(line)
            }
        }
        return events
    }
}

package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v49: SEEKHE HUE KAAMON KI GODAM (learned task
 * routines) — Maya ki "kaam-yaad", v31 wali tathya-
 * yaad ([MayaMemory]) se bilkul ALAG godam।
 *
 * User ka pakka niyam (2026-11-02) hai:
 *  - Seekhna SIRF uski marzi se hoga: woh "মনে
 *    রেখো" bole to aakhiri SAFAL screen-kaam poora
 *    ka poora (shuru se aakhir tak ke kadam) band
 *    hoga; ya woh khud bolkar koi chaal sikha de
 *    (taught), woh bhi band hogi।
 *  - ASAFAL ya aadha-adhura kaam KABHI nahi
 *    seekha jayega — galat chaal yaad rakhna
 *    mana hai। Isiliye bandhna sirf [ScreenAudit]
 *    ke SUCCESS wale record se hota hai, aur
 *    kadam bhi sirf wohi jo asal me OK hue the।
 *  - Agli baar wahi kaam bolne par bandhi chaal
 *    screen wale mastishk ko playbook bankar
 *    milti hai — user ko dobara kadam-kadam
 *    samjhane ki zaroorat nahi।
 *
 * Godam: SharedPreferences "jarvis_routines" me
 * "routines" naam par JSON array — har entry:
 * {name, app, created, source ("session"/"taught"),
 * used, steps[]}। Kadam sirf kriya + nishane ke
 * naam hain (audit wali lines se) — likha gaya
 * asal text, OTP, sampark-jankari isme KABHI nahi
 * aati (audit khud woh rakhta hi nahi)।
 *
 * Seemayein: zyada se zyada [MAX_ROUTINES] kaam
 * (aage sabse purana hat-ta hai), har kaam zyada
 * se zyada [MAX_STEPS] kadam। Padhna-likhna poori
 * tarah exception-safe — godam bigde to seekhna
 * band, asal kaam kabhi nahi rukta।
 *
 * Report: seekhne/mitaane par "jarvis_diag" ki
 * "last_learned" line band hoti hai (naam + srot
 * + tareekh) — kadam usme kabhi nahi jate।
 */
object LearnedRoutines {

    /** Ek bandha hua seekha kaam। */
    data class Routine(
        val name: String,
        val appPackage: String,
        val createdAt: Long,
        val source: String,
        val timesUsed: Int,
        val steps: List<String>
    )

    /** [saveFromLastRun] ka natija — bulane wala sachha jawab isse banata hai। */
    sealed class SaveOutcome {
        class Saved(val routine: Routine) : SaveOutcome()
        object NoSuccessfulRun : SaveOutcome()
    }

    // ---------------------------------------------------------------
    // Padhna / likhna
    // ---------------------------------------------------------------

    /** Is samay bandhe saare seekhe kaam (purane pehle, naye aakhir me)। */
    fun readAll(context: Context): List<Routine> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ROUTINES, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Routine>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isEmpty()) continue
                val stepsArray = obj.optJSONArray("steps")
                val steps = mutableListOf<String>()
                if (stepsArray != null) {
                    for (j in 0 until stepsArray.length()) {
                        val step = stepsArray.optString(j).trim()
                        if (step.isNotEmpty()) steps.add(step)
                    }
                }
                out.add(
                    Routine(
                        name = name,
                        appPackage = obj.optString("app").trim(),
                        createdAt = obj.optLong("created", 0L),
                        source = obj.optString("source")
                            .ifEmpty { SOURCE_SESSION },
                        timesUsed = obj.optInt("used", 0),
                        steps = steps.take(MAX_STEPS)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Poori suchi wapas bandho — seemayein (ginati, kadam) yahin lagti hain। */
    private fun writeAll(context: Context, routines: List<Routine>) {
        try {
            val capped = routines
                .map { routine ->
                    routine.copy(
                        name = routine.name.trim().take(MAX_NAME_CHARS),
                        steps = routine.steps
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .take(MAX_STEPS)
                    )
                }
                .filter { it.name.isNotEmpty() }
                .takeLast(MAX_ROUTINES)
            val array = JSONArray()
            for (routine in capped) {
                val stepsArray = JSONArray()
                routine.steps.forEach { stepsArray.put(it) }
                array.put(
                    JSONObject()
                        .put("name", routine.name)
                        .put("app", routine.appPackage)
                        .put("created", routine.createdAt)
                        .put("source", routine.source)
                        .put("used", routine.timesUsed)
                        .put("steps", stepsArray)
                )
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROUTINES, array.toString())
                .apply()
        } catch (t: Throwable) {
            // Godam bonus hai; asal kaam isse kabhi nahi rukta.
        }
    }

    /** Is samay bandhe seekhe kaamon ki ginati (mukhya screen ki report ke liye)। */
    fun count(context: Context): Int = readAll(context).size

    // ---------------------------------------------------------------
    // Bandhna — safal daud se, ya user ke sikhane se
    // ---------------------------------------------------------------

    /**
     * Aakhiri SAFAL screen-kaam ko routine bankar
     * bandho — user ke "মনে রেখো" wale hukm ka
     * asal kaam yahi hai। Srot [ScreenAudit] ka
     * sabse taaza record hai:
     *  - record hi na ho, ya uska natija SUCCESS
     *    na ho (asafal / ruka / mana hua) — to
     *    [SaveOutcome.NoSuccessfulRun]; KUCH band
     *    nahi hota (user ka pakka niyam: asafal
     *    kaam kabhi nahi seekhna)।
     *  - safal ho to uske sirf OK hue kadam (audit
     *    ki ank-wali lines jo "-> OK" / "-> done"
     *    par khatm hoti hain) bandhte hain —
     *    type wale kadam audit me bina asal text
     *    ke hi aate hain, isliye niji jankari
     *    kadmon me ghus hi nahi sakti।
     * Naam kaam ke apne text se (kata hua)।
     * Ek hi naam ka purana routine ho to woh
     * badal jata hai — dobara sikhana = sudhaar।
     */
    fun saveFromLastRun(context: Context): SaveOutcome {
        val record = ScreenAudit.latestRecord(context)
            ?: return SaveOutcome.NoSuccessfulRun
        if (!record.outcome.startsWith(SUCCESS_OUTCOME_PREFIX)) {
            return SaveOutcome.NoSuccessfulRun
        }
        val steps = record.events
            .filter { line ->
                STEP_LINE_REGEX.containsMatchIn(line) &&
                    (line.endsWith("-> OK") || line.endsWith("-> done"))
            }
            .map { line -> line.replace(STEP_PREFIX_REGEX, "") }
            .take(MAX_STEPS)
        if (steps.isEmpty()) return SaveOutcome.NoSuccessfulRun
        val routine = Routine(
            name = record.task.trim().take(MAX_NAME_CHARS),
            appPackage = "",
            createdAt = System.currentTimeMillis(),
            source = SOURCE_SESSION,
            timesUsed = 0,
            steps = steps
        )
        if (routine.name.isEmpty()) return SaveOutcome.NoSuccessfulRun
        store(context, routine)
        return SaveOutcome.Saved(routine)
    }

    /**
     * User ne bolkar chaal sikhayi — uske apne
     * shabdon wale kadam [steps] bankar bandho।
     * Ye raasta safal-daud wale dwaar se alag hai:
     * sikhana user ka seedha hukm hai, isliye isme
     * audit ki jaanch nahi lagti।
     */
    fun saveTaught(
        context: Context,
        name: String,
        steps: List<String>
    ): Routine {
        val routine = Routine(
            name = name.trim().take(MAX_NAME_CHARS)
                .ifEmpty { "শেখানো কাজ" },
            appPackage = "",
            createdAt = System.currentTimeMillis(),
            source = SOURCE_TAUGHT,
            timesUsed = 0,
            steps = steps.map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(MAX_STEPS)
        )
        store(context, routine)
        return routine
    }

    /** Ek routine godam me rakho (ek-naam wala purana badal jata hai) + report। */
    private fun store(context: Context, routine: Routine) {
        val all = readAll(context).toMutableList()
        val existing = all.indexOfFirst {
            it.name.equals(routine.name, ignoreCase = true)
        }
        if (existing >= 0) {
            // Dobara bandha gaya wahi kaam: purani
            // "used" ginati bachi rahe (seekhne ka
            // sudhaar istemal ka hisaab nahi mitata)।
            all[existing] = routine.copy(
                timesUsed = all[existing].timesUsed
            )
        } else {
            all.add(routine)
        }
        writeAll(context, all)
        saveLearnedDiag(context, routine.name, routine.source)
    }

    /**
     * Naam se routine mitao। Mita ho to woh
     * routine, warna null। Report bhi bandhti hai।
     */
    fun delete(context: Context, routine: Routine): Boolean {
        val all = readAll(context)
        val kept = all.filterNot {
            it.name.equals(routine.name, ignoreCase = true)
        }
        if (kept.size == all.size) return false
        writeAll(context, kept)
        saveLearnedDiag(context, routine.name, "forgot")
        return true
    }

    /** Ek routine ka istemal ginati me jodo (asal me laga tabhi — [matchForTask] ke baad)। */
    fun incrementUsed(context: Context, name: String) {
        val all = readAll(context).map { routine ->
            if (routine.name.equals(name, ignoreCase = true)) {
                routine.copy(timesUsed = routine.timesUsed + 1)
            } else {
                routine
            }
        }
        writeAll(context, all)
    }

    // ---------------------------------------------------------------
    // Dhoondhna — bolne wale hukm se, aur screen-kaam se
    // ---------------------------------------------------------------

    /**
     * Is baat me kisi bandhe routine ka NAAM saaf
     * roop se aaya ho to woh routine — "ভুলে যাও
     * <naam>" jaise hukmon ke liye। Naam ka poora
     * roop baat me ho, ya naam ke bade shabdon ka
     * zyada hissa (60%) baat me ho — tabhi milan
     * mana jata hai; chhota sa tukda kabhi nahi।
     */
    fun findMentioned(context: Context, text: String): Routine? {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        val routines = readAll(context)
        for (routine in routines) {
            val name = routine.name.lowercase(Locale.ROOT)
            if (name.length >= MIN_NAME_MATCH_CHARS && lower.contains(name)) {
                return routine
            }
        }
        return routines.maxByOrNull { wordOverlap(it.name, lower) }
            ?.takeIf { wordOverlap(it.name, lower) >= OVERLAP_THRESHOLD }
    }

    /**
     * Is screen-kaam par koi bandha routine lagta
     * ho to woh — [AgentTools] ka screen_task ise
     * playbook chunne se pehle jaanchta hai। Milan
     * ke do darje: (1) naam kaam me ya kaam naam
     * me poora samaaya ho; (2) naam ke bade shabdon
     * ka 60% se zyada hissa kaam me ho। Pehla
     * milne wala (sabse zyada overlap wala) jeet-ta
     * hai — ek kaam par ek hi routine lagta hai।
     */
    fun matchForTask(context: Context, task: String): Routine? {
        val lower = task.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        val routines = readAll(context)
        if (routines.isEmpty()) return null
        for (routine in routines) {
            val name = routine.name.lowercase(Locale.ROOT)
            if (name.length >= MIN_NAME_MATCH_CHARS &&
                (lower.contains(name) ||
                    (lower.length >= MIN_NAME_MATCH_CHARS &&
                        name.contains(lower)))
            ) {
                return routine
            }
        }
        val best = routines.maxByOrNull { wordOverlap(it.name, lower) }
            ?: return null
        return best.takeIf {
            wordOverlap(it.name, lower) >= OVERLAP_THRESHOLD
        }
    }

    /** Naam ke bade shabdon (3+ akshar) ka kitna hissa [lowerText] me hai (0.0–1.0)। */
    private fun wordOverlap(name: String, lowerText: String): Double {
        val words = name.lowercase(Locale.ROOT)
            .split(Regex("[\\s,।:;!?/\\\\|()\\[\\]\"']+"))
            .filter { it.length >= 3 }
        if (words.isEmpty()) return 0.0
        val present = words.count { lowerText.contains(it) }
        return present.toDouble() / words.size.toDouble()
    }

    // ---------------------------------------------------------------
    // Playbook + instruction block
    // ---------------------------------------------------------------

    /**
     * Routine ka playbook roop — [ScreenAgent] ke
     * playbook wale hisse me bilkul waisi hi
     * hidayat bankar jata hai jaisi v47 ke pack
     * playbook jate hain। Sikhayi chaal me kadam
     * user ke apne shabd hain; safal daud wali
     * chaal me kadam kriya + nishane ke naam hain।
     */
    fun playbookText(routine: Routine): String {
        val out = StringBuilder()
        if (routine.source == SOURCE_TAUGHT) {
            out.append(
                "LEARNED TASK — the user personally taught " +
                    "this procedure. Follow these steps in " +
                    "order, adapting to the current screen:\n"
            )
            routine.steps.forEachIndexed { index, step ->
                out.append(index + 1).append(". ").append(step).append("\n")
            }
        } else {
            out.append(
                "LEARNED TASK — this exact task completed " +
                    "SUCCESSFULLY on this phone before. These " +
                    "are the steps that worked last time; " +
                    "follow the same route, adapting to the " +
                    "current screen:\n"
            )
            for (step in routine.steps) {
                out.append("- ").append(step).append("\n")
            }
        }
        return out.toString().trim()
    }

    /**
     * AgentBrain ke instruction ke liye compact
     * block: sirf NAAM (aur istemal ginati) —
     * kadam wahan nahi jate (woh screen wale
     * mastishk ko app khud deta hai jab screen_task
     * chalta hai)। Godam khaali ho to null।
     */
    fun instructionBlock(context: Context): String? {
        val routines = readAll(context)
        if (routines.isEmpty()) return null
        val picked = routines.takeLast(MAX_BLOCK_ROUTINES)
        val out = StringBuilder()
        out.append(
            "LEARNED TASKS — tasks the user personally " +
                "taught you or asked you to remember after " +
                "they finished successfully (stored only " +
                "on this phone). If the user's request " +
                "matches one of these, call screen_task " +
                "with the task in the user's own words — " +
                "the app attaches the saved steps itself:\n"
        )
        picked.forEachIndexed { index, routine ->
            out.append(index + 1).append(") ")
                .append(routine.name.take(BLOCK_NAME_CHARS))
            if (routine.timesUsed > 0) {
                out.append(" (done ").append(routine.timesUsed)
                    .append(" times before)")
            }
            out.append("\n")
        }
        return out.toString().trim()
    }

    /** "jarvis_diag" ki "last_learned" report — naam + srot + tareekh, kadam kabhi nahi। */
    private fun saveLearnedDiag(
        context: Context,
        name: String,
        source: String
    ) {
        try {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Date())
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(
                    KEY_LAST_LEARNED,
                    name.take(MAX_NAME_CHARS) + " · " + source + " · " + date
                )
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai — asal seekhna isse nahi rukta.
        }
    }

    private const val PREFS_NAME = "jarvis_routines"
    private const val KEY_ROUTINES = "routines"
    private const val DIAG_PREFS_NAME = "jarvis_diag"
    private const val KEY_LAST_LEARNED = "last_learned"

    /** Routine ke srot — safal screen-daud se bandha hua। */
    const val SOURCE_SESSION = "session"

    /** Routine ke srot — user ne bolkar khud sikhaya hua। */
    const val SOURCE_TAUGHT = "taught"

    /** Audit ke SUCCESS wale natije ki shuruaat (saveDiag "SUCCESS" bandhta hai)। */
    private const val SUCCESS_OUTCOME_PREFIX = "SUCCESS"

    /** Bandhe kaamon ki adhiktam ginati — aage sabse purana hat-ta hai। */
    private const val MAX_ROUTINES = 30

    /** Ek kaam ke adhiktam kadam। */
    const val MAX_STEPS = 40

    /** Ek naam ki adhiktam lambai (aksharon me)। */
    private const val MAX_NAME_CHARS = 80

    /** Instruction block me zyada se zyada itne naam। */
    private const val MAX_BLOCK_ROUTINES = 10

    /** Block me ek naam ki kata hua lambai। */
    private const val BLOCK_NAME_CHARS = 60

    /** Naam-milan ke liye naam ki kam se kam lambai (chhote naam shor hain)। */
    private const val MIN_NAME_MATCH_CHARS = 8

    /** Shabd-overlap wala milan kam se kam itna ho। */
    private const val OVERLAP_THRESHOLD = 0.6

    /** Audit ki kadam-line ki pehchan: ank se shuru। */
    private val STEP_LINE_REGEX = Regex("^\\d+\\. ")

    /** Kadam-line ke aage ka ank + bindu hatane ke liye। */
    private val STEP_PREFIX_REGEX = Regex("^\\d+\\.\\s*")
}

package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v100: PROACTIVE PROPOSAL — Maya ki "routine-yaad"
 * (v49 wale [LearnedRoutines] se BILKUL ALAG godam —
 * woh sikhaye hue kaam hain, ye apne-aap seekhe hue
 * aadat hain)।
 *
 * Kya seekhta hai: user ek hi kaam ek hi samay par
 * baar-baar kare (wahi gaana subah, wahi combo raat
 * ko, wahi app dopahar me, wahi yaad subah 9 baje)
 * to woh aadat bandhti hai — [observe] har safal
 * kaam par ek nishan lagata hai (kya + kab), aur
 * 3 nishan hote hi woh "seekhi hui routine" ban
 * jati hai ([MIN_COUNT])। Ek baar ka kaam kabhi
 * routine nahi banta।
 *
 * Kya NAHI seekhta: paise, call/message, setting
 * badlav — sirf chaar surakshit tarah: SONG
 * (YouTube gaana), COMBO (bandha combo), APP
 * (app kholna), ALARM (yaad lagana)। Meeting nahi
 * — app me calendar hai hi nahi, isliye meeting ki
 * aadat ka koi srot hi nahi।
 *
 * Prastav kab: seekhi routine ke samay (mode hour)
 * par, din me ek baar per routine, sirf tab jab
 * prastav chalu hon ([isGlobalEnabled]) aur routine
 * band na ho। "ना" bole to 3 din us routine ka
 * prastav rukega ([DECLINE_COOLDOWN_MS])। 30 din
 * se na hua kaam purana mana jata hai (STALE) —
 * uska prastav nahi hota।
 *
 * Godam: SharedPreferences "jarvis_proactive" me
 * "routines_json" (JSON array) — har entry:
 * {key, kind, arg, label, count, hours, lastSeen,
 * lastProposed, lastDeclined, enabled}। "hours" =
 * aakhiri 10 nishanon ke ghante (csv) — mode hi
 * routine ka samay hai। Latka prastav bhi yahin:
 * "pending_key" + "pending_since" (10 minute ke
 * baad khud mit jata hai)।
 *
 * Padhna-likhna poori tarah exception-safe aur
 * synchronized — seekhna kabhi asal kaam nahi rokta।
 */
object ProactiveRoutines {

    /** Seekhi aadat ki chaar surakshit tarah। */
    enum class Kind { SONG, COMBO, APP, ALARM }

    /** Ek seekhi (ya seekhi ja rahi) aadat। */
    data class Routine(
        val key: String,
        val kind: Kind,
        val arg: String,
        val label: String,
        val count: Int,
        val hours: List<Int>,
        val lastSeen: Long,
        val lastProposed: String,
        val lastDeclined: Long,
        val enabled: Boolean
    )

    // ---------------------------------------------------------------
    // Seekhna — safal kaam par nishan
    // ---------------------------------------------------------------

    /**
     * Ek safal kaam ka nishan lagao — wahi tarah +
     * wahi kaam + is samay ka ghanta। Khaali arg /
     * label kabhi nahi bandhta। Dono mezbaan (saamne
     * wali screen + background wake service) yahi
     * bulate hain, apne-apne safal raaston se।
     */
    fun observe(context: Context, kind: Kind, arg: String, label: String) {
        val cleanArg = arg.trim()
        val cleanLabel = label.trim().take(MAX_LABEL_CHARS)
        if (cleanArg.isEmpty() || cleanLabel.isEmpty()) return
        try {
            synchronized(this) {
                val key = routineKey(kind, cleanArg)
                val now = System.currentTimeMillis()
                val hour = java.util.Calendar.getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY)
                val all = readAll(context).toMutableList()
                val idx = all.indexOfFirst { it.key == key }
                if (idx >= 0) {
                    val old = all[idx]
                    val hours = (old.hours + hour).takeLast(MAX_HOURS)
                    all[idx] = old.copy(
                        count = old.count + 1,
                        hours = hours,
                        lastSeen = now,
                        label = cleanLabel
                    )
                } else {
                    all.add(
                        Routine(
                            key = key,
                            kind = kind,
                            arg = cleanArg,
                            label = cleanLabel,
                            count = 1,
                            hours = listOf(hour),
                            lastSeen = now,
                            lastProposed = "",
                            lastDeclined = 0L,
                            enabled = true
                        )
                    )
                }
                writeAll(context, all)
            }
        } catch (t: Throwable) {
            // Seekhna bonus hai — asal kaam isse kabhi nahi rukta.
        }
    }

    /** Tarah + kaam se pakki chaabi (case-insensitive)। */
    fun routineKey(kind: Kind, arg: String): String =
        kind.name + "|" + arg.trim().lowercase(Locale.ROOT)

    // ---------------------------------------------------------------
    // Padhna / likhna
    // ---------------------------------------------------------------

    /** Is samay bandhi saari aadatein (purani pehle, nayi aakhir me)। */
    fun readAll(context: Context): List<Routine> {
        return try {
            val raw = context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ROUTINES, null) ?: return emptyList()
            val array = JSONArray(raw)
            val out = mutableListOf<Routine>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key").trim()
                val kindName = obj.optString("kind").trim()
                val kind = try {
                    Kind.valueOf(kindName)
                } catch (t: Throwable) {
                    continue
                }
                if (key.isEmpty()) continue
                val hoursRaw = obj.optString("hours", "")
                val hours = hoursRaw.split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in 0..23 }
                    .takeLast(MAX_HOURS)
                out.add(
                    Routine(
                        key = key,
                        kind = kind,
                        arg = obj.optString("arg").trim(),
                        label = obj.optString("label").trim()
                            .ifEmpty { obj.optString("arg").trim() },
                        count = obj.optInt("count", 0),
                        hours = hours,
                        lastSeen = obj.optLong("lastSeen", 0L),
                        lastProposed = obj.optString("lastProposed", ""),
                        lastDeclined = obj.optLong("lastDeclined", 0L),
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** Poori suchi wapas bandho — seema (ginati) yahin lagti hai। */
    private fun writeAll(context: Context, routines: List<Routine>) {
        try {
            val capped = routines
                .filter { it.key.isNotEmpty() && it.count > 0 }
                .sortedWith(
                    compareByDescending<Routine> { it.count }
                        .thenByDescending { it.lastSeen }
                )
                .take(MAX_ROUTINES)
            val array = JSONArray()
            for (routine in capped) {
                array.put(
                    JSONObject()
                        .put("key", routine.key)
                        .put("kind", routine.kind.name)
                        .put("arg", routine.arg)
                        .put("label", routine.label.take(MAX_LABEL_CHARS))
                        .put("count", routine.count)
                        .put(
                            "hours",
                            routine.hours.takeLast(MAX_HOURS)
                                .joinToString(",")
                        )
                        .put("lastSeen", routine.lastSeen)
                        .put("lastProposed", routine.lastProposed)
                        .put("lastDeclined", routine.lastDeclined)
                        .put("enabled", routine.enabled)
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

    /** Ek routine badlo (chaabi se dhoondhkar) — na mile to kuch nahi। */
    private fun update(
        context: Context,
        key: String,
        change: (Routine) -> Routine
    ) {
        try {
            synchronized(this) {
                val all = readAll(context)
                val changed = all.map { routine ->
                    if (routine.key == key) change(routine) else routine
                }
                if (changed != all) writeAll(context, changed)
            }
        } catch (t: Throwable) {
            // Badlav bonus hai — asal kaam isse nahi rukta.
        }
    }

    // ---------------------------------------------------------------
    // Seekhi hui routine — prastav ke layak
    // ---------------------------------------------------------------

    /** Aadat ka samay — nishanon ke ghanton ka mode (barabar ho to taaza wala)। */
    fun modeHour(routine: Routine): Int {
        if (routine.hours.isEmpty()) return -1
        val counts = LinkedHashMap<Int, Int>()
        val lastIdx = HashMap<Int, Int>()
        routine.hours.forEachIndexed { index, hour ->
            counts[hour] = (counts[hour] ?: 0) + 1
            lastIdx[hour] = index
        }
        var bestHour = routine.hours.last()
        var bestCount = -1
        var bestLast = -1
        for ((hour, count) in counts) {
            val last = lastIdx[hour] ?: 0
            if (count > bestCount ||
                (count == bestCount && last > bestLast)
            ) {
                bestHour = hour
                bestCount = count
                bestLast = last
            }
        }
        return bestHour
    }

    /** Seekhi hui routinein — 3+ baar, chalu, purani nahi। */
    fun learned(context: Context): List<Routine> {
        val now = System.currentTimeMillis()
        return readAll(context).filter { routine ->
            routine.enabled && routine.count >= MIN_COUNT &&
                now - routine.lastSeen <= STALE_MS
        }
    }

    /** Seekhi hui routinein kitni hain (card ki sthiti ke liye)। */
    fun learnedCount(context: Context): Int = learned(context).size

    /**
     * Is samay ka due prastav — ek hi (sabse zyada
     * baar wali, barabar ho to taazi wali)। Due
     * hone ki shartein: routine seekhi hui ho, uska
     * samay (mode hour) abhi chal raha ho, aaj
     * iska prastav na hua ho, aur "ना" ke baad 3
     * din beet gaye hon। Na ho to null।
     */
    fun dueProposal(context: Context, nowMillis: Long = System.currentTimeMillis()): Routine? {
        if (!isGlobalEnabled(context)) return null
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMillis
        val hourNow = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val today = todayString(nowMillis)
        val candidates = learned(context).filter { routine ->
            modeHour(routine) == hourNow &&
                routine.lastProposed != today &&
                (routine.lastDeclined == 0L ||
                    nowMillis - routine.lastDeclined > DECLINE_COOLDOWN_MS)
        }
        if (candidates.isEmpty()) return null
        return candidates.maxWithOrNull(
            compareByDescending<Routine> { it.count }
                .thenByDescending { it.lastSeen }
        )
    }

    // ---------------------------------------------------------------
    // Latka prastav — HAAN/NA ka intezaar
    // ---------------------------------------------------------------

    /** Prastav bola gaya — aaj ka nishan + latka hua intezaar bandho। */
    fun markProposed(context: Context, routine: Routine) {
        update(context, routine.key) { old ->
            old.copy(lastProposed = todayString(System.currentTimeMillis()))
        }
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PENDING, routine.key)
                .putLong(KEY_PENDING_SINCE, System.currentTimeMillis())
                .apply()
        } catch (t: Throwable) {
            // Latka intezaar bonus hai — prastav to bola hi gaya.
        }
    }

    /**
     * Latka prastav ho to woh routine — 10 minute se
     * purana ho, routine mit gayi ho ya band ho gayi
     * ho to saaf karke null (pal beet gaya)।
     */
    fun pendingProposal(context: Context): Routine? {
        return try {
            val prefs = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE
            )
            val key = prefs.getString(KEY_PENDING, null) ?: return null
            val since = prefs.getLong(KEY_PENDING_SINCE, 0L)
            if (since == 0L ||
                System.currentTimeMillis() - since > PENDING_TTL_MS
            ) {
                clearPending(context)
                return null
            }
            val routine = readAll(context).firstOrNull { it.key == key }
            if (routine == null || !routine.enabled) {
                clearPending(context)
                return null
            }
            routine
        } catch (t: Throwable) {
            null
        }
    }

    /** Latka intezaar saaf karo (jawab mil gaya ya pal beet gaya)। */
    fun clearPending(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PENDING)
                .remove(KEY_PENDING_SINCE)
                .apply()
        } catch (t: Throwable) {
            // Saaf na ho to agli baar khud mit jayega (TTL)।
        }
    }

    /** "ना" — 3 din is routine ka prastav rukega, intezaar saaf। */
    fun markDeclined(context: Context, routine: Routine) {
        update(context, routine.key) { old ->
            old.copy(lastDeclined = System.currentTimeMillis())
        }
        clearPending(context)
    }

    // ---------------------------------------------------------------
    // Chalu / band
    // ---------------------------------------------------------------

    /** Saare prastav chalu hain kya (default: haan)। */
    fun isGlobalEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)
        } catch (t: Throwable) {
            true
        }
    }

    /** Saare prastav chalu/band karo। */
    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        } catch (t: Throwable) {
            // Badlav bonus hai — asal kaam isse nahi rukta.
        }
    }

    /** Ek routine ka prastav chalu/band karo। */
    fun setRoutineEnabled(context: Context, key: String, enabled: Boolean) {
        update(context, key) { old -> old.copy(enabled = enabled) }
    }

    // ---------------------------------------------------------------
    // Dhoondhna — naam se routine (band karne / wapas chalane ke liye)
    // ---------------------------------------------------------------

    /**
     * Is baat me kisi routine ka naam/label saaf aaya
     * ho to woh routine — "…-এর প্রস্তাব আর কোরো না"
     * jaise hukmon ke liye। Label poora baat me ho,
     * ya label ke bade shabdon ka 60% hissa ho —
     * tabhi milan।
     */
    fun findMentioned(context: Context, text: String): Routine? {
        val lower = text.lowercase(Locale.ROOT).trim()
        if (lower.isEmpty()) return null
        val routines = readAll(context)
        for (routine in routines) {
            val label = routine.label.lowercase(Locale.ROOT)
            if (label.length >= MIN_NAME_MATCH_CHARS &&
                lower.contains(label)
            ) {
                return routine
            }
        }
        return routines.maxByOrNull { wordOverlap(it.label, lower) }
            ?.takeIf { wordOverlap(it.label, lower) >= OVERLAP_THRESHOLD }
    }

    /** Label ke bade shabdon (3+ akshar) ka kitna hissa [lowerText] me hai (0.0–1.0)। */
    private fun wordOverlap(name: String, lowerText: String): Double {
        val words = name.lowercase(Locale.ROOT)
            .split(Regex("[\\s,।:;!?/\\\\|()\\[\\]\"']+"))
            .filter { it.length >= 3 }
        if (words.isEmpty()) return 0.0
        val present = words.count { lowerText.contains(it) }
        return present.toDouble() / words.size.toDouble()
    }

    /** Aaj ki tareekh — "yyyy-MM-dd" (din me ek baar wali jaanch ke liye)। */
    private fun todayString(nowMillis: Long): String {
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis))
        } catch (t: Throwable) {
            ""
        }
    }

    private const val PREFS_NAME = "jarvis_proactive"
    private const val KEY_ROUTINES = "routines_json"
    private const val KEY_ENABLED = "proposals_enabled"
    private const val KEY_PENDING = "pending_key"
    private const val KEY_PENDING_SINCE = "pending_since"

    /** Routine banne ke liye kam se kam itne nishan (ek baar ka kaam kabhi nahi)। */
    private const val MIN_COUNT = 3

    /** Bandhi aadaton ki adhiktam ginati — aage kam baar wali hat-ti hai। */
    private const val MAX_ROUTINES = 20

    /** Ek routine ke aakhiri itne ghante yaad rakho (mode ke liye)। */
    private const val MAX_HOURS = 10

    /** Ek label ki adhiktam lambai (aksharon me)। */
    private const val MAX_LABEL_CHARS = 60

    /** "ना" ke baad is routine ka prastav itne samay rukega (3 din)। */
    private const val DECLINE_COOLDOWN_MS = 3L * 24L * 60L * 60L * 1000L

    /** Latka prastav itne samay baad khud mit jayega (10 minute — pal beet gaya)। */
    private const val PENDING_TTL_MS = 10L * 60L * 1000L

    /** Itne din se na hua kaam purana mana jata hai — prastav nahi hota (30 din)। */
    private const val STALE_MS = 30L * 24L * 60L * 60L * 1000L

    /** Naam-milan ke liye label ki kam se kam lambai। */
    private const val MIN_NAME_MATCH_CHARS = 3

    /** Shabd-overlap wala milan kam se kam itna ho। */
    private const val OVERLAP_THRESHOLD = 0.6
}

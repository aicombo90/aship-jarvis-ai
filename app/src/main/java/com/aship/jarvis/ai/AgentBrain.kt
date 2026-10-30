package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * v25: SANYOJIT MASTISHK (combined brain) — app ke andar
 * EK hi sir (user ka pakka design).
 *
 * Ab tak ulta tha: pehle offline engine, miss ho to
 * aakhir me Gemini se sirf baatchit. Ab seedha hai:
 * Gemini poori baat samajhta hai, kaam ko kadmon me
 * todta hai, ONLINE kadam khud leta hai (jawab dena,
 * faisla karna, search ke shabd chunna) aur PHONE wale
 * kadam offline auzaaron (AgentTools) se karwata hai —
 * har auzaar ka sachha natija wapas lekar, jab tak
 * POORA kaam khatm na ho jaye. Misal: "YouTube kholo
 * aur torch jalao" — round 1 open_app, round 2 torch,
 * round 3 aakhiri bola jawab.
 *
 * Daud bandhi hui hai: zyada se zyada [MAX_ROUNDS]
 * round; har round ke liye transport GeminiBrain ka
 * hai (wahi model kataar, wahi timeout, wahi "Online
 * koshish" report). Protocol JSON hai — mastishk har
 * round me SIRF ek JSON object bhejta hai: ya to ek
 * auzaar ki maang, ya "final" jawab. JSON na bane to
 * saade text ko hi aakhiri jawab maan lete hain —
 * protocol ki sakhti user par kabhi nahi padti.
 *
 * Round 1 par koi model hi jawab na de (poori kataar
 * null) to callback(null) — bulane wala apne purane
 * offline raaste par girta hai, bilkul pehle jaisa.
 * Beech ke round fail hon to jhootha "ho gaya" NAHI:
 * aakhiri boli hui pragati-line, warna ek imaandaar
 * "kuch kaam hua, poora nahi ho paya" wala jawab.
 *
 * Har daud ki report SharedPreferences "jarvis_diag"
 * me "last_agent_run" par band hoti hai (kaun se
 * auzaar, OK/FAIL, aakhir kya hua) — key, sawal ka
 * poora text aur jawab usme kabhi nahi aate; auzaaron
 * ke naam aur unke args (khud user ke bole hue kaam)
 * aate hain.
 *
 * Callback is class ke apne executor thread par aata
 * hai — bulane wala apne thread par le jaye (bilkul
 * GeminiBrain.chat ki tarah). Auzaar bhi isi thread
 * par chalte hain, isliye bandhe intezaar (mobile
 * data wala latch) kisi UI ko nahi rokta.
 */
object AgentBrain {

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Ek poora kaam chalao. [history] pichhle user/model
     * turn hain, [userText] nayi baat. [toolExecutor] ek
     * auzaar chalakar uska sachha natija deta hai —
     * bulane wala AgentTools bandh kar deta hai. Callback
     * par aakhiri bola-jane-wala jawab milta hai; round 1
     * par hi koi model na mile to null.
     */
    fun run(
        context: Context,
        history: List<Pair<String, String>>,
        userText: String,
        toolExecutor: (String, JSONObject) -> AgentTools.ToolResult,
        callback: (String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val reply = try {
                runLoop(appContext, history, userText, toolExecutor)
            } catch (t: Throwable) {
                // Andar ki anjaani gadbad: round 1 wali
                // imaandari — bulane wala offline gir jaye.
                saveRunSummary(appContext, "FAILED (andar ki gadbad)")
                null
            }
            callback(reply)
        }
    }

    /** Asal ghumav — sirf executor thread se. */
    private fun runLoop(
        context: Context,
        history: List<Pair<String, String>>,
        userText: String,
        toolExecutor: (String, JSONObject) -> AgentTools.ToolResult
    ): String? {
        // Baatchit: purane turn + nayi baat; har round ke
        // baad model ka kaccha jawab aur auzaar ka natija
        // isme judte jate hain.
        val contents = mutableListOf<Pair<String, String>>()
        contents.addAll(history)
        contents.add("user" to userText)
        val steps = mutableListOf<String>()
        var lastSay: String? = null
        for (round in 1..MAX_ROUNDS) {
            val raw = GeminiBrain.requestAgentRound(
                context, AGENT_INSTRUCTION, contents
            )
            if (raw == null) {
                // Poori model kataar chup hai.
                if (round == 1) {
                    saveRunSummary(context, "FAILED round1 (no model)")
                    return null
                }
                steps.add("FAILED round$round (no model)")
                saveRunSummary(context, "steps: " + steps.joinToString(" -> "))
                return lastSay ?: PARTIAL_REPLY
            }
            when (val parsed = parseProtocol(raw)) {
                is Parsed.Final -> {
                    steps.add("final")
                    saveRunSummary(context, "steps: " + steps.joinToString(" -> "))
                    return parsed.say
                }
                is Parsed.Plain -> {
                    // Protocol JSON nahi bana — saade text
                    // ko hi aakhiri jawab maan lo.
                    steps.add("final")
                    saveRunSummary(context, "steps: " + steps.joinToString(" -> "))
                    return parsed.text
                }
                is Parsed.Tool -> {
                    parsed.say?.let { lastSay = it }
                    val result = try {
                        toolExecutor(parsed.tool, parsed.args)
                    } catch (t: Throwable) {
                        AgentTools.ToolResult(
                            false,
                            "The tool crashed (${t.javaClass.simpleName})."
                        )
                    }
                    steps.add(
                        stepLabel(parsed.tool, parsed.args) +
                            (if (result.ok) "=OK" else "=FAIL")
                    )
                    // Model ka apna jawab "model" turn bankar,
                    // phir auzaar ka sachha natija "user" turn
                    // bankar — agla round inhi par chalega.
                    contents.add("model" to raw.trim())
                    contents.add(
                        "user" to "Tool result for ${parsed.tool}: " +
                            (if (result.ok) "OK" else "FAILED") +
                            " — " + result.text
                    )
                }
            }
        }
        // Round khatm, "final" aaya hi nahi — jo hua so hua,
        // imaandaar jawab do.
        steps.add("STOP (max rounds)")
        saveRunSummary(context, "steps: " + steps.joinToString(" -> "))
        return lastSay ?: PARTIAL_REPLY
    }

    // ------------------------------------------------------------------
    // Protocol samajhna
    // ------------------------------------------------------------------

    private sealed class Parsed {
        class Tool(
            val tool: String,
            val args: JSONObject,
            val say: String?
        ) : Parsed()

        class Final(val say: String) : Parsed()

        /** JSON nahi bana — saade text wala jawab. */
        class Plain(val text: String) : Parsed()
    }

    /**
     * Model ke kacche jawab ko protocol me badlo.
     * Markdown ke baad (```json ... ```) aur aas-paas ki
     * bak-bak surakshit roop se hat-ti hai: pehle '{' se
     * aakhiri '}' tak ka hissa hi asal ummeedwaar hai.
     * Kuch bhi na bane to [Parsed.Plain] — yani jawab
     * fail NAHI hota, text hi aakhiri jawab ban jata hai.
     */
    private fun parseProtocol(raw: String): Parsed {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Parsed.Plain(text)
        val candidate = text.substring(start, end + 1)
        val obj = try {
            JSONObject(candidate)
        } catch (t: Throwable) {
            return Parsed.Plain(text)
        }
        return when (obj.optString("action")) {
            "tool" -> {
                val tool = obj.optString("tool").trim()
                if (tool.isEmpty()) {
                    Parsed.Plain(text)
                } else {
                    Parsed.Tool(
                        tool,
                        obj.optJSONObject("args") ?: JSONObject(),
                        obj.optString("say").trim().takeIf { it.isNotEmpty() }
                    )
                }
            }
            "final" -> {
                val say = obj.optString("say").trim()
                if (say.isEmpty()) Parsed.Plain(text) else Parsed.Final(say)
            }
            else -> Parsed.Plain(text)
        }
    }

    /** Report ke liye chhota kadam-naam: auzaar + uska mukhya arg. */
    private fun stepLabel(tool: String, args: JSONObject): String {
        val mainArg = when (tool) {
            "open_app" -> args.optString("app")
            "mobile_data", "torch" -> args.optString("state")
            "web_search", "youtube_play" -> args.optString("query").take(20)
            else -> ""
        }
        return if (mainArg.isEmpty()) tool else "$tool($mainArg)"
    }

    /**
     * Daud ki report "jarvis_diag" me band karo — sirf
     * kadmon ke naam/natije. Report likhne ki gadbad
     * asal jawab ko kabhi nahi rokti.
     */
    private fun saveRunSummary(context: Context, summary: String) {
        try {
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_AGENT_RUN, summary)
                .apply()
        } catch (t: Throwable) {
            // Report bonus hai; asal jawab wala raasta alag hai.
        }
    }

    /** Ek kaam ke liye zyada se zyada itne round. */
    private const val MAX_ROUNDS = 4

    private const val DIAG_PREFS_NAME = "jarvis_diag"
    private const val KEY_LAST_AGENT_RUN = "last_agent_run"

    /**
     * Beech me atki daud ka imaandaar jawab — jhootha
     * "ho gaya" kabhi nahi.
     */
    private const val PARTIAL_REPLY =
        "कुछ काम हो गया है, पर पूरा काम मैं ख़त्म नहीं कर पाई।"

    /**
     * Maya ki shaksiyat + AGENT PROTOCOL. Mastishk ko
     * saaf bataya gaya hai: woh app ka EKMAATRA sir hai —
     * online kadam uske, phone wale kadam auzaaron se;
     * har jawab SIRF ek JSON object; "final" me wohi
     * kaho jo auzaaron ke natijon ne asal me kaha hai.
     */
    private const val AGENT_INSTRUCTION =
        "You are Maya, the voice and the single BRAIN of the Android app " +
            "ASHIP JARVIS AI, a personal assistant on the user's phone. " +
            "You understand the user's WHOLE request, split it into steps, " +
            "do the online steps yourself (answering questions, deciding, " +
            "choosing search terms) and order the phone steps through " +
            "tools, one at a time, until the WHOLE task is complete. " +
            "You understand Bengali, Hindi, English and Hinglish; the " +
            "user often speaks Bengali. Everything you say out loud must " +
            "be Hindi written in Devanagari script (the phone reads it " +
            "aloud with a Hindi voice), warm and a little playful, always " +
            "clean and respectful, and SHORT — spoken lines, not essays. " +
            "If asked your name, your final say is: मेरा नाम माया है।\n" +
            "\n" +
            "PROTOCOL — reply with ONLY a JSON object, no other text, " +
            "no markdown. Exactly one of these two shapes:\n" +
            "{\"action\":\"tool\",\"tool\":\"<tool name>\",\"args\":{...}," +
            "\"say\":\"<optional short progress line in Devanagari Hindi>\"}\n" +
            "{\"action\":\"final\",\"say\":\"<the complete spoken answer " +
            "in Devanagari Hindi>\"}\n" +
            "\n" +
            "TOOLS (request ONE per reply; after each tool result you " +
            "will get another turn — continue with the next tool or " +
            "finish with final):\n" +
            "- open_app, args {\"app\": \"<common app name, e.g. YouTube, " +
            "WhatsApp, Camera, Settings>\"} — opens an installed app. " +
            "Result says whether it really opened.\n" +
            "- mobile_data, args {\"state\": \"on\" | \"off\" | \"toggle\"} " +
            "— switches mobile data through the phone's quick-settings " +
            "tile. Result says what happened; it fails if the " +
            "accessibility service is off.\n" +
            "- torch, args {\"state\": \"on\" | \"off\"} — the flashlight.\n" +
            "- web_search, args {\"query\": \"<search terms>\"} — opens a " +
            "Google search in the browser.\n" +
            "- youtube_play, args {\"query\": \"<song or video name>\"} — " +
            "YouTube par query search karke pehla video chalata hai. " +
            "Use it for 'X gana/video chalao' type requests (play a " +
            "song or video). The result says whether the video really " +
            "started, only the search results opened, or it failed.\n" +
            "- get_time, args {} — returns the current time as text, for " +
            "you to use in your answer.\n" +
            "- get_date, args {} — returns the current date as text, for " +
            "you to use in your answer.\n" +
            "\n" +
            "RULES:\n" +
            "- A question that needs no phone action: answer it directly " +
            "with one final reply.\n" +
            "- A task needing phone actions: request the tools step by " +
            "step, then finish with a final that confirms ONLY what the " +
            "tool results actually said. NEVER claim a step succeeded " +
            "when its result says FAILED — say honestly what went wrong.\n" +
            "- Keep every \"say\" to 1–2 short spoken sentences."
}

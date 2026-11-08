package com.aship.jarvis.ai

import android.content.Context
import com.aship.jarvis.commands.PlayIntent
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
 * v31: Maya ki lambi-yaad — user ki bandhi baatein
 * ([MayaMemory], sirf phone par) har daud ki shuruaat
 * me instruction ke aakhir me sandarbh bankar judti
 * hain ([buildInstruction]), taaki niji sawalon ka
 * jawab mastishk bina auzaar ke de sake. Upar wala
 * report-niyam yahan aur sakht hai: yaad ka text
 * report me KABHI nahi aata (stepLabel par dekho).
 *
 * v32: ek aur auzaar — get_weather (AgentTools me):
 * mastishk shahar ka naam aur aaj/kal ka sanket
 * bhejta hai, auzaar Open-Meteo se asal sankhyaen
 * lata hai — mastishk mausam ka andaza khud NAHI
 * lagata. stepLabel me uska shahar bhi dikhta hai.
 *
 * v37: ek aur auzaar — screen_task (asal chal
 * [ScreenAgent] me): dusri app ke andar kai kadam
 * wala kaam screen chalakar poora karna. App band ho
 * to pehle open_app, phir screen_task — ye kram
 * instruction me saaf likha hai. Natije ki imaandari
 * (poora / pushti par ruka / fail, kahaan tak
 * pahuncha) auzaar ke text se hi aati hai.
 *
 * v40: ek aur auzaar — share_screenshot (asal chal
 * [AgentTools] me): is pal ki screen tasveer pakadkar
 * target app (default WhatsApp) ki share screen
 * kholna — tasveer chipki hui, par bhejna user ke
 * haath me rehta hai; instruction me ye imaandari
 * saaf likhi hai. Gallery-trash khaali karne wale
 * kaam ke liye bhi niyam juda hai: hamesha
 * screen_task, user ke apne shabdon me (app apna
 * suraksha playbook khud jodta hai).
 *
 * v48: SHAKSIYAT-BHASHA KA BANDHAN — bulane wala
 * chaahe to is daud ki boli-bhasha pakki kar sakta
 * hai ([run] ka replyLanguage): background me
 * "Maya" naam se bulaya gaya session SIRF Hindi
 * bolega aur "Tara" wala SIRF Bangla, user jis
 * bhasha me bhi baat kare. Bandhan instruction ke
 * aakhir me ek spasht override block bankar judta
 * hai jo LANGUAGE RULE se upar rehta hai. Na diya
 * jaye (saamne wali screen ka aam raasta) to bartav
 * bilkul v47 wala hi hai.
 *
 * v49: do aur sandarbh-block instruction me judte
 * hain — SEEKHE KAAM ([LearnedRoutines] ke bandhe
 * naamon ki compact suchi, taaki milan wala hukm
 * screen_task par jaye aur app uski bandhi chaal
 * khud jod sake) aur, taana session ke andar ki
 * daud ho to, SESSION SANDARBH (bulane wale ka
 * diya [run] ka sessionContext: pichhle hukm/
 * natije + pehle liye gaye screenshot ka sanket)।
 * Dono bhasha-bandhan wale override block se
 * PEHLE aate hain — woh sabse aakhir me hi rehta
 * hai, bilkul v48 jaisa।
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
        replyLanguage: PlayIntent.SpokenLang? = null,
        sessionContext: String? = null,
        callback: (String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val reply = try {
                runLoop(
                    appContext, history, userText, toolExecutor,
                    replyLanguage, sessionContext
                )
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
        toolExecutor: (String, JSONObject) -> AgentTools.ToolResult,
        replyLanguage: PlayIntent.SpokenLang?,
        sessionContext: String? = null
    ): String? {
        // Baatchit: purane turn + nayi baat; har round ke
        // baad model ka kaccha jawab aur auzaar ka natija
        // isme judte jate hain.
        val contents = mutableListOf<Pair<String, String>>()
        contents.addAll(history)
        contents.add("user" to userText)
        // v31: instruction is daud ke liye taiyaar hota
        // hai — bandhi hui yaad taaza padhkar uske
        // aakhir me sandarbh bankar judti hai.
        // v48: shaksiyat-bhasha ka bandhan ho to woh
        // override block sabke aakhir me judta hai.
        // v49: seekhe kaamon ke naam + (session ho to)
        // uska sandarbh bhi usi kataar me judte hain —
        // bhasha-bandhan phir bhi sabse aakhir me.
        val instruction = buildInstruction(context, replyLanguage, sessionContext)
        val steps = mutableListOf<String>()
        var lastSay: String? = null
        for (round in 1..MAX_ROUNDS) {
            // v58: poori provider chain — Gemini pehle
            // (uski purani kataar), phir Groq/OpenRouter/
            // Cloudflare/SambaNova/Cohere (jinki key set
            // hai). Koi provider fail ho ya 429 de to
            // chain chup-chaap agle par jati hai.
            var raw = ProviderChain.agentRound(
                context, instruction, contents
            )
            if (raw == null && round == 1 &&
                !GeminiBrain.lastChainWasQuota(context) &&
                !ProviderChain.lastChainWasQuota(context) &&
                GeminiBrain.isConfigured(context) &&
                ProviderChain.configuredCount(context) == 1
            ) {
                // v51 (item 9): poori TEXT kataar (Lite
                // pehle) chup rahi aur wajah kota NAHI
                // hai — aksar ye dheele net par bikhre
                // timeout hote hain. Haar maan-ne se
                // pehle EK baar vaikalpik kataar (mukhya
                // model pehle) aazmao. Kota wali haar par
                // dobara koshish bekaar hai (har model
                // bandh hai) — isliye woh shart upar hai।
                // v58: ye dobara koshish sirf tab jab
                // chain me Gemini ke alawa koi provider
                // tha hi nahi — warna chain ne pehle hi
                // baaki provider aazma liye hain।
                raw = GeminiBrain.requestAgentRound(
                    context, instruction, contents,
                    GeminiBrain.RequestClass.TEXT_MAIN_FIRST
                )
                if (raw != null) {
                    steps.add("retry-mainfirst=OK")
                }
            }
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

    /**
     * v31: Is daud ka instruction — sthair [AGENT_INSTRUCTION]
     * ke aakhir me, bandhi hui yaad ho to, uska sandarbh-block
     * ([MayaMemory.contextBlock], taaza padha hua). Mastishk
     * in baaton se niji sawalon ka jawab seedha deta hai —
     * auzaar bulane ki zaroorat nahi. Yaad khali ho to
     * instruction bilkul pehle jaisa hi rehta hai.
     */
    private fun buildInstruction(
        context: Context,
        replyLanguage: PlayIntent.SpokenLang? = null,
        sessionContext: String? = null
    ): String {
        var text = AGENT_INSTRUCTION
        MayaMemory.contextBlock(context)?.let { block ->
            text += "\n\nMEMORY — the user has asked you to remember " +
                "these things; use them to answer personal " +
                "questions directly, with NO tool call:\n" + block
        }
        // v50: MALIK KA NAAM — user ne khud bolkar
        // bandha ho to mastishk use beech-beech me
        // naam lekar pukare (har line me nahi —
        // bandha block khud yehi kehta hai)। Naam
        // na bandha ho to block aata hi nahi।
        UserProfile.nameInstruction(context)?.let { block ->
            text += "\n\n" + block
        }
        // v49: SEEKHE KAAM — bandhe routines ke
        // sirf naam (kadam app khud screen_task me
        // jodta hai); mastishk in naamon se milan
        // karke screen_task bulaye।
        LearnedRoutines.instructionBlock(context)?.let { block ->
            text += "\n\n" + block
        }
        // v52: KAAM KE NIYAM — bandhe niyam (shart
        // wale aur pasand wale) — mastishk inhe har
        // baar bina bole mane।
        BrainRules.instructionBlock(context)?.let { block ->
            text += "\n\n" + block
        }
        // v49: TAANA SESSION ka sandarbh — session
        // ke pichhle hukm/natije aur pehle liye
        // gaye screenshot ka sanket, taaki "তিন
        // নম্বরটা" / "ওই স্ক্রিনশটটা" jaise ishare
        // peeche wali baat se judein। Session ke
        // bahar ye block aata hi nahi।
        if (!sessionContext.isNullOrBlank()) {
            text += "\n\nCONTINUOUS SESSION CONTEXT:\n" +
                sessionContext.trim()
        }
        // v48: shaksiyat ka bhasha-bandhan — ye block
        // instruction ke sabse aakhir me rehta hai aur
        // upar wale LANGUAGE RULE se upar hai: is
        // session me boli SIRF bandhi bhasha me hogi,
        // user kisi bhi bhasha me baat kare.
        when (replyLanguage) {
            PlayIntent.SpokenLang.HINDI -> text +=
                "\n\nPERSONA LANGUAGE OVERRIDE (highest priority): " +
                    "in this conversation you are MAYA, called by the " +
                    "name Maya/Jarvis. EVERY \"say\" line and final " +
                    "answer from you MUST be in Hindi written in " +
                    "Devanagari script ONLY — even if the user speaks " +
                    "or writes Bengali or English. Understand the user " +
                    "in any language, but always ANSWER in Hindi only."
            PlayIntent.SpokenLang.BENGALI -> text +=
                "\n\nPERSONA LANGUAGE OVERRIDE (highest priority): " +
                    "in this conversation you are TARA, called by the " +
                    "name Tara. EVERY \"say\" line and final answer " +
                    "from you MUST be in Bengali written in Bengali " +
                    "script ONLY — even if the user speaks or writes " +
                    "Hindi or English. Understand the user in any " +
                    "language, but always ANSWER in Bengali only."
            else -> Unit
        }
        return text
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
            "calculate" -> args.optString("expression").take(20)
            "set_timer" ->
                if (args.has("seconds")) {
                    args.optInt("seconds").toString()
                } else {
                    ""
                }
            "set_alarm" ->
                if (args.has("hour")) {
                    args.optInt("hour").toString() + ":" +
                        args.optInt("minute").toString().padStart(2, '0')
                } else {
                    ""
                }
            "call_person", "whatsapp_message" -> args.optString("name")
            "get_weather" -> args.optString("city")
            // v37: screen_task ka kaam user ke apne shabd
            // hain — report me kata hua roop kaafi hai.
            "screen_task" -> args.optString("task").take(20)
            // v40: share_screenshot ka nishana app — report
            // me sirf app ka naam (koi sampark/tasveer nahi).
            "share_screenshot" -> args.optString("app")
            // v31 PRIVACY: yaad wale auzaaron ke args —
            // khud yaad rakhi baatein/match — report me
            // KABHI nahi; sirf auzaar ka naam dikhega.
            "remember_fact", "recall_facts", "forget_fact" -> ""
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
            "user often speaks Bengali. LANGUAGE RULE: always reply in " +
            "the SAME language and script as the user's latest message " +
            "— a Bengali message gets a Bengali reply (Bengali script); " +
            "a Hindi message gets Hindi in Devanagari; English/Hinglish " +
            "gets the same natural mix. If the user asks you to switch " +
            "language (e.g. 'বাংলায় কথা বল' or 'हिंदी में बोलो'), switch at " +
            "once and confirm in one short line in the NEW language. " +
            "Keep every spoken line warm, a little playful, always " +
            "clean and respectful, and SHORT — spoken lines, not " +
            "essays. If asked your name, answer in the current " +
            "language (Bengali: আমার নাম মায়া। Hindi: मेरा नाम माया है।)\n" +
            "\n" +
            "PROTOCOL — reply with ONLY a JSON object, no other text, " +
            "no markdown. Exactly one of these two shapes:\n" +
            "{\"action\":\"tool\",\"tool\":\"<tool name>\",\"args\":{...}," +
            "\"say\":\"<optional short progress line in the user's language>\"}\n" +
            "{\"action\":\"final\",\"say\":\"<the complete spoken answer " +
            "in the user's language>\"}\n" +
            "\n" +
            "TOOLS (request ONE per reply; after each tool result you " +
            "will get another turn — continue with the next tool or " +
            "finish with final):\n" +
            "- open_app, args {\"app\": \"<common app name, e.g. YouTube, " +
            "WhatsApp, ChatGPT, Gemini, Camera, Settings>\"} — opens an installed app. " +
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
            "- calculate, args {\"expression\": \"<the arithmetic as a " +
            "plain expression, e.g. 125*4+30 or (200*10)/100>\"} — " +
            "computes the expression EXACTLY on the phone. For ANY " +
            "arithmetic (add, subtract, multiply, divide, percent), " +
            "ALWAYS use this tool and report its exact result — never " +
            "do maths in your head. Convert percent phrases yourself: " +
            "'200 er 10 percent' = (200*10)/100.\n" +
            "- get_battery, args {} — returns the battery percentage " +
            "and whether the phone is charging, from the real device " +
            "state. Use it for battery questions — never guess the " +
            "charge.\n" +
            "- get_network, args {} — returns the current connection " +
            "(none, Wi-Fi or mobile data) and whether internet is " +
            "available, from the real device state. Use it for " +
            "network questions — never guess.\n" +
            "- set_timer, args {\"seconds\": <total seconds as an " +
            "integer>} — sets a countdown timer in the phone's clock. " +
            "Convert the user's duration to SECONDS yourself: '10 " +
            "minute' = 600, 'aadha ghanta' (half an hour) = 1800, " +
            "'1 ghanta' = 3600. Use it for timer requests.\n" +
            "- set_alarm, args {\"hour\": <0-23>, \"minute\": <0-59>, " +
            "\"label\": \"<optional short label>\"} — sets an alarm in " +
            "the phone's clock. hour is 24-hour time ('subah 7 baje' " +
            "= hour 7, 'raat 10 baje' = hour 22); always send minute " +
            "(0 when the user means on the hour). Use it for alarm " +
            "requests.\n" +
            "- call_person, args {\"name\": \"<person's name as the " +
            "user said it>\"} — contacts me naam dhundhkar us number " +
            "ke saath dialer kholta hai (call khud NAHI lagata — " +
            "user khud call wala button dabata hai). Agar result me " +
            "kai naam aayein to user se puchho kaun sa wala, aur " +
            "jawab milne par usi poore naam se call_person dobara " +
            "chalao. Use it for 'X ko call karo' type requests.\n" +
            "- whatsapp_message, args {\"name\": \"<person's name>\", " +
            "\"message\": \"<message text exactly as the user " +
            "dictated>\"} — WhatsApp chat kholkar sandesh likh deta " +
            "hai; bhejna user khud dabata hai — kabhi mat kaho ki " +
            "sandesh bhej diya. Agar result me kai naam aayein to " +
            "user se puchho kaun sa wala, aur jawab milne par usi " +
            "poore naam se whatsapp_message dobara chalao. Use it " +
            "for 'X ko WhatsApp par likho / message karo' type " +
            "requests.\n" +
            "- remember_fact, args {\"fact\": \"<one clean " +
            "standalone fact>\"} — remembers something on the " +
            "phone for good. Use it when the user says 'yaad " +
            "rakho / মনে রাখো' (remember this). Compose ONE " +
            "clean standalone fact from what the user said — " +
            "e.g. the user says 'yaad rakho, meri bike ka " +
            "number WB12AB3456 hai' → fact: 'User ki bike ka " +
            "number WB12AB3456 hai'.\n" +
            "- recall_facts, args {} — returns everything you " +
            "have been asked to remember, as a numbered list. " +
            "Use it when the user asks 'kya-kya yaad hai' " +
            "(what all do you remember).\n" +
            "- forget_fact, args {\"match\": \"<a word or " +
            "phrase>\"} — removes every remembered fact " +
            "containing the match. Use it when the user says " +
            "'bhool jao / ভুলে যাও' (forget that).\n" +
            "- get_weather, args {\"city\": \"<place name>\", " +
            "\"when\": \"today\" | \"tomorrow\"} — Open-Meteo se " +
            "asal mausam laata hai (sabse zyada/kam taapmaan, " +
            "barish ki sambhavna). Shahar ka naam user ne jo " +
            "bola wahi bhejo; aaj ke liye when='today', kal ke " +
            "liye 'tomorrow'. Mausam ka andaza khud mat lagao. " +
            "Use it for weather questions.\n" +
            "- screen_task, args {\"task\": \"<the task in the " +
            "user's own words>\"} — drives the screen of another " +
            "app step by step (reading the screen, tapping, " +
            "scrolling, typing AND PASTING text) to complete a " +
            "multi-step task INSIDE an app that no other tool " +
            "covers — e.g. pasting a text into ChatGPT or " +
            "Gemini and sending it, sending a live location " +
            "in WhatsApp, or picking the latest gallery video " +
            "into the CapCut editor. The screen agent puts " +
            "text into another app by PASTING it from the " +
            "clipboard (or typing it) — that IS the paste " +
            "mechanism, and it works in ChatGPT, Gemini, " +
            "WhatsApp, Telegram and any other app. If the " +
            "app is not open yet, first call open_app for it, " +
            "then screen_task with the full task (including " +
            "the app and person's names AND the FULL text to " +
            "paste/send, in the user's own words). " +
            "Send/pay/delete taps inside are confirmed with the " +
            "user by the app itself — the task stops if the user " +
            "does not say yes. The result says honestly whether " +
            "the task completed, stopped, or failed, and how far " +
            "it got; when it carries the screen agent's own " +
            "summary, relay that as your final answer. NEVER " +
            "claim a message or location was sent unless the " +
            "result says the task COMPLETED.\n" +
            "- share_screenshot, args {\"app\": \"<optional target " +
            "app name — WhatsApp by default>\"} — captures the " +
            "CURRENT screen and opens that app's share screen " +
            "with the screenshot attached. The user still picks " +
            "the contact/chat and taps Send themselves — the " +
            "image is NEVER sent by itself; never say it was " +
            "sent. Use it when the user asks to send/share a " +
            "screenshot (e.g. 'screenshot ta amar WhatsApp e " +
            "pathao'): first reach the requested page or state " +
            "(open_app / screen_task as needed), THEN call " +
            "share_screenshot as the last step, with the final " +
            "screen visible.\n" +
            "\n" +
            "RULES:\n" +
            "- Paste / copy-paste / send this text in another app " +
            "(ChatGPT, Gemini, WhatsApp, Telegram, any app): " +
            "ALWAYS route it to open_app (if the app is not open " +
            "yet) + screen_task with the FULL text in the user's " +
            "own words. NEVER refuse with 'no tool' or 'no " +
            "clipboard tool' — the screen agent IS the paste " +
            "tool: it pastes from the clipboard into the app's " +
            "message box and can send it. If the user asks " +
            "'can you copy-paste?' the answer is YES — offer " +
            "to do it right away (or just do it when they give " +
            "you the text and the app).\n" +
            "- Pressing the send button in another app (ChatGPT, " +
            "Gemini, WhatsApp, Telegram, any chat app) is part " +
            "of your job and is ALLOWED. There is NO system rule " +
            "and NO security rule that stops you from pressing " +
            "the send button. When the user tells you to send, " +
            "press it yourself through the screen agent. NEVER " +
            "say that system security blocks you from pressing " +
            "send, and NEVER ask the user to press the send " +
            "button themselves — just do it.\n" +
            "- Emptying a gallery Trash / Recently deleted folder: " +
            "ALWAYS route it to screen_task with the task in the " +
            "user's own words — the app attaches its own " +
            "permanent-deletion safety playbook to such tasks " +
            "itself; never try to do it with other tools.\n" +
            "- A question that needs no phone action: answer it directly " +
            "with one final reply.\n" +
            "- A task needing phone actions: request the tools step by " +
            "step, then finish with a final that confirms ONLY what the " +
            "tool results actually said. NEVER claim a step succeeded " +
            "when its result says FAILED — say honestly what went wrong.\n" +
            "- Keep every \"say\" to 1–2 short spoken sentences.\n" +
            "- The app's OWN send button: the user can say " +
            "'সেন্ড করো / send karo' and the app itself presses " +
            "its own Send button for the text typed in its command " +
            "box — that is allowed and built in. NEVER say the send " +
            "button can't be pressed for safety reasons. If the user " +
            "asks why you can't press the send button, answer that " +
            "you CAN — just type text in the box and say " +
            "'সেন্ড করো'.\n"
}

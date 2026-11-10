package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Phase 3 (v21): Maya ki online buddhi — Google Gemini.
 *
 * Ye sirf BAATCHIT aur SAWALON ke liye hai: offline CommandEngine
 * kisi utterance ko na sambhal paye, tabhi (aur sirf tab, jab user
 * ne key di ho, mode Offline na ho aur net chalu ho — ye teenon
 * jaanch bulane wala karta hai) Gemini ko ek mauka milta hai.
 * Saare phone wale kaam (app kholna, data, torch...) hamesha
 * offline engine se hi hote hain — Gemini unme haath nahi dalta.
 *
 * API key SIRF phone par rehti hai: user app ke andar wale khane
 * me khud bharta hai, woh is file ke SharedPreferences
 * ("jarvis_ai") me band rehti hai. Key code me kahin NAHI hai,
 * aur na hi kabhi log/print hoti hai — header seedha connection
 * par lagta hai, koi string me ghumti nahi.
 *
 * Koi bhi gadbad (net band, galat key, khaali jawab, parse fail)
 * callback(null) ban jati hai — bulane wala apne purane offline
 * raaste par gir jata hai, user ko koi error nahi dikhta.
 *
 * v23: baatchit ke liye modelon ki kataar hai ([MAIN_MODELS]) —
 * naye key ke liye gemini-2.5-flash generateContent par 404
 * deta hai (models list me ab bhi dikhta hai, isliye key
 * jaanch pass hokar bhi jawab nahi aata tha). Pehla model
 * 404/503 de ya khaali nikle to agla aazmaya jata hai; key
 * ya request wali gadbad (400/401/403) par wahin ruk jate
 * hain — woh model badalne se nahi sudhregi.
 *
 * v24: user ke phone ka net bahut dheema hai aur bheed ke
 * samay Google ke jawab bhi der se aate hain — purani 20 s
 * wali padhne ki seema par har koshish timeout banke mar
 * jati thi aur bina kisi saboot ke offline fallback bol
 * padta tha. Isliye chat ki seemayein badhai (30 s / 60 s)
 * aur har chat() koshish ki report — kaun sa model, kitni
 * der, OK/code/timeout/net — SharedPreferences
 * "jarvis_diag" me band hoti hai, jo app ki screen par
 * dikhti hai. Key, sawal aur jawab report me KABHI nahi
 * aate.
 *
 * v25: ye file ab sirf TRANSPORT aur key ka bhandaar hai —
 * modelon ki kataar, timeout, koshish-report aur key ki
 * raksha yahin rehti hai. Uske upar chalne wala AGENT
 * (protocol, kadmon ki kataar, auzaar bulana) AgentBrain
 * me hai; woh har round ke liye [requestAgentRound] se
 * ek kachhi koshish maangta hai — kataar aur report ke
 * niyam chat walon se ek hi hain, isliye "Online koshish"
 * wali report agent ke round bhi dikhati hai.
 *
 * v36: kataar ke "agle model par jao" wale sanketon me
 * 429 (quota/rate-limit) aur 500 bhi jud gaye — v35 tak
 * pehle model ka 429 poori kataar ko wahin rok deta tha
 * aur agent round 1 par bina baaki model aazmaye mar
 * jata tha. Saath hi kataar ka kul nateeja (ok / quota /
 * failed) alag band hota hai ([lastChainWasQuota]), taaki
 * saare model 429 par marein to bulane wala jhootha aam
 * jawab ki jagah imaandaar "online seema khatm" bol sake.
 *
 * v43: KOTA MANAGER jud gaya ([QuotaManager]). Har
 * koshish ab ek VARG ([RequestClass]) ke saath aati
 * hai: TEXT (baatchit, agent round, screen ke node-
 * kadam) pehle saste Lite modelon se guzarti hai aur
 * mukhya kataar uski aakhiri sharan hai; VISION
 * (tasveer wale kadam) seedha mukhya kataar par rehti
 * hai. Jo model aaj 429 de chuka hai ya is project
 * ke liye hai hi nahi (Lite 404), use kataar din bhar
 * phir nahi thokti — ginati QuotaManager rakhta hai.
 *
 * v48: do badlaav. (1) Naya varg VISION_LITE —
 * ScreenAgent ke tasveer-kadmon ki dusri sharan:
 * mukhya kataar kota/thand se mari ho to tasveer
 * Lite modelon ko jati hai (Lite bhi tasveer samajh
 * sakte hain; Set-of-Mark nishan tap ko bandhe hisse
 * par hi rakhte hain). (2) 429 wala niyam QuotaManager
 * ke naye thand-wale roop se chalta hai: kataar
 * exhausted ke saath-saath THAND me baithe model ko
 * bhi chhodti hai, aur safal jawab par us model ka
 * thand-daag dhul jata hai ([QuotaManager
 * .recordSuccess]). "Quota" wala kul nateeja ab
 * tabhi banta hai jab is pal uplabdh har model
 * 429 / thand / exhausted me ho.
 */
object GeminiBrain {

    private val executor = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------
    // API key — sirf phone par (SharedPreferences "jarvis_ai")
    // ------------------------------------------------------------------

    /** Bhari hui key, ya null jab koi key set na ho. */
    fun getKey(context: Context): String? =
        prefs(context).getString(KEY_GEMINI_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_KEY, key.trim()).apply()
    }

    fun clearKey(context: Context) {
        prefs(context).edit().remove(KEY_GEMINI_KEY).apply()
    }

    /** Key set hai ya nahi — key ka text kabhi bahar nahi jata. */
    fun isConfigured(context: Context): Boolean = getKey(context) != null

    /**
     * v22: ummeedwaar key ki JAANCH — save karne se pehle.
     * Models wali halki list-waali call [key] (jo user ne abhi
     * likhi hai, band ki hui purani key NAHI) ke saath ki jati
     * hai. Callback isi class ke executor thread par aata hai:
     * (true, 200) = key chalti hai; (false, HTTP code) = server
     * ne mana kiya; (false, -1) = net/apraadhi gadbad, jaanch
     * ho hi nahi payi. Key kabhi log nahi hoti — seedha header
     * me jaati hai.
     */
    fun validateKey(key: String, callback: (Boolean, Int) -> Unit) {
        val candidate = key.trim()
        executor.execute {
            val code = try {
                var connection: HttpURLConnection? = null
                try {
                    connection =
                        (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = VALIDATE_TIMEOUT_MS
                            readTimeout = VALIDATE_TIMEOUT_MS
                            setRequestProperty("x-goog-api-key", candidate)
                        }
                    connection.responseCode
                } finally {
                    connection?.disconnect()
                }
            } catch (e: Exception) {
                -1
            }
            callback(code == HttpURLConnection.HTTP_OK, code)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Baatchit
    // ------------------------------------------------------------------

    /**
     * Gemini se ek jawab maango. [history] pichhle user/model
     * turn hain (role "user"/"model" wali jodiyan), [userText]
     * naya sawal. Callback ISI class ke executor thread par aata
     * hai — bulane wala apne thread par le jaye. Safal jawab par
     * text, warna null.
     */
    fun chat(
        context: Context,
        history: List<Pair<String, String>>,
        userText: String,
        callback: (String?) -> Unit
    ) {
        val key = getKey(context.applicationContext)
        if (key == null) {
            executor.execute { callback(null) }
            return
        }
        executor.execute {
            callback(requestReply(context.applicationContext, key, history, userText))
        }
    }

    /**
     * v23: [MAIN_MODELS] ki kataar me ek-ek karke koshish — pehla
     * model 404 (naye key par band) / 503 (bheed) de ya 200
     * par khaali jawab nikle to agla; net gadbad (-1) par bhi
     * agle ko ek mauka. 400/401/403 jaisi key/request wali
     * gadbad par wahin ruk jao — model badalna bekaar hai.
     * Sab khatm ho jayein to null.
     *
     * v24: har koshish (model, nateeja, kitni der lagi)
     * report me judti hai aur kataar khatm hone par woh
     * report "jarvis_diag" me band ho jati hai — screen par
     * "Online koshish (aakhiri)" line se user khud dekh
     * sakta hai ki online koshish me asal me hua kya tha.
     */
    private fun requestReply(
        context: Context,
        key: String,
        history: List<Pair<String, String>>,
        userText: String
    ): String? {
        val body = buildRequestBody(history, userText).toString()
        // v43: baatchit TEXT varg hai — Lite kataar pehle.
        return requestWithChain(context, key, body, RequestClass.TEXT)
    }

    /**
     * v43: koshish ka varg. TEXT = bina tasveer wala
     * kaam (baatchit, agent round, screen ke node-
     * kadam) — saste Lite model pehle, mukhya kataar
     * aakhir me. VISION = tasveer wala kadam — seedha
     * mukhya kataar, kyunki tasveer samajhne ka kaam
     * mukhya modelon ke zimme hi rakha gaya hai.
     *
     * v48: VISION_LITE = tasveer wala kadam, par
     * kataar sirf Lite modelon ki — ScreenAgent ki
     * dusri sharan jab mukhya kataar kota/thand se
     * mari ho (neeche [requestWithChain] aur
     * ScreenAgent ka v48 wala nota dekho).
     */
    enum class RequestClass { TEXT, VISION, VISION_LITE, TEXT_MAIN_FIRST }

    /**
     * v25: [MAIN_MODELS] ki kataar par ek taiyaar-shuda body
     * chalao — chat aur agent dono raaston ki ek hi jagah,
     * taaki kataar ke niyam (404/503/khaali par agla model,
     * 400/401/403 par rok, net gadbad par bhi ek mauka) aur
     * koshish-report kabhi alag na hon. Kataar khatm hone
     * par report "jarvis_diag" me band hoti hai (v24 wala
     * [saveLastAttempt]) aur pehla safal jawab laut-ta hai;
     * koi safal na ho to null. (v24 tak ye loop requestReply
     * ke andar tha — harkat byte-for-byte wahi hai.)
     */
    private fun requestWithChain(
        context: Context,
        key: String,
        body: String,
        requestClass: RequestClass
    ): String? {
        // v43: varg ke hisaab se kataar banti hai.
        // TEXT pehle Lite modelon se guzregi, mukhya
        // kataar uske peeche; VISION seedha mukhya;
        // v48: VISION_LITE sirf Lite kataar.
        val fullChain = when (requestClass) {
            RequestClass.TEXT -> LITE_MODELS + MAIN_MODELS
            RequestClass.VISION -> MAIN_MODELS
            RequestClass.VISION_LITE -> LITE_MODELS
            // v51 (item 9): wahi TEXT kataar ulti chaal
            // me — mukhya model pehle. AgentBrain round-1
            // poori tarah khaali laute (aur wajah kota na
            // ho) tab ek hi baar is vaikalpik kataar se
            // dobara koshish ke liye. Aam raasta TEXT hi
            // rehta hai (Lite pehle — kota bachta hai,
            // isiliye report me Lite ki koshishein zyada
            // dikhti hain; woh bandobast hai, kharabi
            // nahi)।
            RequestClass.TEXT_MAIN_FIRST -> MAIN_MODELS + LITE_MODELS
        }
        // v43: jo model aaj 429 de chuka hai (khatm) ya
        // is project ke liye hai hi nahi (uplabdh-nahi),
        // use shuru se hi chhod do — din bhar ek hi
        // deewaar par sir phodna kota ki barbadi hai.
        // 429 par bikhre hue modelon ki ginti alag
        // rakho: kataar ka "quota" nateeja (v36) unpar
        // bhi imandaar rahe (neeche dekho).
        // v48: THAND me baitha model bhi yahi chhod
        // milta hai aur wahi ginti me jata hai — is pal
        // uplabdh har model 429/thand/exhausted me ho
        // tabhi kul nateeja "quota" banta hai.
        var skippedExhausted = 0
        val chain = fullChain.filter { model ->
            when {
                QuotaManager.isExhausted(context, model) -> {
                    skippedExhausted++
                    false
                }
                QuotaManager.isCoolingDown(context, model) -> {
                    skippedExhausted++
                    false
                }
                QuotaManager.isUnavailable(context, model) -> false
                else -> true
            }
        }
        val attempts = mutableListOf<String>()
        val codes = mutableListOf<Int>()
        var result: String? = null
        for (model in chain) {
            // v43: har asal HTTP koshish ginati me likho —
            // safal ho ya fail, kota wahin kharch hota hai.
            QuotaManager.recordAttempt(context, model)
            val startedAtMs = System.currentTimeMillis()
            val attempt = tryModel(key, model, body)
            val seconds = (System.currentTimeMillis() - startedAtMs) / 1000
            attempts.add("$model: ${attempt.outcome} (${seconds}s)")
            codes.add(attempt.code)
            // v43: 429 aaya to model aaj ke liye khatm —
            // agli daud ise shuru se chhod degi.
            if (attempt.code == 429) {
                QuotaManager.record429(context, model)
            }
            if (attempt.code == HttpURLConnection.HTTP_OK && attempt.reply != null) {
                result = attempt.reply
                // v48: safal jawab — is model ka thand
                // wala daag dhul gaya (QuotaManager ka
                // naya niyam); agli 429 iske liye phir
                // "pehli" mani jayegi.
                QuotaManager.recordSuccess(context, model)
                break
            }
            // v43: Lite model ka 404 (ya "model not found"
            // wala 400) matlab woh is project ke liye hai
            // hi nahi — jaise naye keyon par 2.5-flash
            // 404 deta tha. Use aaj ke liye uplabdh-nahi
            // nishan lagao aur CHUP-CHAAP aage badho:
            // user ki koshish ek Lite ke peeche kabhi
            // nahi marti; saare Lite na milein to kataar
            // mukhya modelon par waise hi chalti hai
            // jaise v42 tak chalti thi.
            if (model in LITE_MODELS &&
                (attempt.code == HttpURLConnection.HTTP_NOT_FOUND ||
                    (attempt.code == HttpURLConnection.HTTP_BAD_REQUEST &&
                        attempt.modelNotFound))
            ) {
                QuotaManager.recordUnavailable(context, model)
                continue
            }
            // v36: 429 (quota / rate-limit) aur 500 bhi "agle
            // model par jao" wali gadbadiyan hain. v35 tak 429
            // is kataar me tha hi nahi — pehla model 429 deta
            // to poori kataar wahin ruk jati thi aur agent
            // round 1 par hi mar jata tha, jabki baaki modelon
            // ki seema alag ho sakti hai. 400/401/403 par ab
            // bhi wahin rukte hain — woh key/request ki
            // gadbad hai, model badalne se nahi sudhregi.
            val tryNext = attempt.code == HttpURLConnection.HTTP_OK ||
                attempt.code == HttpURLConnection.HTTP_NOT_FOUND ||
                attempt.code == 429 ||
                attempt.code == HttpURLConnection.HTTP_INTERNAL_ERROR ||
                attempt.code == HttpURLConnection.HTTP_UNAVAILABLE ||
                attempt.code == -1
            if (!tryNext) {
                break
            }
        }
        saveLastAttempt(context, attempts.joinToString(" | "))
        // v36: kataar ka kul nateeja alag se band karo —
        // bulane wale ko ye jaanna hai ki haar QUOTA (har
        // model 429) se hui ya kisi aur wajah se, taaki
        // Maya jhootha aam jawab dene ke bajaye sach bol
        // sake ki online seema khatm hai (lastChainWasQuota).
        // v43: aaj pehle se khatm (429) hone ki wajah se
        // chhode gaye model bhi is hisaab me 429 hi
        // maane jate hain — warna poori kataar bikhri
        // hone par bhi nateeja "failed" aata aur imaandaar
        // kota wali boli kabhi bolti hi nahi.
        val chainCodes = codes + List(skippedExhausted) { 429 }
        val outcome = when {
            result != null -> CHAIN_OUTCOME_OK
            chainCodes.isNotEmpty() && chainCodes.all { it == 429 } -> CHAIN_OUTCOME_QUOTA
            else -> CHAIN_OUTCOME_FAILED
        }
        saveChainOutcome(context, outcome)
        return result
    }

    /**
     * v36: aakhiri model-kataar quota par mari thi kya —
     * har model ne 429 diya ho tabhi true. Report wali
     * prefs ("jarvis_diag") me hi band hota hai; key,
     * sawal ya jawab isme kabhi nahi aata.
     */
    fun lastChainWasQuota(context: Context): Boolean = try {
        context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CHAIN_OUTCOME, null) == CHAIN_OUTCOME_QUOTA
    } catch (e: Exception) {
        false
    }

    /** v36: kataar ke kul natije ko "jarvis_diag" me band karo. */
    private fun saveChainOutcome(context: Context, outcome: String) {
        try {
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CHAIN_OUTCOME, outcome)
                .apply()
        } catch (e: Exception) {
            // Report bonus hai; asal jawab wala raasta isse alag hai.
        }
    }

    // ------------------------------------------------------------------
    // v25: agent ke round — AgentBrain ke liye kaccha transport
    // ------------------------------------------------------------------

    /**
     * Agent ke ek round ki koshish: di gayi [systemInstruction]
     * (agent wala protocol) aur poori [contents] baatchit
     * (user/model/tool-result wale turn, kram se) par model
     * kataar ki ek chaal. Model ka kaccha jawab-text laut-ta
     * hai (protocol JSON hone ki aasha hai — use samajhna
     * AgentBrain ka kaam hai); key na ho ya poori kataar
     * fail ho jaye to null.
     *
     * Ye BANDHA HUA (sync) call hai — bulane wale ke apne
     * background thread se hi bulana (AgentBrain apne
     * executor se bulata hai). Kataar, timeout aur
     * "Online koshish" report chat walon se ek hi hai.
     */
    internal fun requestAgentRound(
        context: Context,
        systemInstruction: String,
        contents: List<Pair<String, String>>,
        requestClass: RequestClass = RequestClass.TEXT
    ): String? {
        val appContext = context.applicationContext
        val key = getKey(appContext) ?: return null
        val body = buildAgentRequestBody(systemInstruction, contents).toString()
        // v43: bina tasveer wala round TEXT varg hai
        // (peeche se bulane par bhi yahi) — Lite kataar
        // pehle, mukhya kataar kota bachati hai.
        return requestWithChain(appContext, key, body, requestClass)
    }

    /**
     * v39: wahi agent round, par aakhiri user turn ke
     * saath EK tasveer (base64 JPEG) bhi jaati hai —
     * ScreenAgent ke vision kadmon ke liye (nishan-aankit
     * Set-of-Mark tasveer). Tasveer khaali/null ho to ye
     * [requestAgentRound] jaisa hi hai; kataar, timeout
     * aur report ke niyam bilkul wahi (requestWithChain)।
     * Purane bulane walon par koi asar nahi — ye sirf
     * ek joda hua raasta hai.
     */
    internal fun requestAgentRoundWithImage(
        context: Context,
        systemInstruction: String,
        contents: List<Pair<String, String>>,
        imageJpegBase64: String?,
        requestClass: RequestClass = RequestClass.VISION
    ): String? {
        if (imageJpegBase64.isNullOrEmpty()) {
            // Tasveer hai hi nahi to ye saade TEXT round
            // jaisa hai (v43: Lite kataar wala raasta).
            return requestAgentRound(
                context, systemInstruction, contents, RequestClass.TEXT
            )
        }
        val appContext = context.applicationContext
        val key = getKey(appContext) ?: return null
        val body = buildAgentRequestBody(
            systemInstruction, contents, imageJpegBase64
        ).toString()
        // v43: tasveer wala round aam taur par VISION
        // varg (mukhya kataar) hota hai; v48 se
        // bulane wala (ScreenAgent) mukhya kataar mari
        // hone par VISION_LITE bhi bhej sakta hai —
        // kataar yahan sirf [requestClass] se banti hai.
        return requestWithChain(appContext, key, body, requestClass)
    }

    /**
     * Agent wali body: [contents] ke saare turn kram se,
     * upar di gayi system instruction. Aakar (shape) chat
     * wali body se ek hi hai — isliye [buildRequestBody]
     * bhi ab isi par bana hai.
     *
     * v39: [imageJpegBase64] diya gaya ho to woh tasveer
     * AAKHIRI turn ke text ke saath ek inline_data hisse
     * bankar judti hai — ScreenAgent har vision kadam par
     * taazi tasveer ke saath wahi turn bhejta hai.
     */
    private fun buildAgentRequestBody(
        systemInstruction: String,
        contents: List<Pair<String, String>>,
        imageJpegBase64: String? = null
    ): JSONObject {
        val turns = JSONArray()
        contents.forEachIndexed { index, (role, text) ->
            val parts = JSONArray().put(JSONObject().put("text", text))
            if (index == contents.lastIndex &&
                !imageJpegBase64.isNullOrEmpty()
            ) {
                parts.put(
                    JSONObject()
                        .put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", imageJpegBase64)
                        )
                )
            }
            turns.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }
        return JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
            .put("contents", turns)
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("temperature", TEMPERATURE)
            )
    }

    /**
     * v24: aakhiri online koshish ki report SharedPreferences
     * "jarvis_diag" me band karo. Sirf model ke naam, nateeja
     * aur samay — key, sawal ya jawab isme KABHI nahi aata.
     * Report likhne me gadbad ho jaye to chup-chaap chhod
     * do — baatchit ka raasta isse kabhi nahi rukta.
     */
    private fun saveLastAttempt(context: Context, summary: String) {
        try {
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_GEMINI_ATTEMPT, summary)
                .apply()
        } catch (e: Exception) {
            // Report bonus hai; asal jawab wala raasta isse alag hai.
        }
    }

    /**
     * Ek model koshish ka nateeja (v24: report ke liye
     * outcome bhi saath). v43: [modelNotFound] — server
     * ki galti wali body me "model not found / not
     * supported" ka sanket mila kya; kataar sirf isi se
     * Lite ke 400 ko "model hai hi nahi" samajhti hai.
     * Body khud kahin band (save) NAHI hoti.
     */
    private class AttemptResult(
        val code: Int,
        val outcome: String,
        val reply: String?,
        val modelNotFound: Boolean = false
    )

    /**
     * Ek model par ek koshish. Net gadbad par code -1 —
     * outcome me "timeout" (samay khatm) aur "net" (baaki
     * gadbad) alag rakhe gaye hain, taaki report saaf bata
     * sake ki koshish asal me mari kis wajah se.
     */
    private fun tryModel(key: String, model: String, body: String): AttemptResult {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(GENERATE_CONTENT_BASE_URL + model + ":generateContent")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    // Key sirf header me jaati hai — kabhi log nahi hoti.
                    setRequestProperty("x-goog-api-key", key)
                    setRequestProperty("Content-Type", "application/json")
                }
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val reply = if (code == HttpURLConnection.HTTP_OK) {
                val responseText =
                    connection.inputStream.bufferedReader().use { it.readText() }
                parseReply(responseText)
            } else {
                null
            }
            // v43: galti wali body ka ek chhota tukda sirf
            // ye jaanchne ke liye padho ki kahin "model
            // not found" to nahi likha — Lite kataar ka
            // 400 isi se pehchana jata hai. Body kahin
            // band nahi hoti, log bhi nahi hoti.
            val modelNotFound = if (code != HttpURLConnection.HTTP_OK) {
                try {
                    val hint = connection.errorStream
                        ?.bufferedReader()?.use { it.readText() }
                        ?.take(600)
                    hint != null &&
                        (hint.contains("not found", ignoreCase = true) ||
                            hint.contains("not supported", ignoreCase = true))
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
            // Safal jawab par "OK", warna seedha HTTP code.
            AttemptResult(
                code,
                if (reply != null) "OK" else code.toString(),
                reply,
                modelNotFound
            )
        } catch (e: SocketTimeoutException) {
            AttemptResult(-1, "timeout", null)
        } catch (e: Exception) {
            AttemptResult(-1, "net", null)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Chat wali body — v25 se [buildAgentRequestBody] par
     * hi banti hai: persona wali instruction ke saath
     * history ke turn aur aakhir me naya user turn. Banti
     * hui JSON pehle wali se ek hi hai.
     */
    private fun buildRequestBody(
        history: List<Pair<String, String>>,
        userText: String
    ): JSONObject =
        buildAgentRequestBody(MAYA_PERSONA, history + ("user" to userText))

    /** candidates[0] ke saare text tukde jodkar ek jawab. Khaali ho to null. */
    private fun parseReply(responseText: String): String? {
        return try {
            val parts = JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val out = StringBuilder()
            for (i in 0 until parts.length()) {
                out.append(parts.getJSONObject(i).optString("text"))
            }
            out.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private const val PREFS_NAME = "jarvis_ai"
    private const val KEY_GEMINI_KEY = "gemini_key"

    // v24: koshish-report wali prefs file — panel-scan wali
    // report bhi yahi file istemal karti hai.
    private const val DIAG_PREFS_NAME = "jarvis_diag"
    private const val KEY_LAST_GEMINI_ATTEMPT = "last_gemini_attempt"

    // v36: model-kataar ke kul natije ki report wali chaabi
    // aur uske teen hi sambhavit moolya.
    private const val KEY_LAST_CHAIN_OUTCOME = "last_chain_outcome"
    private const val CHAIN_OUTCOME_OK = "ok"
    private const val CHAIN_OUTCOME_QUOTA = "quota"
    private const val CHAIN_OUTCOME_FAILED = "failed"

    private const val GENERATE_CONTENT_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * v23: baatchit ke model, kram se. Naye key ke liye
     * gemini-2.5-flash band ho chuka hai (generateContent par
     * 404), isliye sabse pehle gemini-3-flash-preview; phir
     * gemini-flash-latest (kabhi 503 bheed deta hai, isliye
     * beech me); aakhir me purana 2.5-flash — purane keyon
     * par woh ab bhi chalta hai.
     *
     * v43: ye ab MUKHYA (main) kataar hai — VISION kadam
     * aur TEXT ki aakhiri sharan. [QuotaManager] bhi
     * ginati ke liye yahi soochi padhta hai.
     */
    val MAIN_MODELS = listOf(
        "gemini-3-flash-preview",
        "gemini-flash-latest",
        "gemini-2.5-flash"
    )

    /**
     * v43: Lite kataar — TEXT varg ki pehli pasandeed.
     * Lite modelon ki free seema mukhya modelon se
     * kahin zyada hoti hai, isliye roz ka saade-text
     * kaam inse nikalta hai aur mukhya kota vision aur
     * mushkil kadmon ke liye bachta hai. Koi Lite is
     * project par na mile (404) to kataar use aaj ke
     * liye chhodkar mukhya par chali jati hai —
     * bartav v42 se kabhi badtar nahi hota.
     */
    val LITE_MODELS = listOf(
        "gemini-2.5-flash-lite",
        "gemini-flash-lite-latest"
    )

    /** v22: key jaanch ke liye models list wala pata. */
    private const val MODELS_URL =
        "https://generativelanguage.googleapis.com/v1beta/models"

    // v24: chat wali seemayein dheeme net ke liye badhai —
    // judne ke liye 30 s, jawab padhne ke liye 60 s. (Key
    // jaanch wali 15 s seema waisi hi rehti hai.)
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val VALIDATE_TIMEOUT_MS = 15_000
    // v23: 320 se 512 — naye model sochne (thinking) me bhi
    // token kha jate hain; jawab ki jagah bachi rahe isliye.
    // Bolne wala jawab persona phir bhi chhota rakhti hai.
    private const val MAX_OUTPUT_TOKENS = 512
    private const val TEMPERATURE = 0.8

    /**
     * Maya ki shaksiyat. Jawab Devanagari Hindi me, chhota aur
     * bola-jaane-layak — phone use awaaz se padhkar sunata hai.
     * Phone wale kaam chat se NAHI hote; woh seedha command bolne
     * par app ke apne engine se hote hain, isliye persona use
     * jhootha vaada karne se rokti hai.
     */
    private const val MAYA_PERSONA =
        "You are Maya, the voice of the Android app ASHIP JARVIS AI, a friendly " +
            "personal assistant on the user's phone. LANGUAGE RULE: reply in " +
            "the SAME language and script as the user's latest message — " +
            "Bengali in Bengali script, Hindi in Devanagari, English/Hinglish " +
            "in the same mix (the phone picks the matching voice itself). " +
            "You understand many major languages (Bengali, Hindi, English, " +
            "Urdu, Tamil, Telugu, Marathi, Gujarati, Punjabi, Malayalam, " +
            "Kannada, Odia, Assamese, Spanish, French, Arabic, Indonesian, " +
            "Portuguese, Russian) — reply in the user's language and script. If " +
            "the user asks you to switch language, switch at once and confirm " +
            "in one short line in the new language. Be warm and a little " +
            "playful, always clean and respectful. " +
            "Keep answers SHORT — 1 to 3 spoken sentences unless the user asks " +
            "for detail. If asked your " +
            "name, answer in the current language (Bengali: আমার নাম মায়া। " +
            "Hindi: मेरा नाम माया है।). Do not claim you can do phone actions " +
            "(opening apps, toggling data) in chat — those happen through the " +
            "app's own commands; if the user asks for such an action, gently " +
            "say to ask it as a direct command like 'YouTube खोलो'."
}

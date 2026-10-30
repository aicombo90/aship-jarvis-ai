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
 * v23: baatchit ke liye modelon ki kataar hai ([MODELS]) —
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
     * v23: [MODELS] ki kataar me ek-ek karke koshish — pehla
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
        val attempts = mutableListOf<String>()
        var result: String? = null
        for (model in MODELS) {
            val startedAtMs = System.currentTimeMillis()
            val attempt = tryModel(key, model, body)
            val seconds = (System.currentTimeMillis() - startedAtMs) / 1000
            attempts.add("$model: ${attempt.outcome} (${seconds}s)")
            if (attempt.code == HttpURLConnection.HTTP_OK && attempt.reply != null) {
                result = attempt.reply
                break
            }
            val tryNext = attempt.code == HttpURLConnection.HTTP_OK ||
                attempt.code == HttpURLConnection.HTTP_NOT_FOUND ||
                attempt.code == HttpURLConnection.HTTP_UNAVAILABLE ||
                attempt.code == -1
            if (!tryNext) {
                break
            }
        }
        saveLastAttempt(context, attempts.joinToString(" | "))
        return result
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

    /** Ek model koshish ka nateeja (v24: report ke liye outcome bhi saath). */
    private class AttemptResult(val code: Int, val outcome: String, val reply: String?)

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
            // Safal jawab par "OK", warna seedha HTTP code.
            AttemptResult(
                code,
                if (reply != null) "OK" else code.toString(),
                reply
            )
        } catch (e: SocketTimeoutException) {
            AttemptResult(-1, "timeout", null)
        } catch (e: Exception) {
            AttemptResult(-1, "net", null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildRequestBody(
        history: List<Pair<String, String>>,
        userText: String
    ): JSONObject {
        val contents = JSONArray()
        for ((role, text) in history) {
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", text))
                    )
            )
        }
        contents.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", userText))
                )
        )
        return JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", MAYA_PERSONA))
                )
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("temperature", TEMPERATURE)
            )
    }

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

    private const val GENERATE_CONTENT_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * v23: baatchit ke model, kram se. Naye key ke liye
     * gemini-2.5-flash band ho chuka hai (generateContent par
     * 404), isliye sabse pehle gemini-3-flash-preview; phir
     * gemini-flash-latest (kabhi 503 bheed deta hai, isliye
     * beech me); aakhir me purana 2.5-flash — purane keyon
     * par woh ab bhi chalta hai.
     */
    private val MODELS = listOf(
        "gemini-3-flash-preview",
        "gemini-flash-latest",
        "gemini-2.5-flash"
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
            "personal assistant on the user's phone. Speak in Hindi written in " +
            "Devanagari script (the phone reads your reply aloud with a Hindi " +
            "voice), warm and a little playful, always clean and respectful. " +
            "Keep answers SHORT — 1 to 3 spoken sentences unless the user asks " +
            "for detail. You may understand Bengali and Hinglish. If asked your " +
            "name, say: मेरा नाम माया है। Do not claim you can do phone actions " +
            "(opening apps, toggling data) in chat — those happen through the " +
            "app's own commands; if the user asks for such an action, gently " +
            "say to ask it as a direct command like 'YouTube खोलो'."
}

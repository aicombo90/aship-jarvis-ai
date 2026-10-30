package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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
            callback(requestReply(key, history, userText))
        }
    }

    private fun requestReply(
        key: String,
        history: List<Pair<String, String>>,
        userText: String
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(GENERATE_CONTENT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    // Key sirf header me jaati hai — kabhi log nahi hoti.
                    setRequestProperty("x-goog-api-key", key)
                    setRequestProperty("Content-Type", "application/json")
                }
            val body = buildRequestBody(history, userText).toString()
            connection.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val responseText =
                    connection.inputStream.bufferedReader().use { it.readText() }
                parseReply(responseText)
            }
        } catch (e: Exception) {
            null
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

    private const val GENERATE_CONTENT_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/" +
            "gemini-2.5-flash:generateContent"

    /** v22: key jaanch ke liye models list wala pata. */
    private const val MODELS_URL =
        "https://generativelanguage.googleapis.com/v1beta/models"

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val VALIDATE_TIMEOUT_MS = 15_000
    private const val MAX_OUTPUT_TOKENS = 320
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

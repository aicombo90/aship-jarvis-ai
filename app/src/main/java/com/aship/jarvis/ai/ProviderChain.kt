package com.aship.jarvis.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * v58: ONLINE FALLBACK CHAIN — ek provider fail ho to
 * agla, user ko bina bataye (design 2026-10-30 se tay,
 * v53 me sirf key rakhi gayi thi — chain ab bana hai).
 *
 * Kram: Gemini → Groq → OpenRouter free → Cloudflare →
 * SambaNova → Cohere. Gemini hamesha pehla — uska purana
 * raasta ([GeminiBrain.requestAgentRound], Lite+mukhya
 * model kataar, kota niyam) bilkul waisa hi chalta hai.
 * Uske baad har provider ek chhoti HTTP koshish hai;
 * jiski key set nahi hai woh chup-chaap chhod diya
 * jata hai (Cloudflare ko key ke saath Account ID bhi
 * chahiye — ek bhi na ho to woh bhi chhod do).
 *
 * Niyam (design wale hi):
 *  - Offline kaam kabhi yahan nahi aate — chain sirf
 *    online mastishk (AgentBrain ke text round) ke
 *    liye hai. Offline engine se jo ho jata hai woh
 *    net ko chhoota hi nahi.
 *  - Fail ho ya 429 (kota) — agle provider par jao,
 *    user ko switch ke baare me kuch nahi batana.
 *    Sirf tabhi "free seema khatm" boli jati hai jab
 *    set kiye gaye SAARE provider 429 par mare hon
 *    (kota natija neeche band hota hai, bulane wala
 *    use padhkar wahi imaandaar line bolta hai).
 *  - Key kabhi hard-code nahi, kabhi log nahi hoti,
 *    aur sirf usi provider ki API ko bheji jati hai
 *    jise bulaya jaye — doosre provider ki key us
 *    tak kabhi nahi jati. Diag report me sirf
 *    provider ke naam + nateeja + samay — key,
 *    sawal ya jawab kabhi nahi aata.
 *  - Har provider ki ek chhoti seema hai (10 s jodne
 *    ki, 25 s jawab padhne ki) — mara hua provider
 *    jawab ko minute bhar latka nahi sakta.
 *
 * Ye SYNC call hai — bulane wale ke apne background
 * thread se hi bulana (AgentBrain apne executor se
 * bulata hai). Koi bhi gadbad null bankar laut-ti hai,
 * kabhi crash nahi karti.
 */
object ProviderChain {

    /** Koi bhi ek provider ki key set hai kya — agent gate ke liye. */
    fun anyProviderConfigured(context: Context): Boolean =
        ProviderKeys.configuredCount(context) > 0

    /** Kitne provideron ki key set hai. */
    fun configuredCount(context: Context): Int =
        ProviderKeys.configuredCount(context)

    /**
     * Agent ka ek text round poori chain par: pehle
     * Gemini (purana raasta), phir baaki provider kram
     * se. Pehla safal jawab laut-ta hai; sab fail hon
     * to null (natija jarvis_diag me band hota hai).
     */
    fun agentRound(
        context: Context,
        systemInstruction: String,
        contents: List<Pair<String, String>>
    ): String? {
        val appContext = context.applicationContext
        var attempted = 0
        var quotaCount = 0
        val notes = mutableListOf<String>()

        // 1. Gemini pehle — purana raasta, wahi kataar.
        if (GeminiBrain.isConfigured(appContext)) {
            attempted++
            val geminiReply = try {
                GeminiBrain.requestAgentRound(
                    appContext, systemInstruction, contents
                )
            } catch (t: Throwable) {
                null
            }
            if (geminiReply != null) {
                saveChainOutcome(appContext, CHAIN_OUTCOME_OK, "gemini=OK")
                return geminiReply
            }
            if (GeminiBrain.lastChainWasQuota(appContext)) quotaCount++
            notes.add(
                "gemini=" +
                    if (GeminiBrain.lastChainWasQuota(appContext)) "quota"
                    else "failed"
            )
        }

        // 2. Baaki provider — kram se, sirf key wale.
        for (provider in FALLBACK_ORDER) {
            val key = ProviderKeys.getKey(appContext, provider) ?: continue
            if (provider == ProviderKeys.Provider.CLOUDFLARE &&
                ProviderKeys.getAccountId(appContext) == null
            ) {
                continue
            }
            attempted++
            val attempt = try {
                tryProvider(
                    appContext, provider, key,
                    systemInstruction, contents
                )
            } catch (t: Throwable) {
                ProviderAttempt(-1, null, false, "net")
            }
            notes.add(provider.name.lowercase() + "=" + attempt.outcome)
            if (attempt.reply != null) {
                saveChainOutcome(appContext, CHAIN_OUTCOME_OK, notes.joinToString(" | "))
                return attempt.reply
            }
            if (attempt.quota) quotaCount++
        }

        // 3. Kul natija — sab koshish 429 par mari hon
        // tabhi "quota" (free seema khatm); warna "failed".
        // Koi provider set hi nahi tha to bhi "failed" —
        // gate (anyProviderConfigured) waise bhi aise
        // call ko yahan aane nahi deta.
        val outcome = if (attempted > 0 && quotaCount == attempted) {
            CHAIN_OUTCOME_QUOTA
        } else {
            CHAIN_OUTCOME_FAILED
        }
        saveChainOutcome(appContext, outcome, notes.joinToString(" | "))
        return null
    }

    /**
     * v58: aakhiri chain daud kota par mari thi kya —
     * set kiye gaye saare provider 429 de chuke hon
     * tabhi true. Report wali prefs ("jarvis_diag") me
     * hi band hota hai; key, sawal ya jawab isme kabhi
     * nahi aata.
     */
    fun lastChainWasQuota(context: Context): Boolean = try {
        context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_CHAIN_OUTCOME, null) == CHAIN_OUTCOME_QUOTA
    } catch (e: Exception) {
        false
    }

    /** v58: chain ke kul natije + chhoti report ko "jarvis_diag" me band karo. */
    private fun saveChainOutcome(context: Context, outcome: String, summary: String) {
        try {
            context.getSharedPreferences(DIAG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_CHAIN_OUTCOME, outcome)
                .putString(KEY_LAST_CHAIN_ATTEMPT, summary)
                .apply()
        } catch (e: Exception) {
            // Report bonus hai; asal jawab wala raasta isse alag hai.
        }
    }

    // ------------------------------------------------------------------
    // Ek provider ki ek koshish
    // ------------------------------------------------------------------

    /** Ek provider koshish ka nateeja (v58). */
    private class ProviderAttempt(
        val code: Int,
        val reply: String?,
        val quota: Boolean,
        val outcome: String
    )

    /** Gemini ke baad ka kram (design wala hi). */
    private val FALLBACK_ORDER = listOf(
        ProviderKeys.Provider.GROQ,
        ProviderKeys.Provider.OPENROUTER,
        ProviderKeys.Provider.CLOUDFLARE,
        ProviderKeys.Provider.SAMBANOVA,
        ProviderKeys.Provider.COHERE
    )

    private fun tryProvider(
        context: Context,
        provider: ProviderKeys.Provider,
        key: String,
        systemInstruction: String,
        contents: List<Pair<String, String>>
    ): ProviderAttempt {
        return when (provider) {
            ProviderKeys.Provider.GEMINI -> ProviderAttempt(-1, null, false, "skip")
            ProviderKeys.Provider.GROQ -> postOpenAiCompatible(
                OPENAI_URL_GROQ, key, MODEL_GROQ,
                systemInstruction, contents, emptyMap()
            )
            ProviderKeys.Provider.OPENROUTER -> postOpenAiCompatible(
                OPENAI_URL_OPENROUTER, key, MODEL_OPENROUTER,
                systemInstruction, contents,
                mapOf(
                    "HTTP-Referer" to "https://github.com/aicombo90/aship-jarvis-ai",
                    "X-Title" to "ASHIP JARVIS AI"
                )
            )
            ProviderKeys.Provider.SAMBANOVA -> postOpenAiCompatible(
                OPENAI_URL_SAMBANOVA, key, MODEL_SAMBANOVA,
                systemInstruction, contents, emptyMap()
            )
            ProviderKeys.Provider.CLOUDFLARE -> postCloudflare(
                key, ProviderKeys.getAccountId(context) ?: return ProviderAttempt(
                    -1, null, false, "no-account"
                ),
                systemInstruction, contents
            )
            ProviderKeys.Provider.COHERE -> postCohere(
                key, systemInstruction, contents
            )
        }
    }

    /**
     * Groq / OpenRouter / SambaNova — OpenAI wali
     * chat-completions shakl. Jawab: choices[0].message.content.
     */
    private fun postOpenAiCompatible(
        url: String,
        key: String,
        model: String,
        systemInstruction: String,
        contents: List<Pair<String, String>>,
        extraHeaders: Map<String, String>
    ): ProviderAttempt {
        val body = JSONObject()
            .put("model", model)
            .put("messages", buildMessages(systemInstruction, contents))
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("temperature", TEMPERATURE)
        return postJson(url, key, body.toString(), extraHeaders) { responseText ->
            try {
                JSONObject(responseText)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content")
                    .trim().takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Cloudflare Workers AI — khata (account) wala pata,
     * body me wahi messages; jawab: result.response.
     */
    private fun postCloudflare(
        key: String,
        accountId: String,
        systemInstruction: String,
        contents: List<Pair<String, String>>
    ): ProviderAttempt {
        val url = CLOUDFLARE_BASE_URL + accountId + "/ai/run/" + MODEL_CLOUDFLARE
        val body = JSONObject()
            .put("messages", buildMessages(systemInstruction, contents))
            .put("max_tokens", MAX_OUTPUT_TOKENS)
        return postJson(url, key, body.toString(), emptyMap()) { responseText ->
            try {
                JSONObject(responseText)
                    .optJSONObject("result")
                    ?.optString("response")
                    ?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Cohere v2 chat — jawab: message.content[0].text.
     */
    private fun postCohere(
        key: String,
        systemInstruction: String,
        contents: List<Pair<String, String>>
    ): ProviderAttempt {
        val body = JSONObject()
            .put("model", MODEL_COHERE)
            .put("messages", buildMessages(systemInstruction, contents))
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("temperature", TEMPERATURE)
        return postJson(COHERE_URL, key, body.toString(), emptyMap()) { responseText ->
            try {
                val parts = JSONObject(responseText)
                    .optJSONObject("message")
                    ?.optJSONArray("content")
                if (parts == null) {
                    null
                } else {
                    val out = StringBuilder()
                    for (i in 0 until parts.length()) {
                        out.append(parts.getJSONObject(i).optString("text"))
                    }
                    out.toString().trim().takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Saanjha messages shakl — system instruction pehle,
     * phir contents: Gemini wala "model" yahan
     * "assistant" hai, baaki sab "user".
     */
    private fun buildMessages(
        systemInstruction: String,
        contents: List<Pair<String, String>>
    ): JSONArray {
        val messages = JSONArray()
        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemInstruction)
        )
        contents.forEach { (role, text) ->
            messages.put(
                JSONObject()
                    .put("role", if (role == "model") "assistant" else "user")
                    .put("content", text)
            )
        }
        return messages
    }

    /**
     * Ek POST — key sirf Authorization header me (kabhi
     * log nahi hoti). 200 par [parseReply] se jawab nikalo;
     * 429 ya 402 (kota/kharcha khatm) ko quota maano;
     * timeout/net par code -1. Jawab khaali ho to bhi
     * fail — agla provider aazmaya jayega.
     */
    private fun postJson(
        url: String,
        key: String,
        body: String,
        extraHeaders: Map<String, String>,
        parseReply: (String) -> String?
    ): ProviderAttempt {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    extraHeaders.forEach { (name, value) ->
                        setRequestProperty(name, value)
                    }
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
            // 429 (kota) ya 402 (kharcha khatm — OpenRouter)
            // matlab is provider ki free seema is pal khatm.
            val quota = code == 429 || code == 402
            ProviderAttempt(
                code,
                reply,
                quota,
                if (reply != null) "OK" else code.toString()
            )
        } catch (e: SocketTimeoutException) {
            ProviderAttempt(-1, null, false, "timeout")
        } catch (e: Exception) {
            ProviderAttempt(-1, null, false, "net")
        } finally {
            connection?.disconnect()
        }
    }

    // v58: chain ki report wali prefs file (GeminiBrain
    // wali "jarvis_diag" hi — screen par "Online
    // koshish" line ke paas chain ki report bhi dikhe).
    private const val DIAG_PREFS_NAME = "jarvis_diag"
    private const val KEY_LAST_CHAIN_OUTCOME = "last_provider_chain_outcome"
    private const val KEY_LAST_CHAIN_ATTEMPT = "last_provider_chain_attempt"
    private const val CHAIN_OUTCOME_OK = "ok"
    private const val CHAIN_OUTCOME_QUOTA = "quota"
    private const val CHAIN_OUTCOME_FAILED = "failed"

    // Har provider ki ek koshish ki seema — mara hua
    // provider jawab ko minute bhar latka nahi sakta.
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 25_000

    // Jawab ki lambai/andaaz Gemini wale jaisa hi —
    // chhota, bola-jaane-layak.
    private const val MAX_OUTPUT_TOKENS = 512
    private const val TEMPERATURE = 0.8

    // OpenAI-compatible chat-completions pate.
    private const val OPENAI_URL_GROQ =
        "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENAI_URL_OPENROUTER =
        "https://openrouter.ai/api/v1/chat/completions"
    private const val OPENAI_URL_SAMBANOVA =
        "https://api.sambanova.ai/v1/chat/completions"

    // Cloudflare Workers AI — khata (account) beech me judta hai.
    private const val CLOUDFLARE_BASE_URL =
        "https://api.cloudflare.com/client/v4/accounts/"

    // Cohere v2 chat.
    private const val COHERE_URL = "https://api.cohere.com/v2/chat"

    // Har provider ka sabse chhota/sasta free model.
    private const val MODEL_GROQ = "llama-3.1-8b-instant"
    private const val MODEL_OPENROUTER = "meta-llama/llama-3.1-8b-instruct:free"
    private const val MODEL_CLOUDFLARE = "@cf/meta/llama-3-8b-instruct"
    private const val MODEL_SAMBANOVA = "Meta-Llama-3.1-8B-Instruct"
    private const val MODEL_COHERE = "command-r7b-12-2024"
}

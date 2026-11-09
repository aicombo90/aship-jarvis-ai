package com.aship.jarvis.ai

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * v78: CHHOBI BANAO — Gemini se tasveer banana.
 *
 * User ne doosre Jarvis app me dekha: Gemini se anime
 * tasveer banti hai aur bhej di jati hai. Yahi kaam ab
 * is app me — "X-এর ছবি বানাও" bole to Maya Gemini ke
 * image model (gemini-2.5-flash-image) se tasveer
 * banakar dikhayegi.
 *
 * TRANSPORT: [GeminiBrain] ke text wale raaste se
 * ALAG — woh kataar (chain) sirf text jawab samajhti
 * hai; tasveer wale jawab (inlineData) usme kabhi
 * nahi aate. Isliye yahan ek seedha, ek-model wala
 * call hai: wahi base URL, wahi x-goog-api-key header
 * (key sirf phone par, "jarvis_ai" prefs se — code me
 * kabhi nahi), wahi HttpURLConnection wala dhang.
 *
 * IMAANDARI SABSE UPAR:
 *  - Sirf Gemini tasveer bana sakta hai — text wali
 *    fallback kataar (Groq / OpenRouter / Cloudflare /
 *    SambaNova / Cohere) tasveer NAHI bana sakti, isliye
 *    koi fallback nahi hai: key na ho to [NoKey], 429
 *    (quota) ho to [Quota], baaki gadbad par [Failed] —
 *    bulane wala user ko sach bolta hai.
 *  - Kabhi naqli / placeholder tasveer nahi dikhayi
 *    jati, purani tasveer ko nayi batakar nahi diya
 *    jata — safal jawab me asal base64 tasveer mili
 *    tabhi [Success], warna fail.
 *  - Tasveer banne me waqt lagta hai (dheeme net par
 *    30–60 s aam hai), isliye padhne ki seema 60 s —
 *    text chat wali 60 s seema jitni hi.
 */
object ImageGen {

    /** Ek generation ka nateeja — bulane wala inhi par bole. */
    sealed class ImageGenResult {
        /** Tasveer ban gayi: bytes + mime ("image/png" aam). */
        data class Success(val bytes: ByteArray, val mimeType: String) :
            ImageGenResult()

        /** Gemini key set hi nahi hai ("jarvis_ai" me nahi). */
        object NoKey : ImageGenResult()

        /** 429 — Gemini ki seema (quota / rate-limit) khatm. */
        object Quota : ImageGenResult()

        /** Baaki gadbad: HTTP code, ya -1 (timeout / net). */
        data class Failed(val code: Int) : ImageGenResult()
    }

    /** Tasveer banao — callback isi class ke executor thread par. */
    fun generate(
        context: Context,
        prompt: String,
        variation: Boolean,
        callback: (ImageGenResult) -> Unit
    ) {
        val key = GeminiBrain.getKey(context.applicationContext)
        if (key == null) {
            executor.execute { callback(ImageGenResult.NoKey) }
            return
        }
        executor.execute {
            callback(callModel(key, buildBody(prompt, variation)))
        }
    }

    /**
     * API wala sawal — user ka varnan jaisa hai waisa hi,
     * sirf upar ek chhoti si hidayat: tasveer banao.
     * Bhasha badli nahi jati (model Bangla/Hindi bhi
     * samajhta hai)। Dobara banane (variation) par ek
     * aur line: pehle wali se alag style/composition.
     */
    private fun buildBody(prompt: String, variation: Boolean): String {
        val instruction = if (variation) {
            "Generate an image: $prompt. Make it a fresh new " +
                "variation, different in style and composition " +
                "from the previous one."
        } else {
            "Generate an image: $prompt"
        }
        return JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", instruction)
                            )
                        )
                )
            )
            .put(
                "generationConfig",
                JSONObject().put(
                    "responseModalities",
                    JSONArray().put("TEXT").put("IMAGE")
                )
            )
            .toString()
    }

    /** Ek model par ek koshish — text chat wale [GeminiBrain] jaisa dhang. */
    private fun callModel(key: String, body: String): ImageGenResult {
        var connection: HttpURLConnection? = null
        return try {
            connection =
                (URL(GENERATE_CONTENT_BASE_URL + IMAGE_MODEL + ":generateContent")
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
            if (code == HttpURLConnection.HTTP_OK) {
                val responseText =
                    connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = parseImage(responseText)
                if (parsed != null) {
                    ImageGenResult.Success(parsed.first, parsed.second)
                } else {
                    // 200 aaya par tasveer nahi — model ne mana
                    // kiya (safety) ya aakar badal gaya; safal
                    // maan kar jhoothi tasveer kabhi nahi.
                    ImageGenResult.Failed(code)
                }
            } else if (code == 429) {
                ImageGenResult.Quota
            } else {
                ImageGenResult.Failed(code)
            }
        } catch (e: SocketTimeoutException) {
            ImageGenResult.Failed(-1)
        } catch (e: Exception) {
            ImageGenResult.Failed(-1)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Jawab me se tasveer nikalo — candidates[0] ke
     * parts me se pehla inlineData wala hissa (base64)।
     * Text wale hisse (model kabhi ek chhoti line bhi
     * deta hai) yahan kaam ke nahi — tasveer na mile
     * to null, kabhi khaali bytes nahi.
     */
    private fun parseImage(responseText: String): Pair<ByteArray, String>? {
        return try {
            val parts = JSONObject(responseText)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (!part.has("inlineData")) continue
                val inline = part.getJSONObject("inlineData")
                val data = inline.optString("data")
                if (data.isEmpty()) continue
                val bytes = Base64.decode(data, Base64.DEFAULT)
                if (bytes.isEmpty()) continue
                val mime = inline.optString("mime_type", "image/png")
                    .takeIf { it.isNotEmpty() } ?: "image/png"
                return Pair(bytes, mime)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    /** Tasveer banane wala model — text wale modelon se alag. */
    private const val IMAGE_MODEL = "gemini-2.5-flash-image"

    /** [GeminiBrain] wala hi base URL — key wahi, raasta wahi. */
    private const val GENERATE_CONTENT_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Tasveer banne me waqt lagta hai — judne 30 s, padhne 60 s. */
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000
}

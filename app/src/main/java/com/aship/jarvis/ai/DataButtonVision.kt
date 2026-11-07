package com.aship.jarvis.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * v57: SCREENSHOT-VISION — tasveer me mobile-data
 * wala button CHINH dekhkar dhoondhna।
 *
 * Text wala raasta (JarvisAccessibilityService ka
 * findDataTile) jab tile nahi dhoondh pata — jaise
 * user ke panel par "94.75 GB" likha hone par bhi
 * "button nahi mila" bola tha — tab ye raasta
 * Gemini se panel ki tasveer padhwata hai। Gemini
 * ko "Data" SHABD nahi, upar-neeche teer wala
 * CHINH dhoondhna hota hai, isliye tile par kuch
 * likha hi na ho tab bhi mil jata hai।
 *
 * Ye BANDHA HUA (sync) call hai — bulane wale ke
 * apne background thread se hi bulana (service ka
 * worker ya MainActivity ka background executor)।
 * Gemini key na ho ya net na ho to turant null —
 * bulane wala tab aaj ke purane raaste par hi
 * imaandaar "nahi mila" bolega, kabhi crash nahi।
 */
object DataButtonVision {

    /**
     * Tasveer me mila hua data button — jagah (0..1000) +
     * chihn + tasveer me dikhne wali haalat (true = on /
     * ujla, false = off / fika, null = pata nahi chala)।
     * Haalat isliye chahiye taaki pehle se chahi hui
     * haalat me ho to dobara dabakar ulta na kar dein।
     */
    data class VisionHit(
        val x: Int,
        val y: Int,
        val icon: String,
        val on: Boolean? = null
    )

    /**
     * Panel ki tasveer me mobile-data wala chihn
     * dhoondho। Mila to jagah + chihn ka varnan,
     * na mila / key na ho / net fail ho to null।
     */
    fun findDataIcon(context: Context, bitmap: Bitmap): VisionHit? {
        return try {
            if (!GeminiBrain.isConfigured(context)) return null
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val base64 = toBase64Jpeg(bitmap) ?: return null
            val reply = GeminiBrain.requestAgentRoundWithImage(
                context,
                VISION_SYSTEM,
                listOf("user" to VISION_PROMPT),
                base64,
                GeminiBrain.RequestClass.VISION
            ) ?: return null
            parseHit(reply)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Gemini ke jawab se jagah nikalo। Jawab sirf JSON
     * hona chahiye — {"x": 123, "y": 456, "icon": "..."}
     * ya {"found": false} — par model kabhi-kabhi aas-paas
     * kuch shabd bhi likh deta hai, isliye regex se
     * nikaalte hain; kuch na mile to null।
     */
    fun parseHit(reply: String): VisionHit? {
        return try {
            val lower = reply.lowercase()
            if (lower.contains("\"found\"") &&
                FOUND_FALSE_REGEX.containsMatchIn(lower)
            ) {
                return null
            }
            val x = X_REGEX.find(reply)?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: return null
            val y = Y_REGEX.find(reply)?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: return null
            if (x !in 0..1000 || y !in 0..1000) return null
            val icon = ICON_REGEX.find(reply)?.groupValues
                ?.getOrNull(1)?.trim().orEmpty()
            val on = when (ON_REGEX.find(reply)?.groupValues?.getOrNull(1)) {
                "true" -> true
                "false" -> false
                else -> null
            }
            VisionHit(x, y, icon, on)
        } catch (t: Throwable) {
            null
        }
    }

    /** Tasveer ko Gemini ke layak halki JPEG base64 banao (ScreenVision wala hi aakar)। */
    private fun toBase64Jpeg(bitmap: Bitmap): String? {
        return try {
            val scale = if (bitmap.width > TARGET_WIDTH_PX) {
                TARGET_WIDTH_PX.toFloat() / bitmap.width.toFloat()
            } else {
                1f
            }
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
            val stream = ByteArrayOutputStream()
            val ok = scaled.compress(
                Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream
            )
            if (scaled !== bitmap) {
                try {
                    scaled.recycle()
                } catch (t: Throwable) {
                    // Saaf na ho to koi baat nahi.
                }
            }
            if (!ok) return null
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    // System nirdesh — model ko sirf JSON bolna hai, kuch aur nahi.
    private const val VISION_SYSTEM =
        "You are a precise UI grounding helper. " +
            "Reply with ONLY the requested JSON object, no other text."

    /**
     * CHINH-pehle wala nirdesh — "Data" shabd ki
     * zaroorat BILKUL NAHI: tile par kuch likha hi
     * na ho tab bhi upar-neeche teer wala mobile-data
     * ka chihn hi pehchan hai। Jagah 0..1000 me,
     * tasveer ki chaudaai/lambai ke hisse me।
     */
    private const val VISION_PROMPT =
        "This is a screenshot of an Android quick-settings panel. " +
            "Find the MOBILE DATA button. Identify it by its ICON — " +
            "the up and down arrows symbol for mobile data — even if " +
            "the tile has NO text label saying 'Data'. Do NOT require " +
            "the word 'Data'. If you can see the mobile data icon, " +
            "reply with ONLY this JSON: " +
            "{\"x\": <0-1000>, \"y\": <0-1000>, " +
            "\"icon\": \"<short description of the icon>\", " +
            "\"on\": <true if the icon looks switched ON/highlighted, " +
            "false if it looks OFF/grey, null if you cannot tell>} " +
            "where x and y are the center of the icon as fractions " +
            "of the image width and height in 0-1000. If you cannot " +
            "see any mobile data icon, reply with ONLY: " +
            "{\"found\": false}"

    private val X_REGEX = Regex(""""x"\s*:\s*(\d+)""")
    private val Y_REGEX = Regex(""""y"\s*:\s*(\d+)""")
    private val ICON_REGEX = Regex(""""icon"\s*:\s*"([^"]*)"""")
    private val ON_REGEX = Regex(""""on"\s*:\s*(true|false|null)""")
    private val FOUND_FALSE_REGEX = Regex(""""found"\s*:\s*false""")

    /** Tasveer ki chaudaai (px) — Gemini ke liye kaafi, kota ke liye halki। */
    private const val TARGET_WIDTH_PX = 640

    /** JPEG gunvatta। */
    private const val JPEG_QUALITY = 75
}

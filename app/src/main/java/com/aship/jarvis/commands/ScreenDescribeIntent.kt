package com.aship.jarvis.commands

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.ai.GeminiBrain
import com.aship.jarvis.ai.ScreenshotStore
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v80: SCREEN BATAO — "এই স্ক্রিনে কী আছে / স্ক্রিনটা দেখে
 * বলো / এটা দেখে বলো" — saamne wali screen ka chhota
 * barnan bolo। Naya vision engine NAHI — pehle se bana
 * hua raasta: ScreenshotStore.captureFresh (accessibility
 * screenshot) + Gemini vision (requestAgentRoundWithImage,
 * DataButtonVision wala hi pattern)। Vision na mile to
 * accessibility ped ki dikhai dene wali likhai padhkar
 * bolo; dono na mile to imaandaari se "dekh nahi paya"
 * bolo — kabhi andaze se barnan NAHI।
 */
object ScreenDescribeIntent {

    private val DESCRIBE_MARKERS = setOf(
        // Bangla (script)
        "এই স্ক্রিনে কী আছে", "স্ক্রিনে কী আছে", "স্ক্রিনে কি আছে",
        "স্ক্রিনটা দেখে বলো", "স্ক্রিন দেখে বলো", "এটা দেখে বলো",
        "এইটা দেখে বলো", "স্ক্রিনে কী দেখছো", "স্ক্রিনটা বর্ণনা করো",
        "স্ক্রিন বর্ণনা করো", "এই স্ক্রিনটা কী", "স্ক্রিনে কী আছে বলো",
        // Bangla (roman roop)
        "ei screen e ki ache", "screen e ki ache", "screen ta dekhe bolo",
        "screen dekhe bolo", "eta dekhe bolo", "eita dekhe bolo",
        "screen bornona koro",
        // Hindi
        "स्क्रीन पर क्या है", "स्क्रीन देखकर बताओ", "इस स्क्रीन पर क्या है",
        "स्क्रीन पर क्या दिख रहा है",
        // English
        "what's on my screen", "what is on my screen", "whats on my screen",
        "describe this screen", "describe my screen",
        "tell me what is on my screen", "tell me what's on my screen"
    )

    private fun normalize(raw: String): String {
        return raw.lowercase(Locale.ROOT)
            .trim()
            .trim('।', '.', '!', '?', ',', '"', '\'', '(', ')', ' ')
            .replace(Regex("\\s+"), " ")
    }

    /** Poora wakyaansh shabd-seema par mile to hi describe hai ("এই স্ক্রিনটা কীভাবে" describe nahi)। */
    fun isDescribeTask(rawText: String): Boolean {
        if (rawText.isBlank()) return false
        val padded = " " + normalize(rawText) + " "
        return DESCRIBE_MARKERS.any { marker ->
            padded.contains(" $marker ")
        }
    }

    fun thinkingLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "স্ক্রিনটা দেখছি…"
        SpokenLang.HINDI -> "स्क्रीन देख रहा हूँ…"
        SpokenLang.ENGLISH -> "Let me look at the screen…"
    }

    /** Dono raaste fail — andaza nahi, saaf mana। */
    fun noSeeLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "স্ক্রিনটা দেখতে পেলাম না — ছবি নেওয়া যায়নি, আর পড়ার মতো লেখাও পেলাম না। অ্যাক্সেসিবিলিটি সার্ভিস চালু আছে কি না দেখো।"
        SpokenLang.HINDI -> "स्क्रीन देख नहीं पाया — न तस्वीर मिली, न पढ़ने लायक कुछ। Accessibility service चालू है या नहीं, देख लो।"
        SpokenLang.ENGLISH -> "I couldn't see the screen — no screenshot and no readable text. Please check that the accessibility service is on."
    }

    /** Vision fail, ped mila — saaf batao ki ye likhai padhkar bola ja raha hai, dekha nahi। */
    fun treeIntroLine(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "স্ক্রিনের ছবি দেখতে পেলাম না, তবে স্ক্রিনের লেখা পড়ে বলছি — "
        SpokenLang.HINDI -> "स्क्रीन की तस्वीर नहीं देख पाया, पर स्क्रीन पर लिखा पढ़कर बता रहा हूँ — "
        SpokenLang.ENGLISH -> "I couldn't see a picture of the screen, but I can read what's written on it — "
    }

    private fun visionSystemPrompt(): String =
        "You are a phone screen reader. Describe briefly, in 3-4 short sentences, " +
            "what is visible on this phone screenshot: which app or screen it looks like, " +
            "and the main visible items (headings, buttons, messages). " +
            "Do not invent anything not visible. If the image is blank or unreadable, say so."

    private fun visionUserPrompt(lang: SpokenLang): String = when (lang) {
        SpokenLang.BENGALI -> "এই স্ক্রিনে কী আছে, বাংলায় সংক্ষেপে বলো। যা দেখা যাচ্ছে শুধু তাই বলো, আন্দাজে কিছু যোগ কোরো না।"
        SpokenLang.HINDI -> "इस स्क्रीन पर क्या है, हिंदी में संक्षेप में बताओ। जो दिख रहा है वही बताओ, अंदाज़े से कुछ मत जोड़ो।"
        SpokenLang.ENGLISH -> "What is on this screen? Answer briefly in English. Only say what is actually visible, do not guess."
    }

    private fun cleanVisionReply(reply: String): String? {
        val clean = reply
            .replace(Regex("[*#]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
            .trim()
        return clean.ifEmpty { null }
    }

    private fun toBase64Jpeg(bitmap: Bitmap): String? {
        return try {
            val scale = 640f / maxOf(bitmap.width, bitmap.height).toFloat()
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    /** Accessibility ped ki dikhai dene wali likhai — pehle 8, har ek 80 akshar tak। */
    private fun loadTreeTexts(context: Context): List<String> {
        val latch = CountDownLatch(1)
        var snapshot: JarvisAccessibilityService.ScreenSnapshot? = null
        try {
            JarvisAccessibilityService.captureScreen(context) { result ->
                snapshot = result
                latch.countDown()
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        val ok = try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ok) return emptyList()
        val elements = snapshot?.elements ?: return emptyList()
        return elements.mapNotNull { el ->
            val t = el.text.trim()
            if (t.isEmpty()) null else t
        }.distinct().take(8).map { it.take(80) }
    }

    /**
     * Blocking kaam — background thread par hi bulao।
     * 1) Vision (ScreenshotStore + Gemini, DataButtonVision
     * wala pattern)। 2) Ped ki likhai। 3) Dono nahi to
     * imaandaar "dekh nahi paya" line।
     */
    fun describeSync(context: Context, lang: SpokenLang): String {
        try {
            val bitmap = ScreenshotStore.captureFresh(context)
            if (bitmap != null && !ScreenshotStore.screenLooksSecure(context, bitmap)) {
                if (GeminiBrain.isConfigured(context)) {
                    val base64 = toBase64Jpeg(bitmap)
                    val reply = base64?.let {
                        GeminiBrain.requestAgentRoundWithImage(
                            context, visionSystemPrompt(),
                            listOf("user" to visionUserPrompt(lang)),
                            it, GeminiBrain.RequestClass.VISION
                        )
                    }
                    val clean = reply?.let { cleanVisionReply(it) }
                    if (!clean.isNullOrBlank()) return clean
                }
            }
        } catch (t: Throwable) {
            // Vision fail — neeche wala ped wala raasta।
        }
        try {
            val texts = loadTreeTexts(context)
            if (texts.isNotEmpty()) {
                val sep = if (lang == SpokenLang.ENGLISH) ", " else "। "
                return treeIntroLine(lang) + texts.joinToString(sep)
            }
        } catch (t: Throwable) {
            // Neeche imaandaar line।
        }
        return noSeeLine(lang)
    }
}

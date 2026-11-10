package com.aship.jarvis.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.aship.jarvis.access.JarvisAccessibilityService
import com.aship.jarvis.commands.MayaLanguages
import com.aship.jarvis.prefs.AppPrefs
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * v95: MAYA'S EYES — screen dekhna (vision).
 *
 * Maya ko "aankh" mili: saamne wali screen ki tasveer
 * lekar Gemini vision se dekhna, aur ped (accessibility
 * nodes) se padhna. Chaar kaam:
 *  (1) describe — screen par kya hai, batao;
 *  (2) find — "[X] screen par kahan hai", jagah ke saath;
 *  (3) locateByVision — varnan se hisse ko dhoondhna
 *      (tap ke liye; nishan-aankit tasveer + mark number);
 *  (4) readAloud — screen ka dikhai dene wala text padhkar
 *      sunana (pehle ped se, na mile to vision se).
 *
 * Sab kaam BANDHA (blocking) hain — bulane wala thread
 * background thread hi hona chahiye (wake service ka
 * executor, ya MainActivity ka background executor;
 * screenshot capture ka latch main thread par nahi chal
 * sakta — v40 wala hi niyam). Koi nayi permission nahi:
 * tasveer Accessibility ki takeScreenshot() se aati hai
 * (Android 11+), jo pehle se chalu service par bharosa
 * karti hai; Gemini vision ke liye API Keys wali key
 * chahiye — key na ho to imaandaar "key chahiye" jawab,
 * kabhi andaza nahi. FLAG_SECURE wali screen par tasveer
 * kaali aati hai — use "surakshit screen" kehkar mana
 * jata hai, andaza nahi lagaya jata.
 */
object ScreenEyes {

    /** Vision kaam ka natija — [text] tabhi jab [kind] OK ho. */
    enum class Kind {
        OK, NO_KEY, NO_ACCESSIBILITY, NO_SHOT, SECURE, VISION_FAIL, NOT_FOUND, EMPTY
    }

    data class Result(val kind: Kind, val text: String? = null)

    /** Screen par mile hisse ki jagah — upar/majhkhana/neeche + baayein/beech/daayein. */
    data class FoundSpot(val vertical: String, val horizontal: String, val label: String)

    data class FindResult(val kind: Kind, val spot: FoundSpot? = null)

    /** Varnan se mile hisse ka natija — [element] tabhi jab [kind] OK ho. */
    data class LocateResult(
        val kind: Kind,
        val element: JarvisAccessibilityService.ScreenElement? = null,
        /** Tasveer ki chaudaai — jagah nikalne ke liye (find wala raasta). */
        val imageWidth: Int = 0,
        /** Tasveer ki unchaai — jagah nikalne ke liye (find wala raasta). */
        val imageHeight: Int = 0
    )

    /**
     * Gemini ko jawab kis bhasha me dena hai — user ki
     * chuni hui sunne wali bhasha (listen_language pref,
     * v93). Na mile to Bengali (default).
     */
    fun replyLanguageName(context: Context): String {
        val tag = try {
            AppPrefs(context).listenLanguage
        } catch (t: Throwable) {
            "bn-IN"
        }
        return MayaLanguages.fromTag(tag)?.geminiName ?: "Bengali"
    }

    /**
     * (1) Screen par kya hai — tasveer lekar Gemini
     * vision se chhota sachha varnan. Key na ho, service
     * band ho, tasveer na mile, ya screen surakshit ho —
     * har haal me wajah ke saath natija, andaza kabhi nahi.
     */
    fun describe(context: Context): Result {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return Result(Kind.NO_ACCESSIBILITY)
        }
        if (!GeminiBrain.isConfigured(context)) {
            return Result(Kind.NO_KEY)
        }
        val bitmap = ScreenshotStore.captureFresh(context)
            ?: return Result(Kind.NO_SHOT)
        if (ScreenshotStore.screenLooksSecure(context, bitmap)) {
            return Result(Kind.SECURE)
        }
        val base64 = toBase64Jpeg(bitmap) ?: return Result(Kind.NO_SHOT)
        val lang = replyLanguageName(context)
        val system = "You are Maya, a helpful phone voice assistant. " +
            "The attached image is a screenshot of the user's phone screen. " +
            "Describe briefly and factually in $lang what is on the screen: " +
            "which app it looks like, the main content, and any important " +
            "buttons or text. Keep it short — a few sentences. Do not invent " +
            "anything that is not visible in the image. If the screen is " +
            "mostly empty or unclear, say so plainly."
        val raw = visionRound(
            context, system, "Describe what is on this screen.", base64
        )?.trim().orEmpty()
        return if (raw.isEmpty()) Result(Kind.VISION_FAIL) else Result(Kind.OK, raw)
    }

    /**
     * (2) "[target] screen par kahan hai" — pehle ped
     * se (naam mil jaye to bina vision kharch kiye
     * jagah pakki), na mile to nishan-aankit tasveer
     * par Gemini se nishan dhoondho. Jagah hamesha
     * hisse ke asal chaukone se (upar/majhkhana/neeche
     * + baayein/beech/daayein) — andaza nahi.
     */
    fun find(context: Context, target: String): FindResult {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return FindResult(Kind.NO_ACCESSIBILITY)
        }
        val snapshot = captureScreenSync(context)
        val nodeHit = nodeMatch(snapshot, target)
        if (nodeHit != null) {
            val (w, h) = screenSize(snapshot)
            return FindResult(Kind.OK, spotOf(nodeHit, w, h))
        }
        val located = locateByVision(context, target, snapshot)
        return when (located.kind) {
            Kind.OK -> {
                val el = located.element
                if (el == null) {
                    FindResult(Kind.NOT_FOUND)
                } else {
                    // Vision wali tasveer ka aakar hi
                    // screen ka aakar hai — jagah usi se;
                    // na mile to ped wale aakar se.
                    val (w, h) = if (located.imageWidth > 0 &&
                        located.imageHeight > 0
                    ) {
                        located.imageWidth to located.imageHeight
                    } else {
                        screenSize(snapshot)
                    }
                    FindResult(Kind.OK, spotOf(el, w, h))
                }
            }
            else -> FindResult(located.kind)
        }
    }

    /**
     * (3) Varnan se hisse ko dhoondho — tap ke liye.
     * Pehle Gemini key, phir taazi tasveer, phir
     * surakshit-screen jaanch, phir nishan-aankit
     * tasveer par model se nishan-number. Tap yahan
     * NAHI hota — bulane wala (CommandEngine) hisse
     * ke beech par apne gesture raaste se dabata hai.
     */
    fun locateByVision(
        context: Context,
        description: String,
        snapshot: JarvisAccessibilityService.ScreenSnapshot?
    ): LocateResult {
        if (!GeminiBrain.isConfigured(context)) {
            return LocateResult(Kind.NO_KEY)
        }
        val bitmap = ScreenshotStore.captureFresh(context)
            ?: return LocateResult(Kind.NO_SHOT)
        if (ScreenshotStore.screenLooksSecure(context, bitmap)) {
            return LocateResult(Kind.SECURE)
        }
        return locateOnMarked(context, description, snapshot, bitmap)
    }

    /**
     * (4) Screen ka dikhai dene wala text padhkar sunana —
     * pehle ped se (muft, pakka, screen ki apni bhasha
     * me), ped khaali ho to vision se. Lamba dump nahi:
     * zyada se zyada [MAX_READ_ITEMS] hisse, kul
     * [MAX_READ_TOTAL_CHARS] akshar.
     */
    fun readAloud(context: Context): Result {
        if (!JarvisAccessibilityService.isEnabled(context)) {
            return Result(Kind.NO_ACCESSIBILITY)
        }
        val snapshot = captureScreenSync(context)
        val text = collectVisibleText(snapshot)
        if (text.isNotEmpty()) return Result(Kind.OK, text)
        if (!GeminiBrain.isConfigured(context)) {
            return Result(Kind.NO_KEY)
        }
        val bitmap = ScreenshotStore.captureFresh(context)
            ?: return Result(Kind.NO_SHOT)
        if (ScreenshotStore.screenLooksSecure(context, bitmap)) {
            return Result(Kind.SECURE)
        }
        val base64 = toBase64Jpeg(bitmap) ?: return Result(Kind.NO_SHOT)
        val lang = replyLanguageName(context)
        val system = "You are Maya, a helpful phone voice assistant. " +
            "The attached image is a screenshot of the user's phone screen. " +
            "Read out the main visible text on this screen in $lang. " +
            "List only the text that is actually visible, briefly, in reading " +
            "order. Do not invent any text that is not visible. If there is " +
            "no readable text, say so plainly."
        val raw = visionRound(
            context, system, "Read the visible text on this screen.", base64
        )?.trim().orEmpty()
        return if (raw.isEmpty()) Result(Kind.VISION_FAIL) else Result(Kind.OK, raw)
    }

    // ------------------------------------------------------------------
    // Andaruni sahayak
    // ------------------------------------------------------------------

    /** Ek vision round — tasveer ke saath Gemini, sync call. */
    private fun visionRound(
        context: Context,
        system: String,
        userTurn: String,
        base64: String
    ): String? {
        return try {
            GeminiBrain.requestAgentRoundWithImage(
                context, system, listOf("user" to userTurn), base64,
                GeminiBrain.RequestClass.VISION
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** Nishan-aankit tasveer par model se nishan-number nikalna. */
    private fun locateOnMarked(
        context: Context,
        target: String,
        snapshot: JarvisAccessibilityService.ScreenSnapshot?,
        bitmap: Bitmap
    ): LocateResult {
        val elements = snapshot?.elements ?: return LocateResult(Kind.NOT_FOUND)
        val marked = ScreenVision.chooseMarked(elements)
        if (marked.isEmpty()) return LocateResult(Kind.NOT_FOUND)
        val image = ScreenVision.renderMarkedImage(bitmap, marked)
            ?: return LocateResult(Kind.VISION_FAIL)
        val system = "You are Maya, a helpful phone voice assistant. " +
            "The attached image is a screenshot of the user's phone screen " +
            "with numbered marks drawn on elements. " +
            "Which numbered mark is \"$target\"? Reply with ONLY the mark " +
            "number (for example: 7). If it is not visible on the screen, " +
            "reply with ONLY the word none. Do not add any other words."
        val raw = visionRound(
            context, system, "Which mark is \"$target\"?", image.jpegBase64
        )?.trim().orEmpty()
        if (raw.isEmpty()) return LocateResult(Kind.VISION_FAIL)
        if (raw.contains("none", ignoreCase = true)) {
            return LocateResult(Kind.NOT_FOUND)
        }
        val number = Regex("""\d+""").find(raw)?.value?.toIntOrNull()
            ?: return LocateResult(Kind.NOT_FOUND)
        if (number < 1 || number > marked.size) {
            return LocateResult(Kind.NOT_FOUND)
        }
        return LocateResult(
            Kind.OK, marked[number - 1],
            bitmap.width, bitmap.height
        )
    }

    /** Ped se naam-milaan — pehle seedha contains, phir shabd-milan. */
    private fun nodeMatch(
        snapshot: JarvisAccessibilityService.ScreenSnapshot?,
        target: String
    ): JarvisAccessibilityService.ScreenElement? {
        if (snapshot == null) return null
        val needle = target.lowercase(Locale.ROOT).trim()
        if (needle.isEmpty()) return null
        var best: JarvisAccessibilityService.ScreenElement? = null
        var bestLen = Int.MAX_VALUE
        for (el in snapshot.elements) {
            val label = el.text.lowercase(Locale.ROOT)
            if (label.isEmpty()) continue
            if (label.contains(needle) && label.length < bestLen) {
                best = el
                bestLen = label.length
            }
        }
        if (best != null) return best
        val words = needle.split(Regex("""\s+"""))
            .map { it.trim(',', '.', '।', '!', '?') }
            .filter { it.length >= 2 }
        if (words.isEmpty()) return null
        var bestOverlap = 0
        bestLen = Int.MAX_VALUE
        for (el in snapshot.elements) {
            if (!el.clickable) continue
            val label = el.text.lowercase(Locale.ROOT)
            if (label.isEmpty()) continue
            val overlap = words.count { label.contains(it) }
            if (overlap == 0) continue
            if (overlap > bestOverlap ||
                (overlap == bestOverlap && label.length < bestLen)
            ) {
                best = el
                bestOverlap = overlap
                bestLen = label.length
            }
        }
        return best
    }

    /** Ped se dikhai dene wala text jodna — dohraav hata, seema bandhi. */
    private fun collectVisibleText(
        snapshot: JarvisAccessibilityService.ScreenSnapshot?
    ): String {
        if (snapshot == null) return ""
        val seen = LinkedHashSet<String>()
        var total = 0
        for (el in snapshot.elements) {
            val raw = el.text.trim()
            if (raw.isEmpty()) continue
            val chunk = if (raw.length > MAX_READ_CHUNK) {
                raw.take(MAX_READ_CHUNK)
            } else {
                raw
            }
            if (!seen.add(chunk)) continue
            total += chunk.length + 2
            if (seen.size >= MAX_READ_ITEMS || total >= MAX_READ_TOTAL_CHARS) break
        }
        val joined = seen.joinToString(", ")
        return if (joined.length > MAX_READ_TOTAL_CHARS) {
            joined.take(MAX_READ_TOTAL_CHARS)
        } else {
            joined
        }
    }

    /** Screen-saar ka bandha (sync) capture — background thread se hi. */
    private fun captureScreenSync(
        context: Context
    ): JarvisAccessibilityService.ScreenSnapshot? {
        val latch = CountDownLatch(1)
        var snapshot: JarvisAccessibilityService.ScreenSnapshot? = null
        try {
            JarvisAccessibilityService.captureScreen(context) { snap ->
                snapshot = snap
                latch.countDown()
            }
        } catch (t: Throwable) {
            return null
        }
        try {
            latch.await(3_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Jo mila wahi kaafi.
        }
        return snapshot
    }

    /** Hisse ke beech se jagah — upar/majhkhana/neeche + baayein/beech/daayein. */
    private fun spotOf(
        el: JarvisAccessibilityService.ScreenElement,
        width: Int,
        height: Int
    ): FoundSpot {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val vertical = when {
            el.centerY < h / 3 -> "top"
            el.centerY < 2 * h / 3 -> "middle"
            else -> "bottom"
        }
        val horizontal = when {
            el.centerX < w / 3 -> "left"
            el.centerX < 2 * w / 3 -> "centre"
            else -> "right"
        }
        return FoundSpot(vertical, horizontal, el.text.ifEmpty { el.className })
    }

    /** Ped ke hisson ke chaukonon se screen ka lagbhag aakar. */
    private fun screenSize(
        snapshot: JarvisAccessibilityService.ScreenSnapshot?
    ): Pair<Int, Int> {
        if (snapshot == null) return 1080 to 1920
        val w = snapshot.elements.maxOfOrNull { it.right } ?: 1080
        val h = snapshot.elements.maxOfOrNull { it.bottom } ?: 1920
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /** Saaf tasveer ko Gemini ke liye base64 JPEG banao (ScreenVision jaisi seema). */
    private fun toBase64Jpeg(bitmap: Bitmap): String? {
        return try {
            val scale = if (bitmap.width > TARGET_WIDTH_PX) {
                TARGET_WIDTH_PX.toFloat() / bitmap.width.toFloat()
            } else {
                1f
            }
            val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val stream = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            if (scaled !== bitmap) scaled.recycle()
            if (!ok) return null
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        }
    }

    /** Vision tasveer ki chaudaai (px) — ScreenVision jaisi hi seema. */
    private const val TARGET_WIDTH_PX = 640

    /** JPEG gunvatta — saaf padhne layak, kota ke liye halki. */
    private const val JPEG_QUALITY = 75

    /** Padhkar sunane me zyada se zyada kitne hisse. */
    private const val MAX_READ_ITEMS = 12

    /** Padhkar sunane me kul zyada se zyada akshar. */
    private const val MAX_READ_TOTAL_CHARS = 500

    /** Ek hisse ka text zyada se zyada kitna lamba. */
    private const val MAX_READ_CHUNK = 120
}

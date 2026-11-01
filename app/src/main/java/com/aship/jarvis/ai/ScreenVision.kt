package com.aship.jarvis.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.aship.jarvis.access.JarvisAccessibilityService.ScreenElement
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * v39: SCREEN-VISION ki tasveer-mashinari — ScreenAgent
 * ke liye saaf tasveer se nishan-aankit (Set-of-Mark)
 * tasveer banana, aur "screen dikh hi nahi raha"
 * (FLAG_SECURE / kaala capture) ki sasti pehchan.
 *
 * Soch (research par bandhi): Gemini ke kacche
 * nirdeshank kamzor hote hain, isliye model ko khaali
 * tasveer par tap ka andaza NAHI lagana padta. Tasveer
 * par hisson ke chaukone par NAMBAR bane hote hain;
 * model sirf nambar bolta hai ("tap_mark 7") aur tap
 * usi hisse ke PAKKE beech par padta hai — nirdeshank
 * ki galti khatm. Khaana-khaali sat-hon (sirf tasveer,
 * koi hissa hi nahi) ke liye tap_point (0–1000 wala
 * khaaka) bhi hai — woh ScreenAgent sambhalta hai;
 * ye file sirf tasveer banati/pehchanti hai.
 *
 * Tasveer ka aakar bandha hai: chaudaai ~[TARGET_WIDTH_PX]
 * px, JPEG gunvatta ~[JPEG_QUALITY] — Gemini ke liye
 * kaafi saaf, kota ke liye halki. Nishan wali tasveer
 * sirf model ko jaati hai; saaf tasveer service ke
 * paas yaad me rehti hai ([JarvisAccessibilityService]
 * ka lastCleanScreenshot), is file me uski koi
 * bhejna/share karna NAHI hai.
 */
object ScreenVision {

    /** Nishan ke liye chune hue hisse + taiyaar tasveer ka bandha natija. */
    class MarkedImage(
        /** Nishan wale hisse — mark nambar = is kataar me sthiti + 1. */
        val marked: List<ScreenElement>,
        /** Gemini ko bhejne ke liye base64 (bina line-wrap) JPEG. */
        val jpegBase64: String
    )

    /**
     * Nishan ke ummeedwaar chuno: chaukona (bounds) wale
     * hisse; pehle sirf kaam ke (clickable/editable) —
     * woh ek bhi na hon to chaukone wale saare hisse
     * (bina-naam wali tasveeri sat-h par bhi kuch nishan
     * rahen). Kataar ka kram screen wali document-order
     * hi rehta hai, isliye nambar upar se neeche bante hain.
     */
    fun chooseMarked(elements: List<ScreenElement>): List<ScreenElement> {
        val withBounds = elements.filter {
            it.right > it.left && it.bottom > it.top
        }
        if (withBounds.isEmpty()) return emptyList()
        val actionable = withBounds.filter { it.clickable || it.editable }
        return if (actionable.isNotEmpty()) actionable else withBounds
    }

    /**
     * Saaf tasveer par [marked] hisson ke chaukone +
     * nambar-chhatri (chip) banakar JPEG base64 band karo.
     * Kuch bhi gadbad ho (khaali kataar, band na paye) to
     * null — bulane wala us kadam par bina tasveer chalta hai.
     */
    fun renderMarkedImage(
        clean: Bitmap,
        marked: List<ScreenElement>
    ): MarkedImage? {
        if (marked.isEmpty() || clean.width <= 0 || clean.height <= 0) {
            return null
        }
        return try {
            val scale = if (clean.width > TARGET_WIDTH_PX) {
                TARGET_WIDTH_PX.toFloat() / clean.width.toFloat()
            } else {
                1f
            }
            val scaledWidth = (clean.width * scale).roundToInt().coerceAtLeast(1)
            val scaledHeight = (clean.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(
                clean, scaledWidth, scaledHeight, true
            )
            val canvasBitmap = scaled.copy(Bitmap.Config.ARGB_8888, true)
                ?: return null
            if (canvasBitmap !== scaled) {
                scaled.recycle()
            }
            val canvas = Canvas(canvasBitmap)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MARK_COLOR
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MARK_COLOR
                style = Paint.Style.FILL
            }
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = CHIP_TEXT_PX
                isFakeBoldText = true
            }
            marked.forEachIndexed { index, element ->
                val left = element.left * scale
                val top = element.top * scale
                val right = element.right * scale
                val bottom = element.bottom * scale
                canvas.drawRect(left, top, right, bottom, stroke)
                // Nambar wali chhatri: chaukone ke upar,
                // jagah na ho to andar ki or. Kinaare se
                // chipki rehti hai taaki nambar hamesha dikhe.
                val label = (index + 1).toString()
                val chipWidth = number.measureText(label) + CHIP_PAD_PX * 2
                var chipLeft = left.coerceIn(
                    0f, (scaledWidth - chipWidth).coerceAtLeast(0f)
                )
                var chipTop = top - CHIP_HEIGHT_PX
                if (chipTop < 0f) {
                    chipTop = top
                }
                if (chipTop + CHIP_HEIGHT_PX > scaledHeight) {
                    chipTop = (scaledHeight - CHIP_HEIGHT_PX).coerceAtLeast(0f)
                }
                if (chipLeft + chipWidth > scaledWidth) {
                    chipLeft = (scaledWidth - chipWidth).coerceAtLeast(0f)
                }
                canvas.drawRect(
                    chipLeft, chipTop,
                    chipLeft + chipWidth, chipTop + CHIP_HEIGHT_PX,
                    chip
                )
                canvas.drawText(
                    label,
                    chipLeft + CHIP_PAD_PX,
                    chipTop + CHIP_HEIGHT_PX - 6f,
                    number
                )
            }
            val stream = ByteArrayOutputStream()
            val ok = canvasBitmap.compress(
                Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream
            )
            canvasBitmap.recycle()
            if (!ok) return null
            val base64 = Base64.encodeToString(
                stream.toByteArray(), Base64.NO_WRAP
            )
            MarkedImage(marked, base64)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Tasveer "surakshit screen" wali kaali hai kya —
     * sasti jaanch: 16x16 jaali par pixelon ki roshni
     * naapo; saare namune bahut gehre (lagbhag kaale)
     * aur lagbhag ek-jaise hon to FLAG_SECURE wala
     * kaala capture maano. Asal me kaali screen (band
     * display, kaala video hissa) bhi yahi natija degi —
     * isliye ScreenAgent ise TABHI "screen nahi dikh
     * raha" maanta hai jab node-saar bhi khaali/kaam ka
     * na ho; akeli kaali tasveer par kaam nahi rukta.
     */
    fun looksSecureOrBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        return try {
            var minLum = Double.MAX_VALUE
            var maxLum = Double.MIN_VALUE
            val cols = 16
            val rows = 16
            for (r in 0 until rows) {
                val y = ((r + 0.5) * bitmap.height / rows).toInt()
                    .coerceIn(0, bitmap.height - 1)
                for (c in 0 until cols) {
                    val x = ((c + 0.5) * bitmap.width / cols).toInt()
                        .coerceIn(0, bitmap.width - 1)
                    val pixel = bitmap.getPixel(x, y)
                    val lum = 0.299 * Color.red(pixel) +
                        0.587 * Color.green(pixel) +
                        0.114 * Color.blue(pixel)
                    if (lum < minLum) minLum = lum
                    if (lum > maxLum) maxLum = lum
                }
            }
            maxLum < DARK_MAX_LUM && (maxLum - minLum) < DARK_SPREAD_LUM
        } catch (t: Throwable) {
            false
        }
    }

    /** Nishan wali tasveer ki chaudaai (px) — Gemini ke liye kaafi, kota ke liye halki. */
    private const val TARGET_WIDTH_PX = 640

    /** JPEG gunvatta — nambar aur chaukone saaf padh sakein, aakar bandha rahe. */
    private const val JPEG_QUALITY = 75

    // Nishan ka rang: ghane rangon par bhi dikhe aisa
    // chamkeela gulaabi; chhatri par safed nambar.
    private const val MARK_COLOR = 0xFFE91E63.toInt()
    private const val CHIP_HEIGHT_PX = 26f
    private const val CHIP_PAD_PX = 6f
    private const val CHIP_TEXT_PX = 17f

    // Kaali-tasveer jaanch ki seemayein (0–255 roshni):
    // sabse roshan namuna bhi isse gehra + kul phailaav
    // isse kam = lagbhag ek-roop kaala.
    private const val DARK_MAX_LUM = 28.0
    private const val DARK_SPREAD_LUM = 18.0
}

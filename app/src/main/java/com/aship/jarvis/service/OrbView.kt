package com.aship.jarvis.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.5: Siri jaisa chamakta gola (orb).
 *
 * Wake word sunte hi JarvisWakeService ise screen ke neeche beech
 * me ek overlay window me dikhata hai — bilkul Siri ke andaaz me.
 *
 * v1.8: gole ka SIZE AB HAMESHA FIXED rehta hai — poora gola
 * kabhi bada-chhota nahi hota (owner ne saaf mana kiya hai).
 * Saari harkat sirf gole ke ANDAR hoti hai.
 *
 * v1.11: bahari faint halo hataya gaya tha (tasveer jaisa lagta
 * tha), base size overlay me 148dp hua, chamak hamesha chalne
 * lagi taaki gola chalant lage, tasveer nahi.
 *
 * v14: owner ne reference video dikhaya — purana gola "bodda
 * faka" (bahut halka/fika) tha. Ab gola ek GEHRE, ghane rangon
 * wala BEHTA HUA MARBLE gola hai, jaisa video me hai:
 *  - upar-bayein gehra cyan/teal, beech me royal blue, neeche-
 *    dayein violet -> magenta-pink — rang jaise dhuaan/plasma
 *    gole ke andar lagatar ghoom-bah raha ho (4 rang-blob +
 *    2 naram ghoomti spiral-pattiyan + 2 arc-band).
 *  - us behti dhaar me patli SONHRI-NARANGI roshni ki lakerein
 *    (golden ribbons) neeche-beech me bal khati hui behti hain,
 *    saath me chhoti chamakti chingariyan (sparks).
 *  - rang ghane aur tez hain (kuch bhi fika nahi), neeche ka
 *    kinara halka gehra, kinare par cyan -> magenta rang ki
 *    patli rim, aur peeche halki rang-bhari aabha (cyan ek
 *    taraf, magenta dusri taraf) — light bleed jaisi, tasveer
 *    jaisi nahi.
 *  - harkat ek lagatar dheema bahav hai: master phase se saari
 *    paratein alag-alag raftaar/disha me ghoomti hain, loop
 *    bilkul seamless (har ghoom pure number me band hai).
 * SPEAKING mode me bahav ~2.2 guna tez aur sonhri lakerein
 * zyada chamakti hain. LISTENING me "saans" sirf roshni ki
 * chamak me aati hai — size v1.8 ke niyam se fixed hi rehta hai.
 *
 * Do framework animator hi saari harkat karte hain:
 *  - "saans" (breathing): roshni/aabha ki chamak dheere badhti-
 *    ghatti hai — size nahi.
 *  - "bahav" (flow): master phase lagatar badhta hai; SPEAKING
 *    me raftaar zyada. Rukne par (onDetachedFromWindow /
 *    stopAnimations) dono band.
 *
 * CPU halka rahe isliye invalidate() ~30 fps par bandha hai,
 * aur saari bhaari cheezein (gradient, spiral path, spark
 * table) onSizeChanged me ek baar banti hain — har frame
 * sirf sasti trig aur pehle-se-bane paint/path ka istemal.
 */
class OrbView(context: Context) : View(context) {

    enum class Mode { LISTENING, SPEAKING }

    private var mode: Mode = Mode.LISTENING

    // Animator se badalne wali haalat.
    // (v1.8: pulseScale HATA diya — gola kabhi scale nahi hoga.)
    private var glowStrength = GLOW_MIN

    // Master bahav-phase (chakra me). Flow animator ke delta se
    // badhta hai; saari paratein iske pure-multiple me ghoomti
    // hain isliye loop seamless rehta hai. Bahut bada ho jaye
    // to pure 3600 chakra ghata diye jate hain (sab ghoom pure
    // number me hain, koi farq nahi padta).
    private var phase = 0f
    private var lastFlowValue = 0f

    // Geometry (onSizeChanged me banti hai).
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    // Buniyadi paratein.
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beadPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Bahari aabha (halo): cyan upar-bayein, magenta neeche-dayein.
    private val haloPaintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaintB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloMatrix = Matrix()

    // Rang-blob (marble ki badi naram dhaarein).
    private val blobPaints = Array(BLOB_COUNT) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val blobShaders = arrayOfNulls<Shader>(BLOB_COUNT)
    private val blobMatrix = Matrix()

    // Ghoomti pattiyan: spiral path aur arc-band.
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var spiralPathA = Path()
    private var spiralPathB = Path()
    private val bandRectA = RectF()
    private val bandRectB = RectF()

    // Sonhri lakerein (golden ribbons): path har frame phase se
    // dobara bante hain (reset() purani jagah hi reuse karta hai).
    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ribbonPaths = Array(RIBBON_COUNT) { Path() }

    // Kinari: halki safed rim ke upar cyan->magenta sweep rim.
    private val rimBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(38, 255, 255, 255)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val rimMatrix = Matrix()

    private val clipPath = Path()

    // Chingariyan (sparks): jagah table pehle se taiyaar —
    // golden-angle phailav, tay pseudo-random doori/naap/disha.
    private val sparkAngle = FloatArray(SPARK_COUNT) { i -> i * 2.399963f }
    private val sparkRad = FloatArray(SPARK_COUNT) { i ->
        0.18f + 0.62f * (((i * 53) % 89) / 89f)
    }
    private val sparkSize = FloatArray(SPARK_COUNT) { i ->
        0.010f + 0.008f * (((i * 29) % 47) / 47f)
    }
    private val sparkDrift = IntArray(SPARK_COUNT) { i ->
        when (i % 3) {
            0 -> -1
            1 -> 1
            else -> 2
        }
    }
    private val sparkTwinkle = IntArray(SPARK_COUNT) { i -> 1 + (i % 3) }
    private val sparkColor = IntArray(SPARK_COUNT) { i ->
        when (i % 4) {
            0 -> Color.rgb(253, 230, 138)
            1 -> Color.WHITE
            2 -> Color.rgb(165, 243, 252)
            else -> Color.rgb(249, 168, 212)
        }
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var lastInvalidateMs = 0L

    private val breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BREATH_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animator ->
            val v = animator.animatedValue as Float
            // v1.8: sirf roshni badhti-ghatti hai — size fixed.
            glowStrength = GLOW_MIN + (GLOW_MAX - GLOW_MIN) * v
            poke()
        }
    }

    private val flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FLOW_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            val v = animator.animatedValue as Float
            var delta = v - lastFlowValue
            if (delta < 0f) delta += 1f
            lastFlowValue = v
            // SPEAKING me bahav tez — phase ki raftaar badha do,
            // koi jhatka nahi (delta par gunakar hai).
            phase += delta * if (mode == Mode.SPEAKING) SPEAKING_SPEED else 1f
            if (phase > 3600f) phase -= 3600f
            poke()
        }
    }

    /** Jawab bolna shuru ho to SPEAKING, command ka intezaar ho to LISTENING. */
    fun setMode(newMode: Mode) {
        mode = newMode
    }

    fun startAnimations() {
        if (!breathingAnimator.isRunning) breathingAnimator.start()
        if (!flowAnimator.isRunning) flowAnimator.start()
    }

    fun stopAnimations() {
        breathingAnimator.cancel()
        flowAnimator.cancel()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    /** Invalidate ko ~30 fps par roko — bekaar ki drawing nahi. */
    private fun poke() {
        val now = SystemClock.uptimeMillis()
        if (now - lastInvalidateMs >= FRAME_MS) {
            lastInvalidateMs = now
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f * SPHERE_FRACTION
        rimBasePaint.strokeWidth = (radius * 0.022f).coerceAtLeast(1.2f)
        rimPaint.strokeWidth = (radius * 0.022f).coerceAtLeast(1.2f)
        clipPath.reset()
        clipPath.addCircle(centerX, centerY, radius, Path.Direction.CW)

        // Gole ki buniyad: upar-bayein gehri cyan roshni, beech
        // royal blue, neeche gehri baingani-kaali gehrai.
        spherePaint.shader = RadialGradient(
            centerX - radius * 0.35f, centerY - radius * 0.40f, radius * 1.70f,
            intArrayOf(
                Color.rgb(14, 116, 144),
                Color.rgb(30, 64, 175),
                Color.rgb(91, 33, 182),
                Color.rgb(18, 8, 46)
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        // Neeche ke kinare ki gehrai: neeche se upar halki hoti
        // kaali-neeli chhaaya.
        shadePaint.shader = RadialGradient(
            centerX, centerY + radius * 1.15f, radius * 1.05f,
            intArrayOf(
                Color.argb(165, 3, 0, 20),
                Color.argb(70, 3, 0, 20),
                Color.argb(0, 3, 0, 20)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // Upar ki safed jhalak — glossy look ke liye (naram, badi).
        highlightPaint.shader = RadialGradient(
            centerX - radius * 0.4f, centerY - radius * 0.5f, radius * 0.55f,
            intArrayOf(
                Color.argb(165, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        // Chhoti tez chamak (bead) — geeli chamak wala nukta.
        beadPaint.shader = RadialGradient(
            centerX - radius * 0.42f, centerY - radius * 0.48f, radius * 0.22f,
            intArrayOf(
                Color.argb(120, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Aabha (halo) ke gradient — (0,0) par bane hain, har
        // frame local matrix se gole ke peeche sarakte hain.
        haloPaintA.shader = RadialGradient(
            0f, 0f, radius * 1.32f,
            intArrayOf(
                Color.argb(85, 34, 211, 238),
                Color.argb(0, 34, 211, 238)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        haloPaintB.shader = RadialGradient(
            0f, 0f, radius * 1.32f,
            intArrayOf(
                Color.argb(80, 217, 70, 239),
                Color.argb(0, 217, 70, 239)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // Rang-blob ke gradient — har blob ka apna ghana rang,
        // beech ghana, kinare bilkul halka (marble ki dhaar).
        for (i in 0 until BLOB_COUNT) {
            val c = BLOB_COLORS[i]
            val a = Color.alpha(c)
            blobShaders[i] = RadialGradient(
                0f, 0f, BLOB_SIZE[i] * radius,
                intArrayOf(
                    c,
                    Color.argb(
                        (a * 0.45f).toInt(),
                        Color.red(c), Color.green(c), Color.blue(c)
                    ),
                    Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            blobPaints[i].shader = blobShaders[i]
        }

        // Kinari ki sweep rim: cyan -> blue -> baingani -> gulabi.
        rimPaint.shader = SweepGradient(
            0f, 0f,
            intArrayOf(
                Color.rgb(34, 211, 238),
                Color.rgb(59, 130, 246),
                Color.rgb(168, 85, 247),
                Color.rgb(236, 72, 153),
                Color.rgb(34, 211, 238)
            ),
            null
        )

        // Ghoomti spiral-pattiyan — (0,0) kendra par taiyaar,
        // har frame canvas ghuma kar upar rakhi jati hain.
        spiralPathA = buildSpiral(1.6f, radius * 0.95f, 1f)
        spiralPathB = buildSpiral(1.3f, radius * 0.80f, -1f)

        // Arc-band ke dayre (band A chhota/violet, band B bada/teal).
        bandRectA.set(
            centerX - radius * 0.52f, centerY - radius * 0.57f,
            centerX + radius * 0.72f, centerY + radius * 0.67f
        )
        bandRectB.set(
            centerX - radius * 0.84f, centerY - radius * 0.80f,
            centerX + radius * 0.72f, centerY + radius * 0.76f
        )
    }

    /** Kendrit sarpil (spiral) path — marble ki ghoomti dhaar. */
    private fun buildSpiral(turns: Float, maxR: Float, dir: Float): Path {
        val p = Path()
        val steps = 90
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val a = dir * t * turns * 2.0 * PI
            val r = t * maxR
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        return p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val ph = phase % 1f
        val twoPi = (2.0 * PI).toFloat()
        val speaking = mode == Mode.SPEAKING
        canvas.save()
        // v1.8/v1.11: koi scale nahi — gola hamesha ek hi size
        // ka rehta hai, harkat sirf andar ke bahav me hoti hai.

        // --- Bahari aabha (clip se bahar, gole ke peeche) ---
        val haloAlpha = ((150f + 90f * glowStrength) *
            (if (speaking) 1.15f else 1f)).toInt().coerceIn(0, 255)
        haloPaintA.alpha = haloAlpha
        drawHalo(canvas, haloPaintA, centerX - radius * 0.22f, centerY - radius * 0.18f)
        haloPaintB.alpha = haloAlpha
        drawHalo(canvas, haloPaintB, centerX + radius * 0.24f, centerY + radius * 0.20f)

        // --- Gole ke andar ki paratein — kinare se bahar nahi ---
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawCircle(centerX, centerY, radius, spherePaint)

        // Rang-blob: bade naram rang-gole apne-apne dayre me
        // ghoomte hue — yahi marble ka behta aadhaar hai.
        for (i in 0 until BLOB_COUNT) {
            val ang = BLOB_START[i] + BLOB_DIR[i] * ph * twoPi
            val bx = centerX + (cos(ang) * BLOB_ORBIT[i] * radius).toFloat()
            val by = centerY + (sin(ang) * BLOB_ORBIT[i] * radius).toFloat()
            blobMatrix.setTranslate(bx, by)
            blobShaders[i]?.setLocalMatrix(blobMatrix)
            canvas.drawCircle(bx, by, BLOB_SIZE[i] * radius, blobPaints[i])
        }

        // Arc-band: moti naram pattiyan ulta-simte ghoomti hui.
        drawBand(
            canvas, bandRectA, ph * 360f + 40f, 130f,
            Color.rgb(139, 92, 246)
        )
        drawBand(
            canvas, bandRectB, -ph * 360f + 200f, 100f,
            Color.rgb(20, 184, 166)
        )

        // Spiral-pattiyan: do ghumavdaar dhaarein ulta-simte.
        drawSpiral(canvas, spiralPathA, ph * 360f + 15f, Color.rgb(45, 212, 191))
        drawSpiral(canvas, spiralPathB, -ph * 360f + 160f, Color.rgb(217, 70, 239))

        // Neeche ki gehrai (lakerein iske UPAR chamkengi).
        canvas.drawCircle(
            centerX, centerY + radius * 1.15f, radius * 1.05f, shadePaint
        )

        // Sonhri lakerein: neeche-beech me bal khati dhaarein.
        drawRibbons(canvas, ph, twoPi, speaking)

        // Chingariyan: chhoti ghoomti-tiltilati roshniyaan.
        drawSparks(canvas, ph, twoPi)

        // Gloss: naram safed jhalak + chhoti tez chamak.
        canvas.drawCircle(centerX, centerY, radius, highlightPaint)
        canvas.drawCircle(centerX, centerY, radius, beadPaint)
        canvas.restore()

        // Kinari: pehle halki safed rim, phir rang-bhari sweep rim
        // jo dheere-dheere ghoomti hai.
        canvas.drawCircle(centerX, centerY, radius, rimBasePaint)
        rimMatrix.setTranslate(centerX, centerY)
        rimMatrix.preRotate(ph * 360f)
        rimPaint.shader?.setLocalMatrix(rimMatrix)
        rimPaint.alpha = ((100f + 50f * glowStrength) *
            (if (speaking) 1.2f else 1f)).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, radius, rimPaint)
        canvas.restore()
    }

    /** Ek taraf ki aabha: gradient gole ko diye gaye nukte par rakho. */
    private fun drawHalo(canvas: Canvas, paint: Paint, hx: Float, hy: Float) {
        haloMatrix.setTranslate(hx, hy)
        paint.shader?.setLocalMatrix(haloMatrix)
        canvas.drawCircle(hx, hy, radius * 1.32f, paint)
    }

    /**
     * Naram moti patti (arc-band): teen chaudaai me kheench kar
     * kinare naram kiye jate hain — blur ki jagah yahi saasta,
     * har device par ek jaisa dikhta hai.
     */
    private fun drawBand(
        canvas: Canvas,
        rect: RectF,
        startAngle: Float,
        sweepAngle: Float,
        color: Int
    ) {
        bandPaint.color = color
        for (p in BAND_WIDTHS.indices) {
            bandPaint.strokeWidth = BAND_WIDTHS[p] * radius
            bandPaint.alpha = BAND_ALPHAS[p]
            canvas.drawArc(rect, startAngle, sweepAngle, false, bandPaint)
        }
    }

    /** Ghoomti spiral-patti: canvas ko kendra par ghuma kar rakho. */
    private fun drawSpiral(canvas: Canvas, path: Path, angleDeg: Float, color: Int) {
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(angleDeg)
        bandPaint.color = color
        for (p in BAND_WIDTHS.indices) {
            bandPaint.strokeWidth = SPIRAL_WIDTHS[p] * radius
            bandPaint.alpha = SPIRAL_ALPHAS[p]
            canvas.drawPath(path, bandPaint)
        }
        canvas.restore()
    }

    /**
     * Sonhri lakerein: [RIBBON_COUNT] sine-dhaarein gole ke
     * neeche-beech me behti hain. Har frame path phase se banta
     * hai (reset() se purani jagah reuse hoti hai). Teen chaudaai
     * me kheenchne se beech ki lakeer tez aur kinare chamak
     * (glow) jaise dikhte hain.
     */
    private fun drawRibbons(canvas: Canvas, ph: Float, twoPi: Float, speaking: Boolean) {
        val pulse = (0.55f + 0.45f * glowStrength) * (if (speaking) 1.25f else 1f)
        for (rib in 0 until RIBBON_COUNT) {
            val baseY = centerY + radius * (0.16f + 0.17f * rib) +
                (sin(ph * twoPi + rib * 2.1) * radius * 0.03f).toFloat()
            val amp = radius * (0.06f + 0.02f * rib)
            val p = ribbonPaths[rib]
            p.reset()
            val steps = 16
            for (s in 0..steps) {
                val fx = -1f + 2f * s / steps
                val x = centerX + fx * radius * 0.98f
                val y = baseY + (sin(fx * 2.6 + ph * twoPi * 2f + rib * 1.3) * amp).toFloat()
                if (s == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            for (pass in RIBBON_WIDTHS.indices) {
                ribbonPaint.strokeWidth = RIBBON_WIDTHS[pass] * radius
                ribbonPaint.color = RIBBON_COLORS[pass]
                ribbonPaint.alpha = (RIBBON_ALPHAS[pass] * pulse).toInt().coerceIn(0, 255)
                canvas.drawPath(p, ribbonPaint)
            }
        }
    }

    /**
     * Chingariyan: tay jagahon wali chhoti roshniyaan — dheere
     * ghoomti hain aur apni taal par tiltilati hain (koi
     * random nahi, isliye jhanjhat-bhari flicker nahi hota).
     */
    private fun drawSparks(canvas: Canvas, ph: Float, twoPi: Float) {
        for (i in 0 until SPARK_COUNT) {
            val ang = sparkAngle[i] + sparkDrift[i] * ph * twoPi
            val rr = sparkRad[i] * radius
            val sx = centerX + (cos(ang) * rr).toFloat()
            val sy = centerY + (sin(ang) * rr).toFloat()
            val tw = 0.5f + 0.5f * sin(ph * twoPi * sparkTwinkle[i] + i * 1.7).toFloat()
            val a = (60f + 180f * tw).toInt().coerceIn(0, 255)
            val sr = sparkSize[i] * radius
            sparkPaint.color = sparkColor[i]
            sparkPaint.alpha = (a * 0.25f).toInt()
            canvas.drawCircle(sx, sy, sr * 2.6f, sparkPaint)
            sparkPaint.alpha = a
            canvas.drawCircle(sx, sy, sr, sparkPaint)
        }
    }

    private companion object {
        const val BREATH_PERIOD_MS = 2_300L
        // Master bahav: ek poora chakra ~18 second (dheema,
        // plasma jaisa). SPEAKING me iski raftaar dugni se zyada.
        const val FLOW_PERIOD_MS = 18_000L
        const val SPEAKING_SPEED = 2.2f
        const val FRAME_MS = 33L
        const val GLOW_MIN = 0.5f
        const val GLOW_MAX = 0.95f
        const val SPHERE_FRACTION = 0.72f

        // Rang-blob ki taalika: [cyan, azure, magenta, pink].
        const val BLOB_COUNT = 4
        val BLOB_ORBIT = floatArrayOf(0.34f, 0.28f, 0.38f, 0.22f)
        val BLOB_SIZE = floatArrayOf(0.95f, 1.00f, 0.90f, 0.62f)
        val BLOB_START = floatArrayOf(0.8f, 3.6f, 3.9f, 5.2f)
        val BLOB_DIR = floatArrayOf(1f, -1f, 1f, 2f)
        val BLOB_COLORS = intArrayOf(
            Color.argb(175, 34, 211, 238),
            Color.argb(160, 37, 99, 235),
            Color.argb(180, 217, 70, 239),
            Color.argb(140, 236, 72, 153)
        )

        // Naram patti ke teen pass: chaudaai (radius ke hisse me)
        // aur har pass ki ghanaai (alpha).
        val BAND_WIDTHS = floatArrayOf(0.30f, 0.20f, 0.12f)
        val BAND_ALPHAS = intArrayOf(30, 55, 85)
        val SPIRAL_WIDTHS = floatArrayOf(0.30f, 0.19f, 0.10f)
        val SPIRAL_ALPHAS = intArrayOf(26, 46, 78)

        // Sonhri lakerein.
        const val RIBBON_COUNT = 3
        val RIBBON_WIDTHS = floatArrayOf(0.085f, 0.045f, 0.018f)
        val RIBBON_ALPHAS = intArrayOf(60, 120, 230)
        val RIBBON_COLORS = intArrayOf(
            Color.rgb(245, 158, 11),
            Color.rgb(251, 191, 36),
            Color.rgb(253, 230, 138)
        )

        const val SPARK_COUNT = 11
    }
}

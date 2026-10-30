package com.aship.jarvis.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
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
 * me ek overlay window me dikhata hai — bilkul Siri ke andaaz me:
 * gehra neela -> baingani -> gulabi (magenta) ki paraton wala ek
 * glossy gola, bahar halki si chamak (glow) ke saath.
 *
 * Do framework animator hi saari harkat karte hain (koi Compose
 * ya nayi dependency nahi):
 *  - "saans" (breathing): command ka intezaar karte samay gola
 *    dheere-dheere 1.0 <-> 1.12 size me phoolta-sikudta hai aur
 *    glow ki roshni bhi saath me badhti-ghatti hai.
 *  - "chamak" (shimmer): jawab bolte samay gole ke andar ki
 *    gulabi roshni gol-gol ghoomti hai, thodi tez gati se.
 *
 * CPU halka rahe isliye invalidate() ~30 fps par bandha hai —
 * animator har frame bulaye, par drawing usse zyada nahi hoti.
 * Window hat-te hi (onDetachedFromWindow / stopAnimations) saare
 * animator band ho jate hain.
 */
class OrbView(context: Context) : View(context) {

    enum class Mode { LISTENING, SPEAKING }

    private var mode: Mode = Mode.LISTENING

    // Animator se badalne wali haalat.
    private var pulseScale = 1f
    private var glowStrength = GLOW_MIN
    private var swirlPhase = 0f

    // Geometry (onSizeChanged me banti hai).
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swirlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(46, 255, 255, 255)
    }
    private val clipPath = Path()
    private val swirlMatrix = Matrix()

    private var lastInvalidateMs = 0L

    private val breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = BREATH_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animator ->
            val v = animator.animatedValue as Float
            pulseScale = 1f + PULSE_AMPLITUDE * v
            glowStrength = GLOW_MIN + (GLOW_MAX - GLOW_MIN) * v
            poke()
        }
    }

    private val shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SHIMMER_PERIOD_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { animator ->
            swirlPhase = animator.animatedValue as Float
            poke()
        }
    }

    /** Jawab bolna shuru ho to SPEAKING, command ka intezaar ho to LISTENING. */
    fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        if (newMode == Mode.SPEAKING) {
            if (!shimmerAnimator.isRunning) shimmerAnimator.start()
        } else {
            shimmerAnimator.cancel()
        }
    }

    fun startAnimations() {
        if (!breathingAnimator.isRunning) breathingAnimator.start()
        if (mode == Mode.SPEAKING && !shimmerAnimator.isRunning) {
            shimmerAnimator.start()
        }
    }

    fun stopAnimations() {
        breathingAnimator.cancel()
        shimmerAnimator.cancel()
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
        rimPaint.strokeWidth = (radius * 0.03f).coerceAtLeast(1f)
        clipPath.reset()
        clipPath.addCircle(centerX, centerY, radius, Path.Direction.CW)

        // Bahari glow: magenta -> baingani -> poora gayab.
        glowPaint.shader = RadialGradient(
            centerX, centerY, radius * 1.5f,
            intArrayOf(
                Color.argb(190, 255, 60, 172),
                Color.argb(110, 123, 47, 247),
                Color.argb(0, 123, 47, 247)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // Gole ki buniyad: upar-bayein roshni, neeche gehra neela.
        spherePaint.shader = RadialGradient(
            centerX - radius * 0.38f, centerY - radius * 0.42f, radius * 1.65f,
            intArrayOf(
                Color.rgb(199, 125, 255),
                Color.rgb(123, 47, 247),
                Color.rgb(58, 12, 163),
                Color.rgb(18, 8, 64)
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        // Ghoomti gulabi chamak: gradient (0,0) par bana hai, har
        // frame local matrix se use gole ke andar ghumate hain.
        swirlPaint.shader = RadialGradient(
            0f, 0f, radius * 0.9f,
            intArrayOf(
                Color.argb(165, 255, 60, 172),
                Color.argb(70, 255, 110, 199),
                Color.argb(0, 255, 60, 172)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        // Upar ki safed jhalak — glossy look ke liye.
        highlightPaint.shader = RadialGradient(
            centerX - radius * 0.4f, centerY - radius * 0.5f, radius * 0.55f,
            intArrayOf(
                Color.argb(215, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        canvas.save()
        // Saans ke saath poora gola (glow samet) phoolta hai.
        canvas.scale(pulseScale, pulseScale, centerX, centerY)

        // 1) Bahari chamak.
        glowPaint.alpha = (glowStrength * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, radius * 1.5f, glowPaint)

        // 2) Gole ke andar ki paratein — kinare se bahar nahi.
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawCircle(centerX, centerY, radius, spherePaint)
        val angle = swirlPhase * 2.0 * PI
        val swirlX = centerX + (cos(angle) * radius * 0.42).toFloat()
        val swirlY = centerY + radius * 0.18f + (sin(angle) * radius * 0.30).toFloat()
        swirlMatrix.setTranslate(swirlX, swirlY)
        swirlPaint.shader?.setLocalMatrix(swirlMatrix)
        canvas.drawCircle(swirlX, swirlY, radius, swirlPaint)
        canvas.drawCircle(centerX, centerY, radius, highlightPaint)
        canvas.restore()

        // 3) Halki safed kinari.
        canvas.drawCircle(centerX, centerY, radius, rimPaint)
        canvas.restore()
    }

    private companion object {
        const val BREATH_PERIOD_MS = 2_300L
        const val SHIMMER_PERIOD_MS = 900L
        const val FRAME_MS = 33L
        const val PULSE_AMPLITUDE = 0.12f
        const val GLOW_MIN = 0.5f
        const val GLOW_MAX = 0.95f
        const val SPHERE_FRACTION = 0.72f
    }
}

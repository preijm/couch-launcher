package nl.reijm.banklaunchtv

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The "ambilight" background: a deep evening base with a soft light that takes on the colour
 * of the selected app, follows it along the row and slowly breathes.
 */
class GlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var color = Color.parseColor("#3D4BB0")
    private var cx = 0.25f          // glow centre, fraction of the width
    private var breathe = 0f        // 0..1, slow loop
    private var intensity = 0f      // 0..1, fades in on start / out on launch

    private var colorAnim: ValueAnimator? = null
    private var xAnim: ValueAnimator? = null
    private var intensityAnim: ValueAnimator? = null

    private val breatheAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 7000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { breathe = it.animatedValue as Float; invalidate() }
    }

    fun resumeBreathing() { if (!breatheAnim.isStarted) breatheAnim.start() else breatheAnim.resume() }
    fun pauseBreathing() { breatheAnim.pause() }

    fun fadeTo(target: Float, ms: Long, onEnd: (() -> Unit)? = null) {
        intensityAnim?.cancel()
        intensityAnim = ValueAnimator.ofFloat(intensity, target).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { intensity = it.animatedValue as Float; invalidate() }
            if (onEnd != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    fun setIntensityNow(v: Float) { intensityAnim?.cancel(); intensity = v; invalidate() }

    /** Move the light to [xFraction] of the width and blend to [newColor]. */
    fun focusOn(newColor: Int, xFraction: Float) {
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), color, newColor).apply {
            duration = 650
            addUpdateListener { color = it.animatedValue as Int; invalidate() }
            start()
        }
        xAnim?.cancel()
        xAnim = ValueAnimator.ofFloat(cx, xFraction).apply {
            duration = 900
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { cx = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        breatheAnim.cancel(); colorAnim?.cancel(); xAnim?.cancel(); intensityAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(BASE)

        // Main light: rises from below the selected tile.
        blob(canvas, cx * w, h * 1.08f, h * (1.0f + 0.10f * breathe), 0.70f * intensity)
        // Spill light: a weaker glow drifting in the top-right corner, like light on the wall.
        blob(canvas, w * (0.88f - 0.05f * breathe), h * (-0.10f + 0.06f * breathe), h * 0.80f, 0.26f * intensity)
        // Floor: keep the bottom edge from going flat.
        blob(canvas, w * (1f - cx) , h * 1.25f, h * 0.9f, 0.18f * intensity)
    }

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, alpha: Float) {
        if (alpha <= 0.01f || r <= 0f) return
        paint.shader = RadialGradient(
            x, y, r,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha * 0.38f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawCircle(x, y, r, paint)
    }

    private fun withAlpha(c: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** Deep evening blue, not black: the glow needs something to light up. */
        val BASE = Color.parseColor("#0C1224")
    }
}

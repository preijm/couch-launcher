package nl.reijm.banklaunchtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Thin bar split into one segment per app, biggest first; the first is white, the rest step down in grey. */
class SegmentBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shades = intArrayOf(Color.WHITE, 0xFF8A8A8A.toInt(), 0xFF5E5E5E.toInt(), 0xFF454545.toInt(), 0xFF363636.toInt())
    private var parts = floatArrayOf()
    private var progress = 1f
    private val r = RectF()

    fun setValues(values: List<Long>) {
        val total = values.sum().toFloat()
        parts = if (total <= 0f) floatArrayOf() else values.sortedDescending().map { it / total }.toFloatArray()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val gap = resources.displayMetrics.density * 3
        val usable = (width - gap * (parts.size - 1).coerceAtLeast(0)) * progress
        var x = 0f
        val rad = height / 2f
        if (parts.isEmpty()) {
            paint.color = 0xFF262626.toInt(); r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, paint)
            return
        }
        parts.forEachIndexed { i, p ->
            val w = usable * p
            if (w < 1f) return@forEachIndexed
            paint.color = shades[i.coerceAtMost(shades.size - 1)]
            r.set(x, 0f, x + w, height.toFloat())
            c.drawRoundRect(r, rad, rad, paint)
            x += w + gap
        }
    }
}

/** Seven small bars, one per day; today (the last) is white. */
class MiniBars @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values = LongArray(7)
    private var progress = 1f
    private val r = RectF()

    fun setValues(v: LongArray) {
        values = v
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        if (n == 0) return
        val d = resources.displayMetrics.density
        val gap = 5 * d
        val bw = (width - gap * (n - 1)) / n
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val minH = 3 * d
        values.forEachIndexed { i, v ->
            val h = (minH + (height - minH) * (v / max)) * progress
            val x = i * (bw + gap)
            paint.color = if (i == n - 1) Color.WHITE else 0xFF4A4A4A.toInt()
            r.set(x, height - h.coerceAtLeast(minH * progress), x + bw, height.toFloat())
            c.drawRoundRect(r, 1.5f * d, 1.5f * d, paint)
        }
    }
}

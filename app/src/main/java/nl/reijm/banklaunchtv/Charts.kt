package nl.reijm.banklaunchtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

private fun View.grow(ms: Long, update: (Float) -> Unit) =
    ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ms; interpolator = DecelerateInterpolator(2f)
        addUpdateListener { update(it.animatedValue as Float); invalidate() }
        start()
    }

/** One bar split into a coloured segment per app, in app order, sized by watch time. */
class SegmentBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var parts = listOf<Pair<Float, Int>>()
    private var progress = 1f
    private val r = RectF()

    fun setValues(values: List<Pair<Long, Int>>) {
        val total = values.sumOf { it.first }.toFloat()
        parts = if (total <= 0f) emptyList() else values.filter { it.first > 0 }.map { it.first / total to it.second }
        grow(900) { progress = it }
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val gap = 2 * d
        val rad = 1.5f * d
        if (parts.isEmpty()) {
            paint.color = 0xFF1A1A1A.toInt(); r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, paint)
            return
        }
        val usable = (width - gap * (parts.size - 1)) * progress
        var x = 0f
        parts.forEach { (p, color) ->
            val w = usable * p
            paint.color = color
            r.set(x, 0f, x + w, height.toFloat())
            c.drawRoundRect(r, rad, rad, paint)
            x += w + gap
        }
    }
}

/** This week Monday..Sunday with day labels; today in the app colour, other days dimmed, future days empty. */
class WeekBars @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val days = arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    private val TODAY_MIN = 10 * 60_000L

    /** "35m", "1h 20m", "2h". */
    private fun short(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0L -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
    private var values = LongArray(7) { -1L }
    private var color = Color.WHITE
    private var progress = 1f
    private val r = RectF()

    fun setValues(v: LongArray, appColor: Int) {
        values = v
        color = appColor
        grow(600) { progress = it }
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        text.textSize = 8 * resources.displayMetrics.scaledDensity
        val labelH = 13 * d
        val valueH = 12 * d // room above the bars for today's value
        val barMax = height - labelH
        val gap = 5 * d
        val bw = (width - gap * 6) / 7
        val max = values.maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
        val today = values.indexOfLast { it >= 0 }
        val dim = Color.rgb(
            (Color.red(color) * 0.36f).toInt(), (Color.green(color) * 0.36f).toInt(), (Color.blue(color) * 0.36f).toInt(),
        )
        for (i in 0 until 7) {
            val v = values[i]
            val x = i * (bw + gap)
            val h = if (v < 0) 1.5f * d else ((2 * d) + (barMax - valueH - 2 * d) * (v / max)) * progress
            paint.color = when { v < 0 -> 0xFF2A2A2A.toInt(); i == today -> color; else -> dim }
            r.set(x, barMax - h, x + bw, barMax)
            c.drawRoundRect(r, 2 * d, 2 * d, paint)
            text.color = if (i == today) Color.WHITE else 0xFF9A9A9A.toInt()
            c.drawText(days[i], x + bw / 2, height - 2 * d, text)
            // Today's time above its bar, once it's worth mentioning.
            if (i == today && v >= TODAY_MIN && progress > 0.9f) {
                text.color = Color.WHITE
                c.drawText(short(v), x + bw / 2, barMax - h - 3 * d, text)
            }
        }
    }
}

/** Thin track with a coloured fill: this app's time relative to the most-watched app. */
class ShareLine @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fraction = 0f
    private var color = Color.WHITE
    private var progress = 1f
    private val r = RectF()

    fun setValue(f: Float, appColor: Int) {
        fraction = f.coerceIn(0f, 1f)
        color = appColor
        grow(700) { progress = it }
    }

    override fun onDraw(c: Canvas) {
        val rad = height / 2f
        paint.color = 0xFF1A1A1A.toInt()
        r.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(r, rad, rad, paint)
        if (fraction <= 0f) return
        paint.color = color
        r.set(0f, 0f, width * fraction * progress, height.toFloat()); c.drawRoundRect(r, rad, rad, paint)
    }
}

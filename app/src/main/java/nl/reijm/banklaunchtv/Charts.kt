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
    /** Today's time above its bar: big enough to read from the couch. */
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Color.WHITE
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
        value.textSize = 13 * resources.displayMetrics.scaledDensity
        val valueH = 18 * d // room above the bars for today's value
        val barMax = height - labelH
        val gap = 5 * d
        val bw = (width - gap * 6) / 7
        // At least half an hour as the top, so a few seconds of use doesn't show as a full bar.
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(30 * 60_000L).toFloat()
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
                // Centred on the bar, but kept inside the chart (Sunday is at the right edge).
                val t = short(v)
                val half = value.measureText(t) / 2
                val cx = (x + bw / 2).coerceIn(half, width - half)
                // Always in the band above the tallest bar, so it never sits on another day's bar.
                c.drawText(t, cx, valueH - 4 * d, value)
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

/** An app's first letter, as a thin icon that takes the ImageView's tint like the line icons. */
class LetterIcon(private val letter: String) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private var tint: android.content.res.ColorStateList? = null

    override fun draw(c: Canvas) {
        val b = bounds
        paint.color = tint?.getColorForState(state, Color.WHITE) ?: Color.WHITE
        paint.textSize = b.height() * 1.05f
        val y = b.exactCenterY() - (paint.descent() + paint.ascent()) / 2
        c.drawText(letter, b.exactCenterX(), y, paint)
    }

    override fun setTintList(t: android.content.res.ColorStateList?) { tint = t; invalidateSelf() }
    override fun isStateful() = tint?.isStateful == true
    override fun onStateChange(s: IntArray): Boolean { invalidateSelf(); return true }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { paint.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Average per day for a row of weeks or months, oldest first: grey bars, the last one (now) in
 * white with its value, and a dashed line for "usual". Missing periods show as a thin stub.
 */
class TrendBars @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF8A8A8A.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = Color.WHITE
    }
    private var values = listOf<Long?>()
    private var labels = listOf<String>()
    private var usual = 0L
    private var progress = 1f
    private val r = RectF()

    fun setValues(v: List<Long?>, l: List<String>, usualPerDay: Long) {
        values = v; labels = l; usual = usualPerDay
        grow(700) { progress = it }
    }

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        label.textSize = 10 * sp
        value.textSize = 12 * sp
        line.strokeWidth = 1.2f * d
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
        val labelH = 16 * d
        val top = 20 * d
        val bottom = height - labelH
        val usualW = 44 * d // room on the right for the "usual" label
        val gap = 8 * d
        val n = values.size
        // With only a few bars, keep them bar-shaped instead of stretching them wide.
        val bw = minOf((width - usualW - gap * (n - 1)) / n, 64 * d)
        val max = maxOf(values.filterNotNull().maxOrNull() ?: 0L, usual, 30 * 60_000L).toFloat()
        fun y(ms: Long) = bottom - (bottom - top) * (ms / max)

        // The "usual" line first, so the bars sit on top of it.
        if (usual > 0) {
            val uy = y(usual)
            c.drawLine(0f, uy, width - usualW + 4 * d, uy, line)
            label.color = 0xFF9A9A9A.toInt()
            label.textAlign = Paint.Align.LEFT
            c.drawText("usual", width - usualW + 8 * d, uy + 3.5f * d, label)
            label.textAlign = Paint.Align.CENTER
        }

        values.forEachIndexed { i, v ->
            val x = i * (bw + gap)
            val last = i == n - 1
            val h = if (v == null) 1.5f * d else ((bottom - y(v)) * progress).coerceAtLeast(2 * d)
            paint.color = when { v == null -> 0xFF2A2A2A.toInt(); last -> Color.WHITE; else -> 0xFF3E3E3E.toInt() }
            r.set(x, bottom - h, x + bw, bottom)
            c.drawRoundRect(r, 2 * d, 2 * d, paint)
            label.color = if (last) Color.WHITE else 0xFF8A8A8A.toInt()
            c.drawText(labels.getOrElse(i) { "" }, x + bw / 2, height - 3 * d, label)
            // Every bar gets its value, so ups and downs are easy to read; now in white.
            if (v != null && progress > 0.9f) {
                val t = WeekBarsText.short(v)
                value.textSize = (if (last) 12 else 10) * sp
                value.color = if (last) Color.WHITE else 0xFFB0B0B0.toInt()
                val half = value.measureText(t) / 2
                c.drawText(t, (x + bw / 2).coerceIn(half, width - usualW - half), bottom - h - 5 * d, value)
            }
        }
    }
}

/** Short times for chart labels: "35m", "1h 20m", "2h". */
object WeekBarsText {
    fun short(ms: Long): String {
        val m = (ms + 30_000) / 60_000
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0L -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}

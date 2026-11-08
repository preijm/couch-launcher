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
 * Average per day for a row of weeks or months, each bar split into blocks per app (app colours,
 * first app at the bottom). The chosen bar is in full colour, the others a little faded. Missing periods
 * show as a thin stub.
 */
class StackedBars @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var bars = listOf<List<Pair<Long, Int>>?>()
    private var labels = listOf<String>()
    private var selected = -1
    private var progress = 1f
    private val r = RectF()
    private val clip = android.graphics.Path()

    /** [b]: per bar the blocks (time per day, colour), bottom first; null = no record. */
    fun setData(b: List<List<Pair<Long, Int>>?>, l: List<String>) {
        bars = b; labels = l
        grow(700) { progress = it }
    }

    fun select(index: Int) { selected = index; invalidate() }

    override fun onDraw(c: Canvas) {
        if (bars.isEmpty()) return
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        val labelH = 20 * d
        val top = 24 * d
        val bottom = height - labelH
        val gap = 16 * d
        val n = bars.size
        val bw = minOf((width - gap * (n - 1)) / n, 96 * d)
        val max = maxOf(bars.maxOf { b -> b?.sumOf { it.first } ?: 0L }, 30 * 60_000L).toFloat()

        bars.forEachIndexed { i, b ->
            val x = i * (bw + gap)
            val chosen = i == selected
            val total = b?.sumOf { it.first } ?: 0L
            if (b == null || total <= 0L) {
                paint.color = 0xFF2A2A2A.toInt()
                r.set(x, bottom - 1.5f * d, x + bw, bottom)
                c.drawRect(r, paint)
            } else {
                val h = ((bottom - top) * (total / max) * progress).coerceAtLeast(2 * d)
                c.save()
                clip.reset()
                r.set(x, bottom - h, x + bw, bottom)
                clip.addRoundRect(r, 3 * d, 3 * d, android.graphics.Path.Direction.CW)
                c.clipPath(clip)
                var y = bottom
                b.forEach { (ms, color) ->
                    if (ms <= 0) return@forEach
                    val sh = h * ms / total
                    paint.color = color
                    paint.alpha = if (chosen) 255 else 150 // still readable, so each app can be followed across bars
                    r.set(x, y - sh, x + bw, y)
                    c.drawRect(r, paint)
                    y -= sh
                }
                c.restore()
                paint.alpha = 255
                if (progress > 0.9f) {
                    value.textSize = (if (chosen) 14 else 12) * sp
                    value.typeface = Typeface.create(if (chosen) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
                    value.color = if (chosen) Color.WHITE else 0xFF9A9A9A.toInt()
                    c.drawText(WeekBarsText.short(total), x + bw / 2, bottom - h - 7 * d, value)
                }
            }
            label.textSize = 12 * sp
            label.color = if (chosen) Color.WHITE else 0xFF8A8A8A.toInt()
            c.drawText(labels.getOrElse(i) { "" }, x + bw / 2, height - 4 * d, label)
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

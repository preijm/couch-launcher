package nl.reijm.banklaunchtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject

/**
 * One app in the home list.
 *
 * The app is found by the first installed package in [packages]. Watch time is counted over
 * all of them together, so YouTube history from before SmartTube still shows.
 */
data class AppSpec(
    val name: String,
    /** One of [AppList.ICONS], or null to show the name's first letter. */
    val iconKey: String?,
    /** Colour of this app in the charts. */
    val color: String,
    val packages: List<String>,
) {
    val icon: Int get() = AppList.ICONS[iconKey] ?: 0
}

/**
 * The home list, kept on the TV so it can be changed from the TV (add, move, remove).
 * Starts with the five apps below.
 */
object AppList {
    const val MAX = 7

    val ICONS = mapOf(
        "youtube" to R.drawable.ic_youtube,
        "netflix" to R.drawable.ic_netflix,
        "nos" to R.drawable.ic_nos,
        "npo" to R.drawable.ic_npo,
        "prime" to R.drawable.ic_prime,
    )

    private val DEFAULTS = listOf(
        // TizenTube Cobalt first (modern YouTube TV look, no ads), then SmartTube, then official YouTube.
        AppSpec(
            "YouTube", "youtube", "#FF3B30",
            listOf(
                "io.gh.reisxd.tizentube.cobalt", "org.smarttube.stable", "org.smarttube.beta",
                "com.google.android.youtube.tv",
            ),
        ),
        AppSpec("Netflix", "netflix", "#9E0B12", listOf("com.netflix.ninja")),
        AppSpec("NOS", "nos", "#F2F2F2", listOf("nl.nos.app")),
        AppSpec("NPO Start", "npo", "#FF7A00", listOf("nl.uitzendinggemist")),
        AppSpec("Prime Video", "prime", "#1A98FF", listOf("com.amazon.amazonvideo.livingroom")),
    )

    /** Spare colours for added apps when their own icon colour is too close to one in use. */
    private val SPARE = listOf("#B05CFF", "#2ED47A", "#FFD60A", "#FF5FA2", "#22D3EE", "#A3A3A3")

    private fun prefs(c: Context) = c.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun load(c: Context): List<AppSpec> {
        val raw = prefs(c).getString("list", null) ?: return DEFAULTS
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val p = o.getJSONArray("packages")
                AppSpec(
                    o.getString("name"),
                    o.optString("icon").ifEmpty { null },
                    o.getString("color"),
                    List(p.length()) { p.getString(it) },
                )
            }
        }.getOrDefault(DEFAULTS)
    }

    fun save(c: Context, list: List<AppSpec>) {
        val a = JSONArray()
        list.forEach { s ->
            a.put(JSONObject().apply {
                put("name", s.name)
                put("icon", s.iconKey ?: "")
                put("color", s.color)
                put("packages", JSONArray(s.packages))
            })
        }
        prefs(c).edit().putString("list", a.toString()).apply()
    }

    /** A new entry for an installed app: its own name, a letter icon and a colour from its icon. */
    fun newSpec(label: String, pkg: String, icon: Drawable?, inUse: List<AppSpec>): AppSpec {
        val used = inUse.map { Color.parseColor(it.color) }
        val own = icon?.let { dominantColor(it) }
        val color = listOfNotNull(own).plus(SPARE.map { Color.parseColor(it) })
            .first { cand -> used.none { tooClose(it, cand) } }
        return AppSpec(label, null, String.format("#%06X", color and 0xFFFFFF), listOf(pkg))
    }

    /** The most common bright, saturated colour in an icon; null for grey or dark icons. */
    private fun dominantColor(d: Drawable): Int? {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val buckets = HashMap<Int, MutableList<Int>>()
        val hsv = FloatArray(3)
        for (y in 0 until size) for (x in 0 until size) {
            val px = bmp.getPixel(x, y)
            if (Color.alpha(px) < 200) continue
            Color.colorToHSV(px, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
            buckets.getOrPut((hsv[0] / 20).toInt()) { mutableListOf() } += px
        }
        val best = buckets.values.maxByOrNull { it.size } ?: return null
        if (best.size < size * size / 40) return null
        Color.colorToHSV(best[best.size / 2], hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.6f); hsv[2] = hsv[2].coerceAtLeast(0.85f) // bright enough on black
        return Color.HSVToColor(hsv)
    }

    private fun tooClose(a: Int, b: Int): Boolean {
        val ha = FloatArray(3).also { Color.colorToHSV(a, it) }
        val hb = FloatArray(3).also { Color.colorToHSV(b, it) }
        val grey = ha[1] < 0.2f || hb[1] < 0.2f
        if (grey) return ha[1] < 0.2f && hb[1] < 0.2f
        val dh = Math.abs(ha[0] - hb[0]).let { minOf(it, 360 - it) }
        return dh < 28 && Math.abs(ha[2] - hb[2]) < 0.35f
    }
}

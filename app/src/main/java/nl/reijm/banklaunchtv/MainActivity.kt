package nl.reijm.banklaunchtv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private class Tile(
        val label: String,
        val banner: Drawable?,
        val icon: Drawable?,
        val launch: () -> Intent?,
    )

    private lateinit var rowsView: LinearLayout
    private lateinit var scroll: ScrollView
    private var dirty = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dirty = true
            if (hasWindowFocus()) rebuild()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rowsView = findViewById(R.id.rows)
        scroll = findViewById(R.id.scroll)
        rebuild()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (dirty) rebuild()
    }

    /** Home pressed while we're already in front: jump back to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        focusFirst()
    }

    // A home screen has nowhere to go back to.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { focusFirst() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) focusFirst()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ rows

    private fun rebuild() {
        dirty = false
        rowsView.removeAllViews()

        val all = leanbackApps()
        val byPkg = all.associateBy { it.first }

        val favs = Config.favourites.mapNotNull { byPkg[it]?.second }
        if (favs.isNotEmpty()) addRow("Favorieten", favs)

        addRow("Ingangen", hdmiInputs() + settingsTile())

        val rest = all.filter { it.first !in Config.hidden }.map { it.second }
        addRow("Alle apps", rest)

        focusFirst()
    }

    /** All apps with a TV (leanback) launcher entry, sorted by name. */
    private fun leanbackApps(): List<Pair<String, Tile>> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                pkg to Tile(
                    label = ri.loadLabel(pm).toString(),
                    banner = ri.activityInfo.loadBanner(pm),
                    icon = ri.loadIcon(pm),
                    launch = {
                        pm.getLeanbackLaunchIntentForPackage(pkg)
                            ?: pm.getLaunchIntentForPackage(pkg)
                    },
                )
            }
            .sortedBy { it.second.label.lowercase() }
    }

    private fun hdmiInputs(): List<Tile> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            tim.tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(this) }
                .map { info ->
                    val label = (info.loadCustomLabel(this) ?: info.loadLabel(this)).toString()
                    Tile(label, null, info.loadIcon(this)) {
                        Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
                    }
                }
                .sortedBy { it.label }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list TV inputs", e)
            emptyList()
        }
    }

    private fun settingsTile() = Tile(
        "Instellingen", null, getDrawable(android.R.drawable.ic_menu_preferences),
    ) { Intent(Settings.ACTION_SETTINGS) }

    private fun addRow(title: String, tiles: List<Tile>) {
        if (tiles.isEmpty()) return

        val heading = TextView(this).apply {
            text = title.uppercase()
            setTextColor(Color.parseColor("#99F2EDE4"))
            textSize = 13f
            letterSpacing = 0.15f
            setPadding(dp(2), dp(14), 0, 0)
        }
        rowsView.addView(heading)

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // room for the focused tile to scale up without being clipped
            setPadding(dp(10), dp(12), dp(10), dp(12))
            clipChildren = false
            clipToPadding = false
        }
        tiles.forEach { strip.addView(tileView(it)) }

        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(strip)
        }
        rowsView.addView(hs, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = -dp(10) })
    }

    private fun tileView(t: Tile): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            setBackgroundColor(Color.parseColor("#2C3440"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(10).toFloat())
                }
            }
            clipToOutline = true
            contentDescription = t.label
        }

        if (t.banner != null) {
            frame.addView(ImageView(this).apply {
                setImageDrawable(t.banner)
                scaleType = ImageView.ScaleType.FIT_XY
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            frame.addView(ImageView(this).apply {
                setImageDrawable(t.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(14) })
            frame.addView(TextView(this).apply {
                text = t.label
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = dp(10); leftMargin = dp(8); rightMargin = dp(8)
            })
        }

        frame.setOnFocusChangeListener { v, has ->
            val s = if (has) 1.1f else 1f
            v.animate().scaleX(s).scaleY(s).translationZ(if (has) dp(8).toFloat() else 0f)
                .setDuration(130).start()
            v.foreground = if (has) getDrawable(R.drawable.tile_focus) else null
        }
        frame.setOnClickListener { launch(t) }

        frame.layoutParams = LinearLayout.LayoutParams(dp(160), dp(90)).apply { rightMargin = dp(16) }
        return frame
    }

    private fun launch(t: Tile) {
        val intent = try { t.launch() } catch (e: Exception) { null }
        if (intent == null) {
            Toast.makeText(this, "Kan ${t.label} niet openen", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${t.label}", e)
            Toast.makeText(this, "Kan ${t.label} niet openen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusFirst() {
        scroll.scrollTo(0, 0)
        val firstStrip = (0 until rowsView.childCount)
            .map { rowsView.getChildAt(it) }
            .firstOrNull { it is HorizontalScrollView } as? HorizontalScrollView ?: return
        firstStrip.scrollTo(0, 0)
        (firstStrip.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BankLaunch"
    }
}

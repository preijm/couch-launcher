package nl.reijm.banklaunchtv

import android.animation.ArgbEvaluator
import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    /** An installed app as found on the TV. */
    private class Installed(val pkg: String, val label: String, val banner: Drawable?)

    private class Row(
        val spec: AppSpec,
        val app: Installed?,
        val view: LinearLayout,
        val bg: GradientDrawable,
        val icon: ImageView,
        val name: TextView,
        val time: TextView,
        val detail: View,
        val week: TextView,
        val month: TextView,
        val year: TextView,
        val bars: MiniBars,
    )

    private val nl = Locale("nl", "NL")
    private val timeFmt = SimpleDateFormat("HH:mm", nl)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", nl)

    private lateinit var root: FrameLayout
    private lateinit var left: View
    private lateinit var list: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private lateinit var panelScroll: ScrollView

    private val rows = mutableListOf<Row>()
    private var current = 0
    private var stats: Map<String, AppStats> = emptyMap()
    private var hasUsage = false

    private lateinit var usage: UsageRepo
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ticks = 0

    private var launching = false
    private var dirty = false
    private var replayIntro = false
    private var visible = false

    /** Preview mode (am start ... --ez demo true): sample numbers for screenshots off-TV. */
    private val demo get() = intent?.getBooleanExtra("demo", false) == true

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateClock()
            if (++ticks % 5 == 0 && visible) loadStats()
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> replayIntro = true
                Intent.ACTION_SCREEN_ON -> if (replayIntro && visible) { replayIntro = false; closeOverlay(); playIntro() }
            }
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            if (hasWindowFocus()) { buildList(); rows.getOrNull(current)?.view?.requestFocus() }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        left = findViewById(R.id.left)
        list = findViewById(R.id.list)
        overlay = findViewById(R.id.overlay)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        panelScroll = findViewById(R.id.panelScroll)
        usage = UsageRepo(this)
        list.layoutTransition.enableTransitionType(LayoutTransition.CHANGING)

        val btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnInputs = findViewById<ImageButton>(R.id.btnInputs)
        val btnApps = findViewById<ImageButton>(R.id.btnApps)
        listOf(btnSettings, btnInputs, btnApps).forEach { it.isFocusableInTouchMode = true }
        btnSettings.setOnClickListener { startSafely(Intent(Settings.ACTION_SETTINGS), "Instellingen") }
        btnInputs.setOnClickListener { showInputs() }
        btnApps.setOnClickListener { showAllApps() }
        btnApps.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == KeyEvent.ACTION_DOWN) {
                rows.getOrNull(current)?.view?.requestFocus(); true
            } else false
        }

        buildList()
        updateClock()
        register(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        register(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        register(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        playIntro()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        updateClock()
        loadStats()
        if (launching) { launching = false; root.alpha = 1f; rows.getOrNull(current)?.view?.requestFocus() }
        if (dirty) { buildList(); rows.getOrNull(current)?.view?.requestFocus() }
        if (replayIntro && isScreenOn()) { replayIntro = false; closeOverlay(); playIntro() }
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(tickReceiver)
        unregisterReceiver(screenReceiver)
        unregisterReceiver(packageReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while we're in front: close any panel and go back to the top of the list. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeOverlay()
        rows.firstOrNull()?.view?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (overlay.visibility == View.VISIBLE) closeOverlay()
                else rows.firstOrNull()?.view?.requestFocus()
            }
            return true // a home screen has nowhere to go back to
        }
        return super.dispatchKeyEvent(event)
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    private fun isScreenOn() = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    // ------------------------------------------------------------------ clock & stats

    private fun updateClock() {
        val now = Date()
        findViewById<TextView>(R.id.clock).text = timeFmt.format(now)
        findViewById<TextView>(R.id.date).text = dateFmt.format(now)
    }

    private fun loadStats() {
        if (demo) { showStats(demoStats(), true); return }
        io.execute {
            val ok = usage.hasPermission()
            val s = if (ok) runCatching { usage.load(Config.apps) }
                .onFailure { Log.w(TAG, "Could not read usage", it) }.getOrDefault(emptyMap()) else emptyMap()
            main.post { if (!isDestroyed) showStats(s, ok) }
        }
    }

    private fun showStats(s: Map<String, AppStats>, ok: Boolean) {
        stats = s
        hasUsage = ok
        val weeks = Config.apps.map { s[it.name]?.week ?: 0L }
        val total = weeks.sum()
        findViewById<TextView>(R.id.weekTotal).text = if (ok) UsageRepo.format(total) else "–"
        findViewById<SegmentBar>(R.id.segments).setValues(if (ok) weeks else emptyList())
        val daysSoFar = java.util.Calendar.getInstance().let { (it.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 + 1 }
        findViewById<TextView>(R.id.perDay).text =
            if (ok) "≈ ${UsageRepo.format(total / daysSoFar)} per dag" else "Kijktijd nog niet beschikbaar"
        rows.forEach { fillRow(it) }
    }

    private fun fillRow(r: Row) {
        val st = stats[r.spec.name]
        r.time.text = if (hasUsage && st != null) UsageRepo.format(st.week) else ""
        r.week.text = st?.let { UsageRepo.format(it.week) } ?: "–"
        r.month.text = st?.let { UsageRepo.format(it.month) } ?: "–"
        r.year.text = st?.let { UsageRepo.format(it.year) } ?: "–"
        if (r.view.hasFocus() || rows.indexOf(r) == current) r.bars.setValues(st?.last7 ?: LongArray(7))
    }

    private fun demoStats(): Map<String, AppStats> {
        val m = 60_000L
        fun s(w: Long, mo: Long, y: Long, vararg d: Long) = AppStats(w * m, mo * m, y * m, d.map { it * m }.toLongArray())
        return mapOf(
            "YouTube" to s(370, 1290, 8880, 35, 50, 25, 70, 75, 95, 60),
            "Netflix" to s(205, 810, 5400, 40, 0, 65, 30, 0, 70, 0),
            "NOS" to s(65, 300, 2100, 10, 12, 8, 10, 15, 10, 0),
            "NPO Start" to s(50, 140, 900, 0, 20, 0, 0, 30, 0, 0),
            "Prime Video" to s(70, 200, 1300, 0, 0, 0, 70, 0, 0, 0),
        )
    }

    // ------------------------------------------------------------------ the list

    private fun installedApps(): Map<String, Installed> {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(q, 0).associate { ri ->
            val pkg = ri.activityInfo.packageName
            pkg to Installed(pkg, ri.loadLabel(pm).toString(), ri.activityInfo.loadBanner(pm))
        }
    }

    private fun buildList() {
        dirty = false
        val apps = installedApps()
        list.removeAllViews()
        rows.clear()
        Config.apps.forEachIndexed { i, spec ->
            val app = if (demo) null else spec.packages.firstNotNullOfOrNull { apps[it] }
            val r = rowView(spec, app)
            rows += r
            list.addView(r.view)
            r.view.setOnFocusChangeListener { _, has -> setExpanded(r, has); if (has) { current = i } }
            r.view.setOnClickListener { open(r) }
            r.view.nextFocusLeftId = R.id.btnApps
            if (i == 0 && Build.VERSION.SDK_INT >= 26) r.view.isFocusedByDefault = true
            fillRow(r)
        }
        current = current.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }

    private fun rowView(spec: AppSpec, app: Installed?): Row {
        val bg = GradientDrawable().apply { setColor(0xFF161616.toInt()); cornerRadius = dp(14).toFloat(); alpha = 0 }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(22), dp(10), dp(22), dp(10))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = spec.name
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) }
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            setImageResource(spec.icon)
            imageTintList = ColorStateList.valueOf(DIM)
        }
        val name = TextView(this).apply {
            text = spec.name
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SMALL)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
        }
        val time = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)))
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
        header.addView(time)
        view.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            visibility = View.GONE
        }
        fun stat(label: String): TextView {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.label))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                letterSpacing = 0.1f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            val v = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            col.addView(v)
            detail.addView(col, LinearLayout.LayoutParams(dp(96), -2))
            return v
        }
        val week = stat("WEEK")
        val month = stat("MAAND")
        val year = stat("JAAR")
        detail.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val bars = MiniBars(this)
        detail.addView(bars, LinearLayout.LayoutParams(dp(84), dp(30)))
        view.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })

        view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
        return Row(spec, app, view, bg, icon, name, time, detail, week, month, year, bars)
    }

    /** The selected app opens into a card with week / month / year and the last 7 days. */
    private fun setExpanded(r: Row, on: Boolean) {
        r.detail.visibility = if (on) View.VISIBLE else View.GONE
        val from = r.bg.alpha
        ValueAnimator.ofInt(from, if (on) 255 else 0).apply {
            duration = 220
            addUpdateListener { r.bg.alpha = it.animatedValue as Int }
            start()
        }
        val c0 = r.name.currentTextColor
        val c1 = if (on) Color.WHITE else DIM
        val s0 = r.name.textSize / resources.displayMetrics.scaledDensity
        val s1 = if (on) NAME_BIG else NAME_SMALL
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                r.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, s0 + (s1 - s0) * f)
                val c = ArgbEvaluator().evaluate(f, c0, c1) as Int
                r.name.setTextColor(c)
                r.icon.imageTintList = ColorStateList.valueOf(c)
            }
            start()
        }
        r.name.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif-light", Typeface.NORMAL)
        r.time.setTextColor(if (on) Color.WHITE else DIM)
        r.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (on) 17f else 13f)
        (r.icon.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(if (on) 22 else 18); height = width
        }
        r.icon.requestLayout()
        if (on) r.bars.setValues(stats[r.spec.name]?.last7 ?: LongArray(7))
        if (r.app == null && on && !demo) r.time.text = "niet op de tv"
        else if (!on) fillRow(r)
    }

    // ------------------------------------------------------------------ opening apps

    private fun open(r: Row) {
        val app = r.app
        if (app == null) {
            if (!demo) Toast.makeText(this, "${r.spec.name} staat nog niet op de tv", Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Toast.makeText(this, "${r.spec.name} kan niet worden geopend", Toast.LENGTH_LONG).show()
            return
        }
        launching = true
        r.view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).withEndAction {
            r.view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            root.animate().alpha(0f).setDuration(180).withEndAction { startSafely(intent, r.spec.name) }.start()
        }.start()
    }

    private fun startSafely(intent: Intent, label: String) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $label", e)
            Toast.makeText(this, "$label kan niet worden geopend", Toast.LENGTH_LONG).show()
            launching = false
            root.alpha = 1f
        }
    }

    // ------------------------------------------------------------------ inputs & all apps

    private fun showInputs() {
        val inputs: List<Pair<String, () -> Unit>> = if (demo) {
            listOf("Spel (HDMI 1)", "Spel (HDMI 2)", "Xbox (HDMI 3)", "Mediabox (HDMI 4)").map { it to {} }
        } else hdmiInputs().map { (label, id) ->
            label to {
                closeOverlay()
                startSafely(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)), label)
            }
        }
        if (inputs.isEmpty()) showPanel("Ingangen", listOf("Geen ingangen gevonden" to { closeOverlay() }))
        else showPanel("Ingangen", inputs)
    }

    private fun hdmiInputs(): List<Pair<String, String>> {
        val tim = getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        return try {
            tim.tvInputList
                .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(this) }
                .sortedBy { it.loadLabel(this).toString() }   // port order: HDMI 1, HDMI 2, ...
                .map { info ->
                    val port = info.loadLabel(this).toString()
                    val custom = info.loadCustomLabel(this)?.toString()
                    val label = if (custom.isNullOrBlank() || custom == port) port else "$custom ($port)"
                    label to info.id
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list TV inputs", e); emptyList()
        }
    }

    private fun showAllApps() {
        val apps = installedApps().values.filter { it.pkg != packageName }.sortedBy { it.label.lowercase() }
        panelTitle.text = "Alle apps"
        panelBody.removeAllViews()
        apps.chunked(4).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            chunk.forEach { a -> line.addView(appTile(a)) }
            panelBody.addView(line)
        }
        panelScroll.layoutParams = panelScroll.layoutParams.apply { height = dp(330) }
        showOverlay()
    }

    private fun appTile(a: Installed): View {
        val radius = dp(10).toFloat()
        val frame = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = a.label
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        if (a.banner != null) {
            frame.addView(ImageView(this).apply {
                setImageDrawable(a.banner); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            frame.addView(TextView(this).apply {
                text = a.label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val ring = GradientDrawable().apply { cornerRadius = radius; setStroke(dp(2), Color.WHITE) }
        frame.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(150).start()
            v.foreground = if (has) ring else null
        }
        frame.setOnClickListener {
            val pm = packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
            closeOverlay()
            if (i != null) startSafely(i, a.label)
        }
        frame.layoutParams = LinearLayout.LayoutParams(dp(150), dp(84)).apply { marginEnd = dp(14); bottomMargin = dp(14) }
        return frame
    }

    private fun showPanel(title: String, items: List<Pair<String, () -> Unit>>) {
        panelTitle.text = title
        panelBody.removeAllViews()
        items.forEach { (label, action) ->
            panelBody.addView(TextView(this).apply {
                text = label
                textSize = 19f
                setTextColor(getColorStateList(R.color.menu_item_text))
                setBackgroundResource(R.drawable.menu_item_bg)
                setPadding(dp(20), dp(13), dp(20), dp(13))
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        panelScroll.layoutParams = panelScroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        showOverlay()
    }

    private var returnFocus: View? = null

    private fun showOverlay() {
        panelScroll.scrollTo(0, 0)
        if (overlay.visibility != View.VISIBLE) {
            returnFocus = currentFocus
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            val panel = findViewById<View>(R.id.panel)
            panel.scaleX = 0.96f; panel.scaleY = 0.96f
            panel.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
            overlay.animate().alpha(1f).setDuration(180).start()
        }
        panelBody.post { firstFocusable(panelBody)?.requestFocus() }
    }

    private fun closeOverlay() {
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().alpha(0f).setDuration(150).withEndAction { overlay.visibility = View.GONE }.start()
        (returnFocus ?: rows.firstOrNull()?.view)?.requestFocus()
    }

    private fun firstFocusable(g: ViewGroup): View? {
        for (k in 0 until g.childCount) {
            val c = g.getChildAt(k)
            if (c.isFocusable) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ intro

    /** Quiet opening: the time fades up, the apps slide in one by one. */
    private fun playIntro() {
        current = 0
        root.alpha = 1f
        left.animate().cancel()
        left.alpha = 0f; left.translationY = dp(16).toFloat()
        rows.forEach { it.view.animate().cancel(); it.view.alpha = 0f; it.view.translationX = dp(28).toFloat() }
        root.post {
            left.animate().alpha(1f).translationY(0f).setStartDelay(100).setDuration(800)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            rows.forEachIndexed { i, r ->
                r.view.animate().alpha(1f).translationX(0f).setStartDelay(300L + i * 80L).setDuration(600)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
            }
            rows.firstOrNull()?.view?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "BankLaunch"
        private val DIM = Color.parseColor("#8C8C8C")
        private const val NAME_SMALL = 22f
        private const val NAME_BIG = 30f
    }
}

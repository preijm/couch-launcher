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
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private enum class Period(val button: String, val label: String) {
        WEEK("Week", "THIS WEEK ON TV"),
        MONTH("Month", "THIS MONTH ON TV"),
        YEAR("Year", "THIS YEAR ON TV"),
    }

    /** An installed app as found on the TV. */
    private class Installed(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?)

    private class Row(
        val spec: AppSpec,
        val color: Int,
        val app: Installed?,
        val view: LinearLayout,
        val bg: GradientDrawable,
        val icon: ImageView,
        val name: TextView,
        val dot: View,
        val time: TextView,
        val share: ShareLine,
        val detail: View,
        val pct: TextView,
        val monthLabel: TextView,
        val month: TextView,
        val yearLabel: TextView,
        val year: TextView,
        val bars: WeekBars,
    )

    private val en = Locale.UK
    private val timeFmt = SimpleDateFormat("HH:mm", en)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", en)
    private val monthFmt = SimpleDateFormat("MMMM", en)

    private lateinit var root: FrameLayout
    private lateinit var left: View
    private lateinit var list: LinearLayout
    private lateinit var weekCard: View
    private lateinit var overlay: FrameLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private lateinit var panelScroll: ScrollView

    private val rows = mutableListOf<Row>()
    /** The apps in the list, as set from the TV (see [AppList]). */
    private var apps: List<AppSpec> = emptyList()
    private var addRow: View? = null
    /** The home screen always shows this week; month and year are on the cards and in the analysis. */
    private val period = Period.WEEK
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

    private lateinit var speakers: Speakers
    private lateinit var btnSpeaker: View
    private lateinit var btnSettings: View
    private lateinit var speakerLabel: TextView

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
            // The TV changes packages all the time; only rebuild for apps that are in the list.
            val pkg = i.data?.schemeSpecificPart ?: return
            if (apps.none { pkg in it.packages }) return
            // Updates and component changes also land here; only rebuild when an app came or went.
            if (!demo && rows.isNotEmpty() && !installedChanged()) return
            dirty = true
            if (hasWindowFocus()) rebuildList()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        left = findViewById(R.id.left)
        list = findViewById(R.id.list)
        weekCard = findViewById(R.id.weekCard)
        overlay = findViewById(R.id.overlay)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        panelScroll = findViewById(R.id.panelScroll)
        usage = UsageRepo(this)
        list.layoutTransition = smoothTransition()

        btnSettings = findViewById<ImageButton>(R.id.btnSettings)
        val btnInputs = findViewById<ImageButton>(R.id.btnInputs)
        val btnApps = findViewById<ImageButton>(R.id.btnApps)
        listOf(btnSettings, btnInputs, btnApps).forEach { b ->
            b.isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 26) b.defaultFocusHighlightEnabled = false
            b.setOnKeyListener(leftButtonKeys)
        }
        btnSettings.setOnClickListener { startSafely(Intent(Settings.ACTION_SETTINGS), "Settings") }
        btnInputs.setOnClickListener { showInputs() }
        btnApps.setOnClickListener { showAllApps() }

        btnSpeaker = findViewById(R.id.btnSpeaker)
        speakerLabel = findViewById(R.id.speakerLabel)
        btnSpeaker.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) btnSpeaker.defaultFocusHighlightEnabled = false
        btnSpeaker.setOnKeyListener(leftButtonKeys)
        btnSpeaker.setOnClickListener { switchSpeaker() }
        speakers = Speakers(this) { main.post { updateSpeaker() } }
        if (!demo) speakers.start()
        updateSpeaker()

        weekCard.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) weekCard.defaultFocusHighlightEnabled = false
        weekCard.setOnKeyListener(weekCardKeys)
        weekCard.setOnFocusChangeListener { _, has -> findViewById<View>(R.id.weekHint).visibility = if (has) View.VISIBLE else View.GONE }
        weekCard.setOnClickListener { showAnalysis() }
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
        // adb shell am start -n nl.reijm.banklaunchtv/.MainActivity --ez topApps true; adb logcat -s BankLaunch
        // adb shell am start -n nl.reijm.banklaunchtv/.MainActivity --ez analysis true
        if (intent?.getBooleanExtra("analysis", false) == true) root.post { showAnalysis() }
        if (intent?.getBooleanExtra("buckets", false) == true) io.execute { usage.rawBuckets().forEach { Log.i(TAG, it) } }
        // adb shell am start -n nl.reijm.banklaunchtv/.MainActivity --ez weekApps true; adb logcat -s BankLaunch
        if (intent?.getBooleanExtra("weekApps", false) == true) io.execute {
            usage.history(apps).weeks.forEach { w ->
                Log.i(TAG, "week ${Date(w.start)}: " + w.apps.orEmpty().entries.sortedByDescending { it.value }
                    .filter { it.value >= 600_000L }.joinToString(" ") { "${it.key}=${UsageRepo.format(it.value)}" })
            }
        }
        if (intent?.getBooleanExtra("topApps", false) == true) io.execute {
            usage.topApps().take(25).forEach { (pkg, ms) -> Log.i(TAG, "top ${UsageRepo.format(ms)}  $pkg") }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        updateClock()
        loadStats()
        updateSpeaker()
        if (launching) { launching = false; root.alpha = 1f; focusRow(current) }
        if (dirty) rebuildList(force = true)
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
        speakers.stop()
        io.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while we're in front: close any panel and go back to the top of the list. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        closeAnalysis()
        closeOverlay()
        focusRow(0)
        if (intent.getBooleanExtra("analysis", false)) root.post { showAnalysis() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (analysis != null && analysisKey(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (overlay.visibility == View.VISIBLE) closeOverlay()
                else focusRow(0)
            }
            return true // a home screen has nowhere to go back to
        }
        return super.dispatchKeyEvent(event)
    }

    /** From anything on the left, RIGHT goes back to the selected app. */
    private val toListOnRight = View.OnKeyListener { v, code, ev ->
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == KeyEvent.ACTION_DOWN &&
            v.focusSearch(View.FOCUS_RIGHT)?.let { it.parent == v.parent } != true
        ) {
            rows.getOrNull(current)?.view?.requestFocus(); true
        } else false
    }

    /** Round buttons: UP goes to the week card. */
    private val leftButtonKeys = View.OnKeyListener { v, code, ev ->
        if (code == KeyEvent.KEYCODE_DPAD_UP && ev.action == KeyEvent.ACTION_DOWN) {
            weekCard.requestFocus(); true
        } else toListOnRight.onKey(v, code, ev)
    }

    /** Week card: DOWN goes to the round buttons, UP stays (nothing above it to select). */
    private val weekCardKeys = View.OnKeyListener { v, code, ev ->
        when {
            ev.action != KeyEvent.ACTION_DOWN -> false
            code == KeyEvent.KEYCODE_DPAD_DOWN -> { btnSettings.requestFocus(); true }
            code == KeyEvent.KEYCODE_DPAD_UP -> true
            else -> toListOnRight.onKey(v, code, ev)
        }
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    // ------------------------------------------------------------------ speaker

    /** Show which speaker plays; hidden when there's no Bluetooth speaker to switch to. */
    private fun updateSpeaker() {
        if (demo) { btnSpeaker.visibility = View.VISIBLE; speakerLabel.text = "Sonos"; return }
        btnSpeaker.visibility = if (speakers.available) View.VISIBLE else View.GONE
        val text = when {
            !speakers.permitted -> "Speaker"
            else -> when (speakers.state) {
                Speakers.State.SPEAKER -> speakers.speakerName
                Speakers.State.TV -> "TV"
                Speakers.State.SWITCHING -> "Switching…"
            }
        }
        speakerLabel.text = text
        btnSpeaker.contentDescription = "Sound plays on $text. Press to switch."
    }

    private fun switchSpeaker() {
        if (!speakers.permitted) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        speakers.toggle()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        updateSpeaker()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_BT) { speakers.connectProxy(); updateSpeaker() }
    }

    private fun isScreenOn() = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive

    // ------------------------------------------------------------------ clock & period

    private fun updateClock() {
        val now = Date()
        findViewById<TextView>(R.id.clock).text = timeFmt.format(now)
        findViewById<TextView>(R.id.date).text = dateFmt.format(now)
    }

    // ------------------------------------------------------------------ stats

    private fun loadStats() {
        if (demo) { showStats(demoStats(), true); return }
        val specs = apps
        io.execute {
            val ok = usage.hasPermission()
            val s = if (ok) runCatching { usage.load(specs) }
                .onFailure { Log.w(TAG, "Could not read usage", it) }.getOrDefault(emptyMap()) else emptyMap()
            main.post { if (!isDestroyed) showStats(s, ok) }
            // Also get the analysis history ready, so its charts are there the moment it opens.
            val h = if (ok) runCatching { usage.history(specs) }.onFailure { Log.w(TAG, "No history", it) }.getOrNull() else null
            if (h != null) main.post { if (!isDestroyed) onHistory(h) }
        }
    }

    private fun valueFor(st: AppStats?): Long = when (period) {
        Period.WEEK -> st?.week
        Period.MONTH -> st?.month
        Period.YEAR -> st?.year
    } ?: 0L

    private fun showStats(s: Map<String, AppStats>, ok: Boolean) {
        stats = s
        hasUsage = ok
        val values = apps.map { valueFor(s[it.name]) }
        val total = values.sum()
        findViewById<TextView>(R.id.totalLabel).text = period.label
        findViewById<TextView>(R.id.total).text = if (ok) UsageRepo.format(total) else "–"
        findViewById<SegmentBar>(R.id.segments).setValues(
            if (ok) apps.mapIndexed { i, a -> values[i] to Color.parseColor(a.color) } else emptyList(),
        )
        findViewById<TextView>(R.id.perDay).text =
            if (ok) weekSentence() else "Watch time not available yet"
        rows.forEach { fillRow(it) }
        if (analysis != null) fillAnalysisStats()
    }

    /**
     * "≈ 2h 00m per day · 11% more than last week". Compares this week so far with last week up to
     * the same day and time (Wednesday evening against last Wednesday evening); within 5% "same as last week".
     */
    private fun weekSentence(): CharSequence {
        val week = apps.sumOf { stats[it.name]?.week ?: 0L }
        val head = "≈ ${UsageRepo.format(week / daysInPeriod())} per day"
        val last = apps.map { stats[it.name]?.lastWeekSoFar ?: -1L }
        val pct = if (last.isNotEmpty() && last.none { it < 0 }) {
            if (last.sum() < 20 * 60_000L) return head
            val now = apps.sumOf { a -> stats[a.name]?.weekDays?.filter { it > 0 }?.sum() ?: 0L }
            Math.round((now - last.sum()) * 100.0 / last.sum()).toInt()
        } else {
            // Last week's days are no longer on the TV: compare with its average per day instead.
            val before = history?.weeks?.let { it.getOrNull(it.size - 2) }?.perDay ?: return head
            if (before < 5 * 60_000L) return head
            Math.round((week / daysInPeriod() - before) * 100.0 / before).toInt()
        }
        val cmp = when {
            Math.abs(pct) < 5 -> "same as last week"
            pct < 0 -> "${-pct}% less than last week"
            else -> "$pct% more than last week"
        }
        return "$head · $cmp"
    }

    /** Days so far in the selected period, today included. */
    private fun daysInPeriod(): Int {
        val c = Calendar.getInstance()
        return when (period) {
            Period.WEEK -> (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            Period.MONTH -> c.get(Calendar.DAY_OF_MONTH)
            Period.YEAR -> c.get(Calendar.DAY_OF_YEAR)
        }
    }

    private fun fillRow(r: Row) {
        val st = stats[r.spec.name]
        val v = valueFor(st)
        val total = apps.sumOf { valueFor(stats[it.name]) }
        val max = apps.maxOf { valueFor(stats[it.name]) }.coerceAtLeast(1L)
        val now = Date()
        r.time.text = if (r.app == null && !demo) "not on this TV" else if (hasUsage) UsageRepo.format(v) else ""
        r.share.setValue(if (hasUsage) v.toFloat() / max else 0f, r.color)
        r.pct.text = if (hasUsage && total > 0) "${(v * 100 / total)}%" else "–"
        r.monthLabel.text = monthFmt.format(now).uppercase(en)
        r.month.text = st?.let { UsageRepo.format(it.month) } ?: "–"
        r.yearLabel.text = Calendar.getInstance().get(Calendar.YEAR).toString()
        r.year.text = st?.let { UsageRepo.format(it.year) } ?: "–"
        if (rows.indexOf(r) == current) r.bars.setValues(st?.weekDays ?: LongArray(7) { -1L }, r.color)
    }

    private fun demoStats(): Map<String, AppStats> {
        val m = 60_000L
        fun s(w: Long, mo: Long, y: Long, vararg d: Long) =
            AppStats(w * m, mo * m, y * m, (d.map { it * m } + listOf(-1L)).toLongArray())
        return mapOf(
            "YouTube" to s(370, 440, 8040, 45, 68, 31, 91, 105, 128),
            "Netflix" to s(205, 260, 5400, 40, 0, 65, 30, 0, 70),
            "NOS" to s(65, 80, 2100, 10, 12, 8, 10, 15, 10),
            "NPO Start" to s(50, 60, 900, 0, 20, 0, 0, 30, 0),
            "Prime Video" to s(70, 85, 1300, 0, 0, 0, 70, 0, 0),
        )
    }

    // ------------------------------------------------------------------ the list

    private fun installedApps(): Map<String, Installed> {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(q, 0).associate { ri ->
            val pkg = ri.activityInfo.packageName
            pkg to Installed(pkg, ri.loadLabel(pm).toString(), ri.activityInfo.loadBanner(pm), ri.loadIcon(pm))
        }
    }

    /** Whether any app in the list was installed or removed since the rows were built. */
    private fun installedChanged(): Boolean {
        val installed = installedApps()
        return rows.any { r -> r.spec.packages.firstOrNull { it in installed } != r.app?.pkg }
    }

    private fun buildList() {
        dirty = false
        apps = AppList.load(this)
        val installed = installedApps()
        list.removeAllViews()
        rows.clear()
        apps.forEachIndexed { i, spec ->
            val app = if (demo) null else spec.packages.firstNotNullOfOrNull { installed[it] }
            val r = rowView(spec, app)
            rows += r
            list.addView(r.view)
            r.view.setOnFocusChangeListener { _, has -> if (has) current = i; setExpanded(r, has) }
            r.view.setOnClickListener { open(r) }
            r.view.setOnLongClickListener { showAppMenu(i); true }
            r.view.setOnKeyListener { _, code, ev ->
                when {
                    ev.action != KeyEvent.ACTION_DOWN -> false
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> { weekCard.requestFocus(); true }
                    // Last app: down goes to "Add app" (or nowhere), never across to the left side.
                    code == KeyEvent.KEYCODE_DPAD_DOWN && i == apps.size - 1 -> { focusAddRow(); true }
                    else -> false
                }
            }
            if (i == 0 && Build.VERSION.SDK_INT >= 26) r.view.isFocusedByDefault = true
            fillRow(r)
        }
        addRow = if (apps.size < AppList.MAX && !demo) addAppRow().also { list.addView(it) } else null
        current = current.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }

    /** Quiet "+ Add app" line under the list; lights up only when you're on it. */
    private fun addAppRow(): View {
        val v = TextView(this).apply {
            text = "+  Add app"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ADD_DIM)
            letterSpacing = 0.04f
            setPadding(dp(22), dp(8), dp(22), dp(8))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setOnFocusChangeListener { _, has -> setTextColor(if (has) Color.WHITE else ADD_DIM) }
            setOnClickListener { showAllApps(pick = true) }
            setOnKeyListener { _, code, ev ->
                when {
                    ev.action != KeyEvent.ACTION_DOWN -> false
                    code == KeyEvent.KEYCODE_DPAD_LEFT -> { btnSettings.requestFocus(); true }
                    code == KeyEvent.KEYCODE_DPAD_UP -> { focusRow(rows.size - 1); true }
                    code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
        return v
    }

    /**
     * Go to "Add app" without the list's layout animation: while the card above it collapses,
     * that animation briefly gives the line a negative height, and Android then drops focus.
     */
    private fun focusAddRow() {
        val row = addRow ?: return
        val lt = list.layoutTransition
        list.layoutTransition = null
        row.requestFocus()
        list.post { list.post { list.layoutTransition = lt } }
    }

    /** Hold OK on an app: move it, or take it off the list. */
    private fun showAppMenu(i: Int) {
        val spec = apps.getOrNull(i) ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (i > 0) items += "Move up" to { changeList(i - 1) { it.add(i - 1, it.removeAt(i)) } }
        if (i < apps.size - 1) items += "Move down" to { changeList(i + 1) { it.add(i + 1, it.removeAt(i)) } }
        items += "Remove from list" to { changeList(maxOf(0, i - 1)) { it.removeAt(i) } }
        showPanel(spec.name, items)
    }

    private fun addApp(a: Installed) {
        val spec = AppList.newSpec(a.label, a.pkg, a.icon, apps)
        changeList(apps.size) { it.add(spec) }
    }

    /** Change the saved list, rebuild the rows and put the selection on [focus]. */
    private fun changeList(focus: Int, edit: (MutableList<AppSpec>) -> Unit) {
        val l = apps.toMutableList()
        edit(l)
        AppList.save(this, l)
        returnFocus = null
        closeOverlay()
        buildList()
        loadStats()
        current = focus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        focusRow(current)
    }

    /** Select app [i] once the list has laid out (right after a rebuild, focus requests can get lost). */
    private fun focusRow(i: Int) {
        list.post { rows.getOrNull(i.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))?.view?.requestFocus() }
    }

    /** Rebuild the rows; if an app was selected, select it again instead of letting focus jump left. */
    private fun rebuildList(force: Boolean = false) {
        val onRow = rows.any { it.view.hasFocus() }
        buildList()
        loadStats()
        if (onRow || force) focusRow(current)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF9A9A9A.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        letterSpacing = 0.12f
    }

    private fun rowView(spec: AppSpec, app: Installed?): Row {
        val color = Color.parseColor(spec.color)
        val bg = GradientDrawable().apply { setColor(0xFF141414.toInt()); cornerRadius = dp(14).toFloat(); alpha = 0 }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(22), dp(9), dp(22), dp(9))
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = spec.name
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            layoutTransition = smoothTransition()
        }

        // Header: icon, name, (share line | dot), time
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val icon = ImageView(this).apply {
            if (spec.icon != 0) setImageResource(spec.icon) else setImageDrawable(LetterIcon(spec.name.take(1).uppercase()))
            imageTintList = ColorStateList.valueOf(DIM)
        }
        val name = TextView(this).apply {
            text = spec.name
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SMALL)
            letterSpacing = -0.02f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val share = ShareLine(this)
        val dot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            visibility = View.GONE
        }
        val time = TextView(this).apply {
            setTextColor(DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.END
            minWidth = dp(96) // "43h 35m" and "362h" take the same space, so the bars line up
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        header.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        header.addView(share, LinearLayout.LayoutParams(dp(100), dp(3)).apply { marginEnd = dp(14) })
        header.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
        header.addView(time, LinearLayout.LayoutParams(-2, -2).apply { })
        view.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Detail (selected only): share %, this month, this year, this week per day
        val detail = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; visibility = View.GONE }
        fun stat(lbl: String): Pair<TextView, TextView> {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val l = label(lbl)
            val v = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) }
            col.addView(l)
            col.addView(v, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            detail.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(20) })
            return l to v
        }
        val (_, pct) = stat("SHARE")
        val (monthLabel, month) = stat("MONTH")
        val (yearLabel, year) = stat("YEAR")
        val bars = WeekBars(this)
        detail.addView(bars, LinearLayout.LayoutParams(0, dp(56), 1.8f))
        view.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(4) })

        view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        return Row(spec, color, app, view, bg, icon, name, dot, time, share, detail, pct, monthLabel, month, yearLabel, year, bars)
    }

    /** The selected app opens into a card; the rest stay a quiet list. */
    private fun setExpanded(r: Row, on: Boolean) {
        r.detail.visibility = if (on) View.VISIBLE else View.GONE
        r.share.visibility = if (on) View.GONE else View.VISIBLE
        r.dot.visibility = if (on) View.VISIBLE else View.GONE
        r.view.setPadding(dp(22), dp(if (on) 18 else 9), dp(22), dp(if (on) 18 else 9))
        ValueAnimator.ofInt(r.bg.alpha, if (on) 255 else 0).apply {
            duration = ANIM
            addUpdateListener { r.bg.alpha = it.animatedValue as Int }
            start()
        }
        // Sizes jump to their final value at once, so the card animates to its final height in
        // one movement. The grow/shrink is then shown with scale, which doesn't move the layout.
        val s0 = r.name.textSize
        r.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (on) NAME_BIG else NAME_SMALL)
        val t0 = r.time.textSize
        r.time.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (on) 20f else 16f)
        r.time.minWidth = if (on) 0 else dp(96) // fixed width only matters for lining up the bars
        val i0 = r.icon.layoutParams.width.toFloat()
        (r.icon.layoutParams as LinearLayout.LayoutParams).apply { width = dp(if (on) 26 else 20); height = width }
        r.icon.requestLayout()
        fun grow(v: View, from: Float, pivotEnd: Boolean = false) {
            v.pivotX = if (pivotEnd) v.width.toFloat() else 0f
            v.pivotY = v.height / 2f
            v.animate().cancel()
            v.scaleX = from; v.scaleY = from
            v.animate().scaleX(1f).scaleY(1f).setDuration(ANIM).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
        grow(r.name, s0 / r.name.textSize)
        grow(r.time, t0 / r.time.textSize, pivotEnd = true)
        grow(r.icon, i0 / r.icon.layoutParams.width)
        r.name.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        r.name.letterSpacing = if (on) -0.03f else -0.02f

        val c0 = r.name.currentTextColor
        val c1 = if (on) Color.WHITE else DIM
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val c = ArgbEvaluator().evaluate(it.animatedValue as Float, c0, c1) as Int
                r.name.setTextColor(c)
                r.time.setTextColor(c)
                r.icon.imageTintList = ColorStateList.valueOf(c)
            }
            start()
        }
        if (on) r.bars.setValues(stats[r.spec.name]?.weekDays ?: LongArray(7) { -1L }, r.color, replay = true)
    }

    // ------------------------------------------------------------------ opening apps

    private fun open(r: Row) {
        val app = r.app
        if (app == null) {
            if (!demo) Toast.makeText(this, "${r.spec.name} isn't installed on this TV", Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Toast.makeText(this, "Couldn't open ${r.spec.name}", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, "Couldn't open $label", Toast.LENGTH_LONG).show()
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
        if (inputs.isEmpty()) showPanel("Inputs", listOf("No inputs found" to { closeOverlay() }))
        else showPanel("Inputs", inputs)
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

    /** All apps; with [pick], only those not in the list yet, and choosing one adds it. */
    private fun showAllApps(pick: Boolean = false) {
        val inList = apps.flatMap { it.packages }.toSet()
        val shown = installedApps().values
            .filter { it.pkg != packageName && !(pick && it.pkg in inList) }
            .sortedBy { it.label.lowercase() }
        panelTitle.text = if (pick) "Add an app" else "All apps"
        panelBody.removeAllViews()
        shown.chunked(4).forEach { chunk ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            chunk.forEach { a -> line.addView(appTile(a, if (pick) ({ addApp(a) }) else null)) }
            panelBody.addView(line)
        }
        panelScroll.layoutParams = panelScroll.layoutParams.apply { height = dp(330) }
        showOverlay()
    }

    private fun appTile(a: Installed, onPick: (() -> Unit)? = null): View {
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
            if (onPick != null) { onPick(); return@setOnClickListener }
            val pm = packageManager
            val i = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
            closeOverlay()
            if (i != null) startSafely(i, a.label)
        }
        frame.layoutParams = LinearLayout.LayoutParams(dp(150), dp(84)).apply { marginEnd = dp(14); bottomMargin = dp(14) }
        return frame
    }

    // ------------------------------------------------------------------ analysis

    /**
     * Full-screen "your TV time": the week line, totals, per-day trends for weeks and months, and
     * per app for the chosen bar. Left/right picks a bar, up/down switches weeks/months, Back closes.
     * Only periods the TV has complete records for are shown, so every number is measured.
     */
    private var analysis: FrameLayout? = null
    private var history: History? = null
    private var weekBars: List<PeriodAverage> = emptyList()
    private var monthBars: List<PeriodAverage> = emptyList()
    private var anChart = 0 // 0 = weeks, 1 = months
    private var anWeek = 0
    private var anMonth = 0

    private fun showAnalysis() {
        if (analysis != null) return
        val a = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            alpha = 0f
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        a.addView(col, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(64), dp(36), dp(64), dp(24)) })
        root.addView(a, FrameLayout.LayoutParams(-1, -1))
        analysis = a

        // Header: title and the week line on the left, the totals small in the top right.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Your TV time", 30f, Color.WHITE, "sans-serif-light"))
            addView(text("", 15f, GREY).apply { id = R.id.anSentence }, lp(top = 8))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; id = R.id.anTotals })
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        // Weeks / Months: up and down switch between them.
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        tabs.addView(text("Weeks", 17f, Color.WHITE).apply { id = R.id.anWeeksTitle })
        tabs.addView(text("Months", 17f, Color.WHITE).apply { id = R.id.anMonthsTitle },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(24) })
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(30) })
        col.addView(StackedBars(this).apply { id = R.id.anWeeks },
            LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(8) })

        // The chosen bar, split by app, with what changed since the one before.
        col.addView(heading("").apply { id = R.id.anAppsTitle }, lp(top = 20))
        col.addView(SegmentBar(this).apply { id = R.id.anSplit },
            LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10) })
        col.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = R.id.anApps },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        col.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(text("◀ ▶  choose a bar     ▲ ▼  weeks or months", 11f, 0xFF6A6A6A.toInt()))

        anChart = 0
        anWeek = Int.MAX_VALUE
        anMonth = Int.MAX_VALUE
        fillAnalysisStats()
        fillHistory()
        a.animate().alpha(1f).setDuration(220).start()
        a.requestFocus()
        if (history == null) loadHistory()
    }

    private fun closeAnalysis() {
        val a = analysis ?: return
        analysis = null
        a.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(a) }.start()
        weekCard.requestFocus()
    }

    /** Remote keys while the analysis is open. */
    private fun analysisKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (!down) closeAnalysis() }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveBar(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveBar(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (down && anChart != 0) { anChart = 0; showChart(); showSelection() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && anChart != 1) { anChart = 1; showChart(); showSelection() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {}
            else -> return false
        }
        return true
    }

    private fun moveBar(step: Int) {
        if (anChart == 0) anWeek = (anWeek + step).coerceIn(0, (weekBars.size - 1).coerceAtLeast(0))
        else anMonth = (anMonth + step).coerceIn(0, (monthBars.size - 1).coerceAtLeast(0))
        showSelection()
    }

    private fun loadHistory() {
        if (demo) { history = demoHistory(); fillHistory(); return }
        val specs = apps
        io.execute {
            val h = runCatching { usage.history(specs) }.onFailure { Log.w(TAG, "No history", it) }.getOrNull()
            main.post { if (h != null) onHistory(h) }
        }
    }

    /** New history for the analysis page. */
    private fun onHistory(h: History) {
        history = h
        if (hasUsage) findViewById<TextView>(R.id.perDay).text =
            weekSentence()
        fillAnalysisStats()
        fillHistory()
    }

    /** The months the TV has complete records for, up to now. */
    private fun measuredMonths(): List<PeriodAverage> =
        history?.months?.dropWhile { it.apps == null }.orEmpty()

    /** The parts that come straight from the home screen's numbers, plus the measured total. */
    private fun fillAnalysisStats() {
        val a = analysis ?: return
        val week = apps.sumOf { stats[it.name]?.week ?: 0L }
        val month = apps.sumOf { stats[it.name]?.month ?: 0L }
        val c = Calendar.getInstance()
        a.findViewById<TextView>(R.id.anSentence).text = weekSentence()

        val totals = a.findViewById<LinearLayout>(R.id.anTotals)
        totals.removeAllViews()
        val blocks = mutableListOf(
            Triple("THIS WEEK", week, (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1),
            Triple(monthFmt.format(c.time).uppercase(en), month, c.get(Calendar.DAY_OF_MONTH)),
        )
        val measured = measuredMonths()
        if (measured.size > 1) blocks += Triple(
            "SINCE " + monthFmt.format(Date(measured.first().start)).uppercase(en),
            measured.sumOf { it.total }, measured.sumOf { it.days },
        )
        blocks.forEach { (title, total, days) ->
            totals.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(heading(title))
                addView(text(UsageRepo.format(total), 24f, Color.WHITE, "sans-serif-light"), lp(top = 6))
                addView(text("≈ ${UsageRepo.format(total / days.coerceAtLeast(1))} a day", 12f, GREY), lp(top = 4))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(40) })
        }
    }

    private fun fillHistory() {
        val a = analysis ?: return
        val h = history ?: return
        // A selection on the newest bar stays on the newest bar when the history is refreshed.
        if (anWeek >= weekBars.size - 1) anWeek = Int.MAX_VALUE
        if (anMonth >= monthBars.size - 1) anMonth = Int.MAX_VALUE
        weekBars = h.weeks.dropWhile { it.apps == null && it !== h.weeks.last() }
        monthBars = measuredMonths().ifEmpty { listOf(h.months.last()) }
        if (anWeek == Int.MAX_VALUE) anWeek = weekBars.size - 1
        if (anMonth == Int.MAX_VALUE) anMonth = monthBars.size - 1
        anWeek = anWeek.coerceIn(0, weekBars.size - 1)
        anMonth = anMonth.coerceIn(0, monthBars.size - 1)

        showChart()
        showSelection()
    }

    /** Fill the chart with the weeks or the months, each bar split by app. */
    private fun showChart() {
        val a = analysis ?: return
        val bars = if (anChart == 0) weekBars else monthBars
        val day = SimpleDateFormat("d MMM", en)
        val mon = SimpleDateFormat("MMM", en)
        a.findViewById<StackedBars>(R.id.anWeeks).setData(
            bars.map { p -> if (p.apps == null) null else blocks(p).map { it.ms / p.days to it.color } },
            bars.mapIndexed { i, p ->
                when {
                    anChart == 1 -> mon.format(Date(p.start))
                    i == bars.size - 1 -> "this week"
                    else -> day.format(Date(p.start))
                }
            } + upcoming(bars),
        )
        a.findViewById<TextView>(R.id.anWeeksTitle).setTextColor(if (anChart == 0) Color.WHITE else 0xFF5A5A5A.toInt())
        a.findViewById<TextView>(R.id.anMonthsTitle).setTextColor(if (anChart == 1) Color.WHITE else 0xFF5A5A5A.toInt())
    }

    /** Labels for the weeks or months still to come, so the chart always shows 8 weeks or 12 months. */
    private fun upcoming(bars: List<PeriodAverage>): List<String> {
        val last = bars.lastOrNull() ?: return emptyList()
        val slots = if (anChart == 0) 8 else 12
        val fmt = SimpleDateFormat(if (anChart == 0) "d MMM" else "MMM", en)
        return (1..(slots - bars.size).coerceAtLeast(0)).map { k ->
            val c = Calendar.getInstance().apply { timeInMillis = last.start }
            if (anChart == 0) c.add(Calendar.DAY_OF_MONTH, 7 * k) else c.add(Calendar.MONTH, k)
            fmt.format(c.time)
        }
    }

    private class Block(val name: String, val ms: Long, val color: Int)

    /** A period per app, in list order, with everything else as one grey block at the end. */
    private fun blocks(p: PeriodAverage): List<Block> {
        val byApp = p.apps.orEmpty()
        val listed = apps.flatMap { it.packages }.toSet()
        val out = apps.map { spec -> Block(spec.name, spec.packages.sumOf { byApp[it] ?: 0L }, Color.parseColor(spec.color)) }
        // Apps outside the list: the bigger ones (half an hour or more) by name, the rest together.
        val others = byApp.filterKeys { it !in listed && it !in NOT_WATCHING && it != packageName }
            .entries.sortedByDescending { it.value }
        val named = others.filter { it.value >= 30 * 60_000L }.take(2)
            .mapIndexed { i, e -> Block(appLabel(e.key), e.value, if (i == 0) OTHER_1 else OTHER_2) }
        val rest = others.drop(named.size).sumOf { it.value }
        return out + named + Block(if (named.isEmpty()) "Other apps" else "Other", rest, OTHER)
    }

    /** Highlight the chosen bar and show that period split by app. */
    private fun showSelection() {
        val a = analysis ?: return
        val bars = if (anChart == 0) weekBars else monthBars
        val index = if (anChart == 0) anWeek else anMonth
        a.findViewById<StackedBars>(R.id.anWeeks).select(index)

        val p = (if (anChart == 0) weekBars.getOrNull(anWeek) else monthBars.getOrNull(anMonth)) ?: return
        val last = if (anChart == 0) anWeek == weekBars.size - 1 else anMonth == monthBars.size - 1
        val title = when {
            anChart == 0 && last -> "THIS WEEK"
            anChart == 0 -> "WEEK OF " + SimpleDateFormat("d MMMM", en).format(Date(p.start)).uppercase(en)
            last -> monthFmt.format(Date(p.start)).uppercase(en) + " SO FAR"
            else -> SimpleDateFormat("MMMM yyyy", en).format(Date(p.start)).uppercase(en)
        }
        val now = blocks(p)
        val perDay = now.sumOf { it.ms } / p.days.coerceAtLeast(1)
        a.findViewById<TextView>(R.id.anAppsTitle).text = "$title     ${UsageRepo.format(perDay)} A DAY"
        a.findViewById<SegmentBar>(R.id.anSplit).setValues(now.map { it.ms to it.color })

        // Legend: per app the time per day and the change since the bar before.
        val before = bars.getOrNull(index - 1)?.takeIf { it.apps != null }?.let { b -> blocks(b).associate { it.name to it.ms / b.days } }
        val list = a.findViewById<LinearLayout>(R.id.anApps)
        list.removeAllViews()
        val shown = now.filter { it.ms > 0 || (before?.get(it.name) ?: 0L) > 0 }
        shown.chunked(3).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { b ->
                val ms = b.ms / p.days.coerceAtLeast(1)
                val item = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                item.addView(View(this).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(b.color) }
                }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
                item.addView(text(b.name, 14f, Color.WHITE).apply {
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
                item.addView(text(WeekBarsText.short(ms), 14f, Color.WHITE, "sans-serif-medium"),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                before?.let { prev ->
                    val diff = ms - (prev[b.name] ?: 0L)
                    val t = when {
                        Math.abs(diff) < 60_000L -> "same"
                        diff > 0 -> "+" + WeekBarsText.short(diff)
                        else -> "−" + WeekBarsText.short(-diff)
                    }
                    item.addView(text(t, 13f, GREY), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                }
                line.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
            }
            repeat(3 - row.size) { line.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    /** An app's name as the TV shows it, for packages outside the list. */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun demoHistory(): History {
        val h = 3_600_000L
        val now = System.currentTimeMillis()
        val pk = apps.map { it.packages.first() }
        val mix = listOf(0.45, 0.3, 0.12, 0.08, 0.05)
        fun p(start: Long, days: Int, perDay: Double?) = PeriodAverage(
            start, start + days * 24 * h,
            perDay?.let { v -> pk.mapIndexed { i, k -> k to (v * days * h * mix.getOrElse(i) { 0.0 }).toLong() }.toMap() + ("com.spotify.tv.android" to 2 * h) },
            days, pk.toSet(),
        )
        val weeks = listOf(null, null, null, 3.4, 2.9, 3.5, 2.7, 2.0).mapIndexed { i, v -> p(now - (7 - i) * 7 * 24 * h, 7, v) }
        val months = listOf(null, null, null, null, null, null, 2.9, 3.1, 3.8, 3.3, 2.6, 2.4)
            .mapIndexed { i, v -> p(now - (11 - i) * 30L * 24 * h, 30, v) }
        return History(weeks, months)
    }

    private fun text(t: String, sp: Float, color: Int, family: String = "sans-serif") = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = Typeface.create(family, Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun heading(t: String) = text(t, 11f, GREY, "sans-serif-medium").apply { letterSpacing = 0.14f }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(top) }

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
                if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
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
        val back = returnFocus
        if (back != null && back.isAttachedToWindow) back.requestFocus() else focusRow(current)
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

    /**
     * Android's default layout animation waits before resizing and before fading new parts in,
     * which made the card grow in two steps. Everything here starts at once, with one duration.
     */
    private fun smoothTransition() = LayoutTransition().apply {
        enableTransitionType(LayoutTransition.CHANGING)
        listOf(
            LayoutTransition.APPEARING, LayoutTransition.DISAPPEARING,
            LayoutTransition.CHANGE_APPEARING, LayoutTransition.CHANGE_DISAPPEARING, LayoutTransition.CHANGING,
        ).forEach {
            setStartDelay(it, 0)
            setDuration(it, ANIM)
            setInterpolator(it, DecelerateInterpolator(1.6f))
        }
    }

    companion object {
        private const val TAG = "BankLaunch"
        private const val REQ_BT = 7
        /** Screensaver, home screens and settings: on screen, but not watching. */
        private val NOT_WATCHING = setOf(
            "com.google.android.apps.tv.dreamx", "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith", "com.android.tv.settings", "com.sony.dtv.settings",
        )
        private val DIM = Color.parseColor("#8A8A8A")
        private val GREY = Color.parseColor("#9A9A9A")
        private val OTHER = Color.parseColor("#9B7BFF")
        private val OTHER_1 = Color.parseColor("#2EC4B6")
        private val OTHER_2 = Color.parseColor("#E9C46A")
        private val ADD_DIM = Color.parseColor("#4A4A4A")
        private const val NAME_SMALL = 28f
        private const val NAME_BIG = 40f
        /** One duration for everything that moves when the selection changes. */
        private const val ANIM = 260L
    }
}

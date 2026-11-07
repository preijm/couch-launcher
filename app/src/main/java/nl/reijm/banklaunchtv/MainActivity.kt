package nl.reijm.banklaunchtv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    /** An installed app as found on the TV. */
    private class Installed(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable?)

    private class Tile(val spec: AppSpec, val app: Installed?, val view: View)

    private val nl = Locale("nl", "NL")
    private val timeFmt = SimpleDateFormat("HH:mm", nl)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", nl)

    private lateinit var root: FrameLayout
    private lateinit var glow: GlowView
    private lateinit var row: LinearLayout
    private lateinit var appName: TextView
    private lateinit var gear: ImageButton
    private lateinit var overlay: FrameLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private lateinit var panelScroll: ScrollView

    private val tiles = mutableListOf<Tile>()
    private var current = 0            // index of the selected tile
    private var introDone = false
    private var launching = false
    private var dirty = false

    /** Preview mode (am start ... --ez demo true): brand-colour tiles for screenshots off-TV. */
    private val demo get() = intent?.getBooleanExtra("demo", false) == true

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            if (hasWindowFocus()) { buildRow(); revealRowNow() }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        glow = findViewById(R.id.glow)
        row = findViewById(R.id.row)
        appName = findViewById(R.id.appName)
        gear = findViewById(R.id.gear)
        overlay = findViewById(R.id.overlay)
        panelTitle = findViewById(R.id.panelTitle)
        panelBody = findViewById(R.id.panelBody)
        panelScroll = findViewById(R.id.panelScroll)

        gear.isFocusableInTouchMode = true
        gear.setOnFocusChangeListener { _, has -> if (has) onGearFocused() }
        gear.setOnClickListener { openMenu() }
        gear.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.action == KeyEvent.ACTION_DOWN) {
                tiles.getOrNull(current)?.view?.requestFocus(); true
            } else false
        }

        buildRow()
        updateClock()
        register(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK).apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
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
        updateClock()
        glow.resumeBreathing()
        if (launching) restoreAfterLaunch()
        if (dirty) { buildRow(); revealRowNow() }
    }

    override fun onStop() {
        glow.pauseBreathing()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(tickReceiver)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    /** Home pressed while we're in front: close any panel and go back to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeOverlay()
        if (introDone) tiles.firstOrNull()?.view?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (overlay.visibility == View.VISIBLE) closeOverlay()
                else tiles.firstOrNull()?.view?.requestFocus()
            }
            return true // a home screen has nowhere to go back to
        }
        return super.dispatchKeyEvent(event)
    }

    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    // ------------------------------------------------------------------ clock

    private fun updateClock() {
        val now = Date()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        findViewById<TextView>(R.id.greeting).text = when (hour) {
            in 6..11 -> "Goedemorgen"
            in 12..17 -> "Goedemiddag"
            in 18..23 -> "Goedenavond"
            else -> "Goedenacht"
        }
        findViewById<TextView>(R.id.clock).text = timeFmt.format(now)
        findViewById<TextView>(R.id.date).text = dateFmt.format(now)
    }

    // ------------------------------------------------------------------ the row

    private fun installedApps(): Map<String, Installed> {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(q, 0).associate { ri ->
            val pkg = ri.activityInfo.packageName
            pkg to Installed(pkg, ri.loadLabel(pm).toString(), ri.activityInfo.loadBanner(pm), ri.loadIcon(pm))
        }
    }

    private fun find(spec: AppSpec, apps: Map<String, Installed>): Installed? {
        if (demo) return null
        spec.packages.firstNotNullOfOrNull { apps[it] }?.let { return it }
        // Not a TV app under a known name: try the label, then a plain (non-TV) launcher entry.
        apps.values.firstOrNull { a -> spec.labels.any { a.label.equals(it, ignoreCase = true) } }?.let { return it }
        val pm = packageManager
        return spec.packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { pkg ->
            val info = pm.getApplicationInfo(pkg, 0)
            Installed(pkg, info.loadLabel(pm).toString(), info.loadBanner(pm), info.loadIcon(pm))
        }
    }

    private fun buildRow() {
        dirty = false
        val apps = installedApps()
        row.removeAllViews()
        tiles.clear()
        Config.apps.forEachIndexed { i, spec ->
            val app = find(spec, apps)
            val v = tileView(spec, app)
            tiles += Tile(spec, app, v)
            row.addView(v)
            v.setOnFocusChangeListener { _, has -> if (has) select(i) }
            v.setOnClickListener { open(i) }
            v.nextFocusUpId = R.id.gear
            if (i == 0 && Build.VERSION.SDK_INT >= 26) v.isFocusedByDefault = true
        }
        current = current.coerceIn(0, (tiles.size - 1).coerceAtLeast(0))
    }

    private fun tileView(spec: AppSpec, app: Installed?): View {
        val brand = Color.parseColor(spec.glow)
        val radius = dp(12).toFloat()
        val frame = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = spec.name
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = brand
                outlineAmbientShadowColor = brand
            }
        }

        if (app?.banner != null) {
            frame.addView(ImageView(this).apply {
                setImageDrawable(app.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            // No banner (or preview): a brand-colour card with the name as a wordmark.
            frame.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(lighten(brand), darken(brand)),
            ).apply { cornerRadius = radius }
            frame.addView(TextView(this).apply {
                text = spec.name
                setTextColor(Color.WHITE)
                textSize = 21f
                typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                letterSpacing = -0.01f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }

        frame.layoutParams = LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { marginEnd = dp(TILE_GAP) }
        return frame
    }

    /** Selection moved to tile [i]: lift it, dim the rest, move the light. */
    private fun select(i: Int) {
        current = i
        tiles.forEachIndexed { j, t ->
            val on = j == i
            t.view.animate()
                .scaleX(if (on) 1.24f else 1f).scaleY(if (on) 1.24f else 1f)
                .translationY(if (on) -dp(10).toFloat() else 0f)
                .translationZ(if (on) dp(22).toFloat() else 0f)
                .alpha(if (on) 1f else 0.5f)
                .setInterpolator(if (on) OvershootInterpolator(1.4f) else DecelerateInterpolator())
                .setStartDelay(0)
                .setDuration(if (on) 340 else 260)
                .start()
            t.view.foreground = if (on) getDrawable(R.drawable.tile_focus) else null
        }
        val t = tiles[i]
        glow.focusOn(Color.parseColor(t.spec.glow), tileCenterFraction(t.view))
        setAppName(if (t.app == null && !demo) "${t.spec.name} staat nog niet op de tv" else t.spec.name)
    }

    private fun onGearFocused() {
        tiles.forEach {
            it.view.foreground = null
            it.view.animate().scaleX(1f).scaleY(1f).translationY(0f).translationZ(0f).alpha(0.5f)
                .setStartDelay(0).setDuration(260).start()
        }
        glow.focusOn(Color.parseColor(Config.SETTINGS_GLOW), 0.92f)
        setAppName("Instellingen en meer")
    }

    private var pendingName: String? = null

    private fun setAppName(name: String) {
        if (pendingName == name) return
        pendingName = name
        // Note: cancel() also fires end actions, so the end action always reads the latest name.
        appName.animate().cancel()
        appName.animate().alpha(0f).translationX(-dp(8).toFloat()).setStartDelay(0).setDuration(110).withEndAction {
            appName.text = pendingName
            appName.translationX = dp(12).toFloat()
            appName.animate().alpha(1f).translationX(0f).setStartDelay(0).setDuration(240)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun tileCenterFraction(v: View): Float {
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val x = row.left + v.left + v.width / 2f
        return (x / w).coerceIn(0.05f, 0.95f)
    }

    // ------------------------------------------------------------------ opening apps

    private fun open(i: Int) {
        val t = tiles[i]
        val app = t.app
        if (app == null) {
            if (!demo) Toast.makeText(this, "${t.spec.name} staat nog niet op de tv", Toast.LENGTH_LONG).show()
            return
        }
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg)
        if (intent == null) {
            Toast.makeText(this, "${t.spec.name} kan niet worden geopend", Toast.LENGTH_LONG).show()
            return
        }
        // Flourish: the tile swells into the light, then the app opens.
        launching = true
        glow.fadeTo(1.35f, 260)
        t.view.animate().scaleX(1.5f).scaleY(1.5f).alpha(0f).setStartDelay(0).setDuration(240)
            .setInterpolator(DecelerateInterpolator()).withEndAction { startSafely(intent, t.spec.name) }.start()
    }

    private fun startSafely(intent: Intent, label: String) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not open $label", e)
            Toast.makeText(this, "$label kan niet worden geopend", Toast.LENGTH_LONG).show()
            if (launching) restoreAfterLaunch()
        }
    }

    /** Back from an app: put the home screen back the way it was, without the intro. */
    private fun restoreAfterLaunch() {
        launching = false
        tiles.forEach { it.view.animate().cancel(); it.view.alpha = 0.5f; it.view.scaleX = 1f; it.view.scaleY = 1f }
        glow.setIntensityNow(1f)
        tiles.getOrNull(current)?.view?.let { it.requestFocus(); select(current) }
    }

    // ------------------------------------------------------------------ settings & more

    private fun openMenu() {
        showPanel("Instellingen en meer", listOf(
            "Instellingen van de tv" to { closeOverlay(); startSafely(Intent(Settings.ACTION_SETTINGS), "Instellingen") },
            "Ingangen (HDMI)" to { showInputs() },
            "Alle apps" to { showAllApps() },
        ))
    }

    private fun showInputs() {
        val inputs: List<Pair<String, () -> Unit>> = if (demo) {
            (1..4).map { n -> "HDMI $n" to {} }
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
            chunk.forEach { a ->
                val v = tileView(AppSpec(a.label, "#3A4670", listOf(a.pkg)), a)
                (v.layoutParams as LinearLayout.LayoutParams).apply { marginEnd = dp(14); bottomMargin = dp(14) }
                v.setOnFocusChangeListener { view, has ->
                    view.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(160).start()
                    view.foreground = if (has) getDrawable(R.drawable.tile_focus) else null
                }
                v.setOnClickListener {
                    val pm = packageManager
                    val i = pm.getLeanbackLaunchIntentForPackage(a.pkg) ?: pm.getLaunchIntentForPackage(a.pkg)
                    closeOverlay()
                    if (i != null) startSafely(i, a.label)
                }
                line.addView(v)
            }
            panelBody.addView(line)
        }
        panelScroll.layoutParams = panelScroll.layoutParams.apply { height = dp(330) }
        showOverlay()
    }

    private fun showPanel(title: String, items: List<Pair<String, () -> Unit>>) {
        panelTitle.text = title
        panelBody.removeAllViews()
        items.forEach { (label, action) ->
            panelBody.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(getColorStateList(R.color.menu_item_text))
                setBackgroundResource(R.drawable.menu_item_bg)
                setPadding(dp(20), dp(14), dp(20), dp(14))
                isFocusable = true
                isClickable = true
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        panelScroll.layoutParams = panelScroll.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        showOverlay()
    }

    private fun showOverlay() {
        panelScroll.scrollTo(0, 0)
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            val panel = findViewById<View>(R.id.panel)
            panel.scaleX = 0.94f; panel.scaleY = 0.94f
            panel.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            overlay.animate().alpha(1f).setDuration(200).start()
        }
        panelBody.post { firstFocusable(panelBody)?.requestFocus() }
    }

    private fun closeOverlay() {
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().alpha(0f).setDuration(160).withEndAction { overlay.visibility = View.GONE }.start()
        gear.requestFocus()
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

    /** The one orchestrated moment: light comes up, the clock rises, the tiles arrive. */
    private fun playIntro() {
        val clock = findViewById<View>(R.id.clockBlock)
        val quiet = listOf(gear, appName, findViewById<View>(R.id.mark))
        glow.setIntensityNow(0f)
        clock.alpha = 0f; clock.translationY = dp(28).toFloat()
        quiet.forEach { it.alpha = 0f }
        tiles.forEach { it.view.alpha = 0f; it.view.translationY = dp(48).toFloat() }

        root.post {
            tiles.firstOrNull()?.let { glow.focusOn(Color.parseColor(it.spec.glow), tileCenterFraction(it.view)) }
            glow.fadeTo(1f, 1900)
            clock.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(900)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            tiles.forEachIndexed { i, t ->
                t.view.animate().alpha(0.5f).translationY(0f).setStartDelay(650L + i * 90L).setDuration(650)
                    .setInterpolator(DecelerateInterpolator(2.2f)).start()
            }
            quiet.forEach { it.animate().alpha(1f).setStartDelay(1300).setDuration(700).start() }
            root.postDelayed({
                introDone = true
                tiles.firstOrNull()?.view?.requestFocus()
            }, 650L + tiles.size * 90L + 450L)
        }
    }

    private fun revealRowNow() {
        tiles.forEach { it.view.alpha = 0.5f }
        tiles.getOrNull(current)?.view?.let { it.requestFocus(); select(current) }
    }

    // ------------------------------------------------------------------ helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun darken(c: Int) =
        Color.rgb((Color.red(c) * 0.55f).toInt(), (Color.green(c) * 0.55f).toInt(), (Color.blue(c) * 0.55f).toInt())

    private fun lighten(c: Int) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * 0.12f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * 0.12f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * 0.12f).toInt(),
    )

    companion object {
        private const val TAG = "BankLaunch"
        private const val TILE_W = 150
        private const val TILE_H = 84
        private const val TILE_GAP = 26
    }
}

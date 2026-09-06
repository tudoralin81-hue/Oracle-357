package ro.alintudor.oracle.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.widget.FrameLayout
import ro.alintudor.oracle.core.OracleMarketCalendar

/** A narrow strip of flickering "0"/"1" characters, Matrix-style — two of
 *  these flank the module header's centered title, one on each side. Self-
 *  contained: starts its own tick loop on attach, stops it on detach, so a
 *  header that gets torn down (back navigation, module switch) never leaves
 *  a stray Handler running. Deliberately subtle (low alpha, slow flicker,
 *  small monospace glyphs) — decoration around the title, not competing
 *  with it for attention. */
/** A narrow strip of falling "0"/"1" characters, Matrix-style — two of
 *  these flank the module header's centered title, one on each side. Each
 *  column has one leading character continuously scrolling downward
 *  (wrapping from the top when it passes the bottom), drawn with a
 *  fading 3-character trail behind it — genuine falling rain rather than
 *  a static grid of flickering digits, and spaced wide enough to read as
 *  a clean accent instead of visual noise. Self-contained: starts its own
 *  tick loop on attach, stops it on detach, so a header that gets torn
 *  down (back navigation, module switch) never leaves a stray Handler
 *  running. */
class OracleMatrixRainView(context: Context, private val accentColor: Int) : View(context) {
    private val paint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.MONOSPACE }
    private val maxColumns = 6
    private data class Column(var chars: MutableList<Char> = MutableList(4) { randChar() }, var headRow: Float = (Math.random() * -6).toFloat(), var speed: Float = 0.28f + (Math.random() * 0.16f).toFloat())
    private val columns = Array(maxColumns) { Column() }
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            for (col in columns) {
                col.headRow += col.speed
                if (col.headRow > 10f) {
                    col.headRow = (Math.random() * -3 - 1).toFloat()
                    col.chars = MutableList(4) { randChar() }
                }
            }
            invalidate()
            handler.postDelayed(this, 70L)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); handler.removeCallbacks(tick) }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        val targetColumnWidth = 14f * density
        val visibleColumns = (w / targetColumnWidth).toInt().coerceIn(0, maxColumns)
        if (visibleColumns == 0) return
        val rowH = h / 6f
        paint.textSize = rowH * 0.62f
        val r = Color.red(accentColor); val g = Color.green(accentColor); val b = Color.blue(accentColor)
        for (col in 0 until visibleColumns) {
            val cx = w * (col + 0.5f) / visibleColumns
            val c = columns[col]
            for (t in 0 until c.chars.size) {
                val rowPos = c.headRow - t
                if (rowPos < -1f || rowPos > 7f) continue
                val fade = (1f - t / c.chars.size.toFloat()).coerceIn(0f, 1f)
                paint.color = Color.argb((fade * 200).toInt(), r, g, b)
                canvas.drawText(c.chars[t].toString(), cx, rowH * (rowPos + 0.8f), paint)
            }
        }
    }
    companion object { private fun randChar() = if (Math.random() < 0.5) '0' else '1' }
}

/**
 * Wraps a full content rebuild (removeAllViews() + re-adding everything) so
 * Android performs exactly one layout+draw pass at the end instead of
 * potentially drawing a partial or empty frame partway through — this is
 * the "hidden refresh" flicker seen on screens that silently re-render on
 * every periodic data refresh (News, Growth, Analysis, Watchlist, Knowledge,
 * Alerts, Journal, Portfolio): removeAllViews() empties the container, then
 * each addView() for the new content is added one at a time, and if that
 * takes long enough to span more than one frame, the system draws whatever
 * partial (or fully empty, right after removeAllViews) state exists at that
 * moment — visible as a dim/blank flash before the rebuilt screen appears.
 * suppressLayout (API 29+) defers all layout of this ViewGroup and its
 * descendants until it's un-suppressed, so nothing gets measured/drawn until
 * the whole rebuild inside [block] has finished. No-op below API 29 — those
 * devices keep the previous (unfixed) behavior, nothing regresses.
 */
inline fun LinearLayout.rebuildWithoutFlicker(block: () -> Unit) {
    val canSuppress = android.os.Build.VERSION.SDK_INT >= 29
    if (canSuppress) suppressLayout(true)
    try { block() } finally { if (canSuppress) suppressLayout(false) }
}

/** Shared Oracle module shell. Header semantics are fixed: left=Back, right=Refresh. */
class OracleNativeModule(
    private val context: Context,
    private val title: String,
    private val onBack: () -> Unit = {},
    private val onRefresh: () -> Unit = {},
    private val moduleKey: String = ""
) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(1,3,8)); setPadding(dp(10),0,dp(10),0) }
    val fixedToolbar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2),0,dp(2),dp(4)) }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2),dp(10),dp(2),dp(24)) }
    val accent = when(title.uppercase()) { "ALERTS" -> Color.rgb(255,75,40); "NEWS","ANALYSIS" -> Color.rgb(25,205,255); "GROWTH" -> Color.rgb(145,245,35); "PORTFOLIO" -> Color.rgb(190,65,255); else -> Color.rgb(255,210,45) }
    private lateinit var scrollView: ScrollView
    private lateinit var swipeRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private var marketStatusView: TextView? = null
    private val marketStatusHandler = Handler(Looper.getMainLooper())
    private val marketStatusUpdater = object : Runnable {
        override fun run() {
            val view = marketStatusView ?: return
            val status = OracleMarketCalendar.status()
            view.text = "${if (status.open) "☀" else "☾"}  ${status.label}\n${status.countdown}"
            view.setTextColor(if (status.open) Color.rgb(90,245,135) else Color.rgb(255,85,95))
            view.background = rounded(Color.rgb(7,11,22), dp(9), if (status.open) Color.rgb(55,130,80) else Color.rgb(145,55,65), dp(1))
            marketStatusHandler.postDelayed(this, 30_000L)
        }
    }

    init {
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(5),dp(2),dp(5)) }
        header.addView(plainButton("‹","Back") { onBack() }, LinearLayout.LayoutParams(dp(46),dp(46)))
        val center = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        center.addView(TextView(context).apply { text="LUX OCULI";textSize=17f;typeface=Typeface.create(Typeface.SERIF,Typeface.BOLD);setTextColor(Color.WHITE);gravity=Gravity.CENTER;includeFontPadding=true })
        // Module name gets the same hand-drawn glyph this module shows as its
        // icon on the START hub, so a module screen is recognizable at a
        // glance the same way its tile is.
        val moduleTitleRow = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        if (moduleKey.isNotBlank()) moduleTitleRow.addView(OracleModuleIcon(context, moduleKey, accent), LinearLayout.LayoutParams(dp(20),dp(20)).apply{ setMargins(0,0,dp(6),0) })
        moduleTitleRow.addView(TextView(context).apply { text=title;textSize=11f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.18f;setTextColor(accent);gravity=Gravity.CENTER;includeFontPadding=true })
        // A small connection-status dot for the modules that actually talk to
        // the server — separate from the global "SERVER ON/OFF" dot on the
        // START hub, this is per-module so each screen says whether ITS OWN
        // data just came from the server or not, right where you're looking.
        when (moduleKey) {
            "growth" -> {
                val local = ro.alintudor.oracle.core.OracleGrowthEmergency.isForcingLocal(context)
                moduleTitleRow.addView(connectionDot(context, on = !local, label = if (local) "Growth: local mode (forced for testing)" else "Growth: server"))
            }
            "knowledge" -> {
                val hasError = ro.alintudor.oracle.core.OracleKnowledgeSync.lastError(context).isNotBlank()
                moduleTitleRow.addView(connectionDot(context, on = !hasError, label = if (hasError) "Knowledge: last sync failed, showing cached articles" else "Knowledge: synced"))
            }
        }
        center.addView(moduleTitleRow)
        // App icon next to the build number — the same mark the login/boot
        // screens use, small enough here to just anchor the version line.
        val buildRow = LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        buildRow.addView(ImageView(context).apply { setImageResource(ro.alintudor.oracle.R.drawable.ic_oracle); scaleType=ImageView.ScaleType.CENTER_INSIDE }, LinearLayout.LayoutParams(dp(17),dp(17)).apply{ setMargins(0,dp(3),dp(5),0) })
        buildRow.addView(TextView(context).apply { text=ro.alintudor.oracle.core.OracleBuildInfo.label(title);textSize=10f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.10f;setTextColor(Color.rgb(25,205,255));gravity=Gravity.CENTER;includeFontPadding=true })
        center.addView(buildRow)
        val centerRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        centerRow.addView(OracleMatrixRainView(context, accent), LinearLayout.LayoutParams(0, dp(50), 1f))
        centerRow.addView(center, LinearLayout.LayoutParams(-2, dp(76)))
        centerRow.addView(OracleMatrixRainView(context, accent), LinearLayout.LayoutParams(0, dp(50), 1f))
        header.addView(centerRow, LinearLayout.LayoutParams(0, dp(76), 1f))
        header.addView(View(context), LinearLayout.LayoutParams(dp(46),dp(46)))
        root.addView(header,LinearLayout.LayoutParams(-1,dp(84)))
        root.addView(View(context).apply{setBackgroundColor(accent)},LinearLayout.LayoutParams(-1,dp(1)).apply{setMargins(dp(6),0,dp(6),dp(5))})
        if (title.equals("GROWTH", true)) {
            val statusView = TextView(context).apply {
                val status = OracleMarketCalendar.status()
                text = "${if (status.open) "☀" else "☾"}  ${status.label}\n${status.countdown}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .04f
                gravity = Gravity.CENTER
                setTextColor(if (status.open) Color.rgb(90,245,135) else Color.rgb(255,85,95))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Color.rgb(7,11,22), dp(9), if (status.open) Color.rgb(55,130,80) else Color.rgb(145,55,65), dp(1))
            }
            marketStatusView = statusView
            fixedToolbar.addView(statusView, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(dp(4),0,dp(4),dp(4)) })
            marketStatusHandler.post(marketStatusUpdater)
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { marketStatusHandler.removeCallbacks(marketStatusUpdater) }
            })
        }
        root.addView(fixedToolbar,LinearLayout.LayoutParams(-1,-2))
        scrollView = ScrollView(context).apply {
            clipToPadding=false; isFillViewport=true; overScrollMode=View.OVER_SCROLL_ALWAYS; isNestedScrollingEnabled=false; addView(content)
            setOnScrollChangeListener { _, _, scrollY, _, _ -> scrollPositions[title] = scrollY }
        }
        if (ro.alintudor.oracle.core.OracleDemo.active(context)) {
            root.addView(TextView(context).apply {
                text = "DEMO  \u00b7  ${ro.alintudor.oracle.core.OracleDemo.LOCK} locked values \u2014 create an account for full access"
                textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .06f; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 205, 45))
                setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(Color.rgb(20, 16, 6), dp(10), Color.rgb(255, 205, 45), dp(1))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(8)) })
        }
        swipeRefresh = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(context).apply {
            setColorSchemeColors(accent)
            setProgressBackgroundColorSchemeColor(Color.rgb(7, 11, 22))
            setOnRefreshListener {
                onRefresh()
                // No visibility into when the caller's async refresh actually
                // finishes from in here — a brief spinner flash is the honest
                // signal "the gesture registered", not a claim about data
                // having arrived yet.
                postDelayed({ isRefreshing = false }, 600L)
            }
            addView(scrollView)
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(-1,0,1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else 0
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
            root.setPadding(dp(10), top + dp(16), dp(10), bottom); content.setPadding(dp(2),dp(10),dp(2),bottom + dp(32)); insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        scrollView.post { scrollView.scrollTo(0, scrollPositions[title] ?: 0) }
    }

    fun getScrollY(): Int = if (::scrollView.isInitialized) scrollView.scrollY else 0
    fun restoreScrollY(value: Int) { if (!::scrollView.isInitialized) return; scrollPositions[title] = value.coerceAtLeast(0); scrollView.post { scrollView.scrollTo(0, value.coerceAtLeast(0)) } }

    private fun plainButton(symbol:String,desc:String,click:()->Unit)=TextView(context).apply{ text=symbol;textSize=32f;gravity=Gravity.CENTER;contentDescription=desc;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(255,205,45));isClickable=true;isFocusable=true;setOnClickListener{click()} }
    private fun connectionDot(context: Context, on: Boolean, label: String) = TextView(context).apply {
        text = "\u25CF"; textSize = 9f; setTextColor(if (on) Color.rgb(80, 235, 130) else Color.rgb(255, 120, 90))
        contentDescription = label; gravity = Gravity.CENTER; setPadding(dp(5), 0, 0, 0)
    }
    fun addCard(heading:String,body:String){
        val card=LinearLayout(context).apply{ orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=rounded(Color.rgb(7,11,22),dp(15),Color.rgb(42,52,76),dp(1)) }
        card.addView(TextView(context).apply{text=heading.uppercase();textSize=17f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.04f;setTextColor(Color.WHITE)})
        card.addView(TextView(context).apply{text=body;textSize=14f;setTextColor(Color.rgb(175,182,198));setPadding(0,dp(7),0,0)})
        content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(10))})
    }
    fun addSectionLabel(text:String,sectionAccent:Int=accent){content.addView(TextView(context).apply{this.text=text.uppercase();textSize=11f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.14f;setTextColor(sectionAccent);setPadding(dp(5),dp(8),dp(5),dp(7))})}
    fun dp(v:Int)= (v*context.resources.displayMetrics.density).toInt()
    companion object{
        private val scrollPositions = mutableMapOf<String, Int>()
        fun rememberedScroll(title:String): Int = scrollPositions[title] ?: 0
        fun rounded(fill:Int,radius:Int,stroke:Int=Color.TRANSPARENT,strokeWidth:Int=0)=GradientDrawable().apply{setColor(fill);cornerRadius=radius.toFloat();if(strokeWidth>0)setStroke(strokeWidth,stroke)}
    }
}
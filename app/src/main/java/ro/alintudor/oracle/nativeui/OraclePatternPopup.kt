package ro.alintudor.oracle.nativeui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ro.alintudor.oracle.core.OracleChartPattern
import ro.alintudor.oracle.core.OracleMarketData
import ro.alintudor.oracle.core.OracleOhlcvPoint
import ro.alintudor.oracle.core.OraclePatternDetector
import java.util.Locale

/** PATTERNoster: a small "▲▽" glyph button meant to sit next to a ticker in
 *  Analysis, opening the 7-pattern geometric detector as a full-screen
 *  popup — same visual family as the ⓘ company-data button. */
fun patternButton(host: OracleNativeModule, ticker: String, accent: Int = host.accent, size: Int = host.dp(30)): TextView =
    TextView(host.root.context).apply {
        text = "\u25B2\u25BD"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(accent)
        background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), size / 2, accent, host.dp(1))
        isClickable = true; isFocusable = true
        contentDescription = "$ticker PATTERNoster"
        setOnClickListener { showPatternDialog(host.root.context, accent, ticker) }
    }

fun showPatternDialog(context: Context, accent: Int, ticker: String) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.rgb(1, 3, 8)))

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(1, 3, 8)) }
    val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(10), dp(8), dp(10)) }
    header.addView(TextView(context).apply {
        text = "\u2715"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = OracleNativeModule.rounded(Color.rgb(5, 8, 17), dp(12), accent, dp(1))
        isClickable = true; isFocusable = true; setOnClickListener { dialog.dismiss() }
    }, LinearLayout.LayoutParams(dp(42), dp(42)))
    header.addView(TextView(context).apply {
        text = "${ticker.uppercase(Locale.US)} \u00b7 PATTERNoster"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        setPadding(dp(12), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header)
    root.addView(View(context).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(-1, dp(1)))

    val scroll = ScrollView(context)
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(28)) }
    content.addView(TextView(context).apply {
        text = "7 geometric patterns, checked by measurable rules — not a subjective wave count. Double Top/Bottom, Head & Shoulders (both directions), Triangles, Support/Resistance Breakouts, Flags."
        textSize = 11.5f; setTextColor(Color.rgb(150, 160, 182)); setLineSpacing(dp(3).toFloat(), 1f)
    })
    val loader = ProgressBar(context).apply { isIndeterminate = true }
    content.addView(loader, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER; topMargin = dp(50) })
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    dialog.setContentView(root)
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    dialog.show()

    Thread {
        val fetched = runCatching { OracleMarketData.fetchDaily(ticker, "1y") }.getOrDefault(emptyList())
        val candles = fetched.sortedBy { it.timestamp } // oldest-first, matching what the detector scans internally
        val patterns = runCatching { OraclePatternDetector.detect(candles) }.getOrDefault(emptyList())
        val summary = OraclePatternDetector.summarize(patterns)
        Handler(Looper.getMainLooper()).post {
            content.removeView(loader)
            renderPatternResults(context, dp = ::dp, content = content, accent = accent, candles = candles, patterns = patterns, verdict = summary.verdict, bullishCount = summary.bullishCount, bearishCount = summary.bearishCount)
        }
    }.start()
}

private fun renderPatternResults(
    context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, candles: List<OracleOhlcvPoint>,
    patterns: List<OracleChartPattern>, verdict: String, bullishCount: Int, bearishCount: Int
) {
    val verdictColor = when {
        patterns.isEmpty() -> Color.rgb(150, 160, 182)
        bullishCount > bearishCount -> Color.rgb(105, 245, 35)
        bearishCount > bullishCount -> Color.rgb(255, 90, 90)
        else -> Color.rgb(255, 205, 55)
    }
    // The "ANALYSIS DONE" style conclusion — one clear line, up top, before the individual pattern cards.
    val banner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13))
        background = OracleNativeModule.rounded(Color.rgb(7, 12, 23), dp(14), verdictColor, dp(1))
    }
    banner.addView(TextView(context).apply {
        text = "ANALYSIS DONE"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(verdictColor)
    })
    banner.addView(TextView(context).apply {
        text = verdict; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0)
    })
    content.addView(banner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(14) })

    if (patterns.isEmpty()) {
        content.addView(TextView(context).apply {
            text = "Nothing matched the 7 shapes on the last year of daily candles right now. That's a normal, common outcome — most days don't sit inside a clean geometric setup."
            textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setLineSpacing(dp(3).toFloat(), 1f)
        })
        return
    }

    patterns.forEach { p ->
        val color = when (p.bullish) { true -> Color.rgb(105, 245, 35); false -> Color.rgb(255, 90, 90); null -> Color.rgb(255, 205, 55) }
        val direction = when (p.bullish) { true -> "BULLISH"; false -> "BEARISH"; null -> "DEPENDS ON BREAKOUT" }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), dp(12), color, dp(1))
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = p.label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(context).apply {
            text = direction; textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setTextColor(color)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = OracleNativeModule.rounded(Color.rgb(6, 10, 20), dp(8), color, dp(1))
        }.also { badge ->
            android.animation.ValueAnimator.ofFloat(0.55f, 1f, 0.55f).apply {
                duration = 1400L; repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { anim -> if (badge.isAttachedToWindow) badge.alpha = anim.animatedValue as Float else anim.cancel() }
            }.start()
        })
        card.addView(top)
        card.addView(TextView(context).apply {
            text = p.note; textSize = 12.5f; setTextColor(Color.rgb(190, 198, 216)); setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        })
        generalDescription(p.type)?.let { extra ->
            card.addView(TextView(context).apply {
                text = extra; textSize = 11.5f; setTextColor(Color.rgb(140, 150, 172)); setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        }
        // --- Chart snapshot: a window of candles around the pattern, with its
        // defining points marked and connected — visual confirmation next to
        // the text, not instead of it. Price range and date range labeled
        // directly around the chart, since a bare line with no scale isn't
        // actually verifiable at a glance. ---
        buildPatternSnapshot(context, dp, candles, p, color)?.let {
            card.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
}

/** A short, general "what this pattern typically means" line — separate from
 *  the per-detection note (which has this ticker's specific numbers) so the
 *  card reads as "here's the textbook shape, and here's how this ticker
 *  matched it" rather than repeating the same sentence twice. */
private fun generalDescription(type: String): String? = when (type) {
    "DOUBLE_TOP" -> "Two failed attempts to break the same high often mean buyers ran out of conviction there — a close below the pullback low between the peaks is the usual confirmation, not the second peak alone."
    "DOUBLE_BOTTOM" -> "Two failed attempts to break the same low often mean sellers ran out of conviction there — a close above the rally high between the troughs is the usual confirmation."
    "HEAD_SHOULDERS" -> "A classic exhaustion shape: the middle peak is the last, weaker push before momentum fades. Confirmation is a close below the neckline, not the shape alone."
    "INV_HEAD_SHOULDERS" -> "The bullish mirror of Head & Shoulders — a final, weaker sell-off before buyers take over. Confirmation is a close above the neckline."
    "TRIANGLE_ASC" -> "Buyers keep meeting the same ceiling but sellers give less ground each time — a squeeze that has historically favored an upside break, though it isn't guaranteed."
    "TRIANGLE_DESC" -> "Sellers keep meeting the same floor but buyers give less ground each time — a squeeze that has historically favored a downside break, though it isn't guaranteed."
    "TRIANGLE_SYM" -> "Neither side is winning yet — range keeps compressing. The direction of the eventual break matters far more than the triangle itself."
    "BREAKOUT_RESISTANCE" -> "A level tested multiple times and finally cleared — often (not always) followed by a retest of that same level from above as new support."
    "BREAKOUT_SUPPORT" -> "A level tested multiple times and finally lost — often (not always) followed by a retest of that same level from below as new resistance."
    "FLAG_BULLISH" -> "A sharp rally followed by a shallow, orderly pullback — historically a continuation setup rather than a reversal, as long as the pullback stays shallow."
    "FLAG_BEARISH" -> "A sharp decline followed by a shallow, orderly bounce — historically a continuation setup rather than a reversal, as long as the bounce stays shallow."
    else -> null
}

/** Windows the candle list to roughly the pattern's own span plus some
 *  padding on both sides for context, and translates the pattern's markers
 *  (given in full-list indices) into indices local to that window. */
private fun buildPatternSnapshot(context: Context, dp: (Int) -> Int, candles: List<OracleOhlcvPoint>, pattern: OracleChartPattern, color: Int): View? {
    if (candles.isEmpty()) return null
    val span = pattern.toIndex - pattern.fromIndex
    val padding = maxOf(4, span / 4)
    val from = maxOf(0, pattern.fromIndex - padding)
    val to = minOf(candles.size - 1, pattern.toIndex + padding)
    if (to <= from) return null
    val window = candles.subList(from, to + 1)
    val localMarkers = pattern.markers.mapNotNull { (idx, price) -> if (idx in from..to) (idx - from) to price else null }
    val prices = window.map { it.close } + localMarkers.map { it.second }
    val maxPrice = prices.max(); val minPrice = prices.min()
    val dateFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.US)
    val fromDate = dateFmt.format(java.util.Date(window.first().timestamp))
    val toDate = dateFmt.format(java.util.Date(window.last().timestamp))

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = OracleNativeModule.rounded(Color.rgb(5, 8, 17), dp(8), Color.rgb(30, 38, 58), dp(1))
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    container.addView(TextView(context).apply {
        text = "%.2f".format(java.util.Locale.US, maxPrice); textSize = 9f; setTextColor(Color.rgb(120, 130, 152))
    })
    container.addView(PatternMiniChartView(context, window, localMarkers, color), LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(2); bottomMargin = dp(2) })
    val bottomRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    bottomRow.addView(TextView(context).apply {
        text = "%.2f".format(java.util.Locale.US, minPrice); textSize = 9f; setTextColor(Color.rgb(120, 130, 152))
    }, LinearLayout.LayoutParams(0, -2, 1f))
    bottomRow.addView(TextView(context).apply {
        text = "$fromDate \u2192 $toDate"; textSize = 9f; setTextColor(Color.rgb(120, 130, 152)); gravity = Gravity.END
    }, LinearLayout.LayoutParams(0, -2, 1f))
    container.addView(bottomRow)
    return container
}

/** A minimal, dependency-free line-chart View: the closing-price path across
 *  the window in a neutral tone, plus the pattern's own marker points in its
 *  direction color, connected by a dashed line so the shape the detector
 *  matched is visible at a glance. Deliberately simple (no axes, no candle
 *  bodies) — this is a confirmation thumbnail, not the main chart. */
private class PatternMiniChartView(
    context: Context,
    private val candles: List<OracleOhlcvPoint>,
    private val markers: List<Pair<Int, Double>>,
    private val color: Int
) : View(context) {
    private val linePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(95, 105, 135) }
    private val markerLinePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2.5f; pathEffect = DashPathEffect(floatArrayOf(7f, 6f), 0f) }
    private val markerDotPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private var drawProgress = 0f
    private var animationStarted = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (candles.size < 2) return
        if (!animationStarted) {
            animationStarted = true
            post {
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900L; interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { anim -> if (isAttachedToWindow) { drawProgress = anim.animatedValue as Float; invalidate() } else anim.cancel() }
                }.start()
            }
        }
        val w = width.toFloat(); val h = height.toFloat()
        val padY = h * 0.12f
        val prices = candles.map { it.close } + markers.map { it.second }
        val minP = prices.min(); val maxP = prices.max()
        val span = (maxP - minP).takeIf { it > 0.0 } ?: (maxP.takeIf { it > 0.0 } ?: 1.0) * 0.02
        fun y(v: Double): Float = h - padY - ((v - minP) / span * (h - padY * 2)).toFloat()
        val stepX = w / (candles.size - 1).toFloat()
        fun x(i: Int): Float = i * stepX

        val path = Path()
        candles.forEachIndexed { i, c -> if (i == 0) path.moveTo(x(i), y(c.close)) else path.lineTo(x(i), y(c.close)) }
        canvas.drawPath(path, linePaint)

        if (markers.size >= 2) {
            markerLinePaint.color = color
            val mp = Path()
            markers.forEachIndexed { i, (idx, price) -> if (i == 0) mp.moveTo(x(idx), y(price)) else mp.lineTo(x(idx), y(price)) }
            // The dashed marker line draws itself in progressively — a static
            // "confirmation" line reads as decoration; a line that traces the
            // shape reads as the app actually pointing it out.
            val measure = android.graphics.PathMeasure(mp, false)
            val revealed = Path()
            measure.getSegment(0f, measure.length * drawProgress, revealed, true)
            canvas.drawPath(revealed, markerLinePaint)
        }
        markerDotPaint.color = color
        val lastVisible = ((markers.size - 1) * drawProgress).toInt().coerceIn(0, markers.size - 1)
        markers.forEachIndexed { i, (idx, price) -> if (i <= lastVisible) canvas.drawCircle(x(idx), y(price), h * 0.045f, markerDotPaint) }
    }
}

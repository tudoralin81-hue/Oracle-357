package ro.alintudor.oracle.nativeui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ro.alintudor.oracle.core.OracleFundamentals
import ro.alintudor.oracle.core.OracleMarketData
import ro.alintudor.oracle.core.OracleOhlcvPoint
import ro.alintudor.oracle.core.OracleRealData
import ro.alintudor.oracle.core.OracleTechnicalIndicators
import java.util.Locale

/** Prompts for a second ticker, then opens the comparison popup for it.
 *  Kept separate from the chart so the comparison always uses a fixed,
 *  independent window — not whatever intraday timeframe the chart happens
 *  to be showing (that mismatch is why the old inline overlay never lined up). */
fun showCompareDialog(host: OracleNativeModule, primaryTicker: String) {
    val context = host.root.context
    val input = EditText(context).apply {
        hint = "Ticker (e.g. SPY)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        setSingleLine(true); textSize = 16f; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 130, 152))
        setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
        background = OracleNativeModule.rounded(Color.rgb(4, 8, 16), host.dp(10), host.accent, host.dp(1))
    }
    val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(20), host.dp(10), host.dp(20), host.dp(6)) }
    box.addView(input, LinearLayout.LayoutParams(-1, -2))
    AlertDialog.Builder(context)
        .setTitle("Compare $primaryTicker with\u2026")
        .setView(box)
        .setPositiveButton("Compare") { _, _ ->
            val second = input.text.toString().trim().uppercase(Locale.US)
            if (second.isNotBlank() && second != primaryTicker) showComparePopup(context, host.accent, primaryTicker, second)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun showComparePopup(context: Context, accent: Int, tickerA: String, tickerB: String) {
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
    val titleGroup = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    titleGroup.addView(TextView(context).apply {
        text = "$tickerA  vs  $tickerB"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
    })
    titleGroup.addView(TextView(context).apply {
        text = "${resolvedCompanyName(context, tickerA)}  vs  ${resolvedCompanyName(context, tickerB)}"
        textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182))
    })
    header.addView(titleGroup, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header)
    root.addView(View(context).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(-1, dp(1)))

    val scroll = ScrollView(context)
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(28)) }
    val loader = ProgressBar(context).apply { isIndeterminate = true }
    content.addView(loader, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER; topMargin = dp(40) })
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    dialog.setContentView(root)
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    dialog.show()

    // Fixed 3-month daily window for both — independent of any chart
    // timeframe, so the two series are always genuinely aligned by date.
    Thread {
        val a = runCatching { OracleMarketData.fetchDaily(tickerA, "3mo") }.getOrDefault(emptyList()).sortedBy { it.timestamp }
        val b = runCatching { OracleMarketData.fetchDaily(tickerB, "3mo") }.getOrDefault(emptyList()).sortedBy { it.timestamp }
        val fundA = runCatching { OracleRealData.fundamentals(tickerA) }.getOrNull()
        val fundB = runCatching { OracleRealData.fundamentals(tickerB) }.getOrNull()
        Handler(Looper.getMainLooper()).post {
            content.removeView(loader)
            if (a.size < 2 || b.size < 2) {
                content.addView(TextView(context).apply { text = "Couldn't load 3-month data for one of these tickers."; textSize = 14f; setTextColor(Color.rgb(255, 130, 130)) })
                return@post
            }
            buildComparison(context, dp = ::dp, accent = accent, content = content, tickerA = tickerA, tickerB = tickerB, a = a, b = b, fundA = fundA, fundB = fundB)
        }
    }.start()
}

private fun buildComparison(context: Context, dp: (Int) -> Int, accent: Int, content: LinearLayout, tickerA: String, tickerB: String, a: List<OracleOhlcvPoint>, b: List<OracleOhlcvPoint>, fundA: OracleFundamentals?, fundB: OracleFundamentals?) {
    fun pctReturn(series: List<OracleOhlcvPoint>, sessions: Int): Double? {
        if (series.size <= sessions) return null
        val base = series[series.size - 1 - sessions].close
        return if (base > 0.0) (series.last().close / base - 1.0) * 100.0 else null
    }
    fun annualVol(series: List<OracleOhlcvPoint>): Double? {
        if (series.size < 21) return null
        val rets = series.takeLast(60).zipWithNext { x, y -> if (x.close > 0.0) (y.close / x.close - 1.0) else 0.0 }
        if (rets.size < 10) return null
        val m = rets.average()
        return kotlin.math.sqrt(rets.sumOf { (it - m) * (it - m) } / rets.size) * kotlin.math.sqrt(252.0) * 100.0
    }
    fun maxDrawdown(series: List<OracleOhlcvPoint>): Double? {
        if (series.size < 5) return null
        var peak = series.first().close; var worst = 0.0
        for (p in series) { if (p.close > peak) peak = p.close; if (peak > 0.0) worst = minOf(worst, (p.close / peak - 1.0) * 100.0) }
        return worst
    }
    fun fromHigh52(series: List<OracleOhlcvPoint>): Double? {
        val hi = series.maxByOrNull { it.high }?.high ?: return null
        return if (hi > 0.0) (series.last().close / hi - 1.0) * 100.0 else null
    }
    fun correlation(x: List<OracleOhlcvPoint>, y: List<OracleOhlcvPoint>): Double? {
        val byDayY = y.associateBy { it.timestamp / 86_400_000L }
        val pairs = ArrayList<Pair<Double, Double>>()
        var prevX: Double? = null; var prevY: Double? = null
        for (px in x) {
            val py = byDayY[px.timestamp / 86_400_000L] ?: continue
            if (prevX != null && prevY != null && prevX!! > 0.0 && prevY!! > 0.0) pairs += (px.close / prevX!! - 1.0) to (py.close / prevY!! - 1.0)
            prevX = px.close; prevY = py.close
        }
        if (pairs.size < 15) return null
        val mx = pairs.map { it.first }.average(); val my = pairs.map { it.second }.average()
        val cov = pairs.sumOf { (it.first - mx) * (it.second - my) }
        val sx = kotlin.math.sqrt(pairs.sumOf { (it.first - mx) * (it.first - mx) })
        val sy = kotlin.math.sqrt(pairs.sumOf { (it.second - my) * (it.second - my) })
        return if (sx > 0.0 && sy > 0.0) cov / (sx * sy) else null
    }

    val techA = OracleTechnicalIndicators.fromCandles(tickerA, a)
    val techB = OracleTechnicalIndicators.fromCandles(tickerB, b)

    // Relative performance chart: both rebased to 0% at the start of the
    // shared window, so the lines are directly comparable regardless of price.
    content.addView(RelativeChartView(context, a, b, tickerA, tickerB, accent), LinearLayout.LayoutParams(-1, dp(220)).apply { bottomMargin = dp(16) })

    fun row(label: String, va: String, vb: String, colorA: Int = Color.WHITE, colorB: Int = Color.WHITE) {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
        r.addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.rgb(150, 160, 182)) }, LinearLayout.LayoutParams(0, -2, 1.25f))
        r.addView(TextView(context).apply { text = va; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colorA); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(context).apply { text = vb; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colorB); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(r)
    }
    fun divider() = content.addView(View(context).apply { setBackgroundColor(Color.rgb(30, 38, 55)) }, LinearLayout.LayoutParams(-1, dp(1)))
    fun sectionLabel(t: String) = content.addView(TextView(context).apply {
        text = t; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
        setTextColor(accent); setPadding(0, dp(16), 0, dp(4))
    })
    fun pctColor(v: Double?) = if ((v ?: 0.0) >= 0.0) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120)
    fun pctText(v: Double?) = v?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "\u2014"
    fun numText(v: Double?, d: Int = 1) = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: "\u2014"
    // Green marks the better of the two for metrics where "more" is better
    // (or "less", when lowerIsBetter) — so the eye finds the winner per row.
    fun better(x: Double?, y: Double?, lowerIsBetter: Boolean = false): Pair<Int, Int> {
        if (x == null || y == null) return Color.WHITE to Color.WHITE
        val win = Color.rgb(105, 245, 35); val lose = Color.rgb(190, 198, 214)
        val xWins = if (lowerIsBetter) x < y else x > y
        return if (x == y) Color.WHITE to Color.WHITE else if (xWins) win to lose else lose to win
    }

    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
    head.addView(TextView(context).apply { text = "" }, LinearLayout.LayoutParams(0, -2, 1.25f))
    head.addView(TextView(context).apply { text = tickerA; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = tickerB; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(255, 205, 45)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(head)
    divider()

    sectionLabel("PRICE & PERFORMANCE")
    row("Price", numText(a.last().close, 2), numText(b.last().close, 2))
    val r5a = pctReturn(a, 5); val r5b = pctReturn(b, 5)
    row("Return 5D", pctText(r5a), pctText(r5b), pctColor(r5a), pctColor(r5b))
    val r20a = pctReturn(a, 20); val r20b = pctReturn(b, 20)
    row("Return 20D", pctText(r20a), pctText(r20b), pctColor(r20a), pctColor(r20b))
    val r60a = pctReturn(a, minOf(60, a.size - 1)); val r60b = pctReturn(b, minOf(60, b.size - 1))
    row("Return \u224860D", pctText(r60a), pctText(r60b), pctColor(r60a), pctColor(r60b))
    val fhA = fromHigh52(a); val fhB = fromHigh52(b)
    row("From 3-month high", pctText(fhA), pctText(fhB), better(fhA, fhB).first, better(fhA, fhB).second)

    sectionLabel("RISK")
    val volA = annualVol(a); val volB = annualVol(b)
    row("Volatility (annual.)", volA?.let { String.format(Locale.US, "%.0f%%", it) } ?: "\u2014", volB?.let { String.format(Locale.US, "%.0f%%", it) } ?: "\u2014",
        better(volA, volB, lowerIsBetter = true).first, better(volA, volB, lowerIsBetter = true).second)
    val ddA = maxDrawdown(a); val ddB = maxDrawdown(b)
    row("Max drawdown 3M", pctText(ddA), pctText(ddB), better(ddA, ddB).first, better(ddA, ddB).second)
    val atrA = techA?.atr14?.let { it / a.last().close * 100.0 }; val atrB = techB?.atr14?.let { it / b.last().close * 100.0 }
    row("Daily range (ATR%)", atrA?.let { String.format(Locale.US, "%.1f%%", it) } ?: "\u2014", atrB?.let { String.format(Locale.US, "%.1f%%", it) } ?: "\u2014",
        better(atrA, atrB, lowerIsBetter = true).first, better(atrA, atrB, lowerIsBetter = true).second)
    // Return per unit of volatility — the honest way to compare two names
    // that moved differently for very different amounts of risk.
    val effA = if (volA != null && volA > 0.0 && r60a != null) r60a / volA else null
    val effB = if (volB != null && volB > 0.0 && r60b != null) r60b / volB else null
    row("Return / risk", numText(effA, 2), numText(effB, 2), better(effA, effB).first, better(effA, effB).second)

    sectionLabel("TREND & MOMENTUM")
    row("RSI(14)", numText(techA?.rsi, 0), numText(techB?.rsi, 0))
    row("vs SMA50", techA?.let { if (a.last().close >= it.sma50) "Above" else "Below" } ?: "\u2014", techB?.let { if (b.last().close >= it.sma50) "Above" else "Below" } ?: "\u2014",
        techA?.let { if (a.last().close >= it.sma50) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120) } ?: Color.WHITE,
        techB?.let { if (b.last().close >= it.sma50) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120) } ?: Color.WHITE)
    row("ADX(14) trend strength", numText(techA?.adx, 0), numText(techB?.adx, 0), better(techA?.adx, techB?.adx).first, better(techA?.adx, techB?.adx).second)
    val techScoreA = techA?.techScore?.toDouble(); val techScoreB = techB?.techScore?.toDouble()
    row("Oracle score", techScoreA?.let { "${it.toInt()}/100" } ?: "\u2014", techScoreB?.let { "${it.toInt()}/100" } ?: "\u2014",
        better(techScoreA, techScoreB).first, better(techScoreA, techScoreB).second)

    sectionLabel("RELATIONSHIP")
    val corr = correlation(a, b)
    row("Correlation (daily)", corr?.let { String.format(Locale.US, "%.2f", it) } ?: "\u2014", "", Color.WHITE, Color.WHITE)

    if (fundA != null || fundB != null) {
        sectionLabel("FUNDAMENTALS")
        fun capText(v: Double?) = v?.let {
            when { it >= 1e12 -> String.format(Locale.US, "%.2fT", it / 1e12); it >= 1e9 -> String.format(Locale.US, "%.1fB", it / 1e9); it >= 1e6 -> String.format(Locale.US, "%.0fM", it / 1e6); else -> String.format(Locale.US, "%.0f", it) }
        } ?: "\u2014"
        fun pctText2(v: Double?) = v?.let { String.format(Locale.US, "%+.1f%%", it * 100.0) } ?: "\u2014"
        fun numText2(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "\u2014"
        row("Sector", fundA?.sector ?: "\u2014", fundB?.sector ?: "\u2014")
        row("Market cap", capText(fundA?.marketCap), capText(fundB?.marketCap))
        val peA = fundA?.trailingPe; val peB = fundB?.trailingPe
        row("P/E (trailing)", numText2(peA), numText2(peB), better(peA, peB, lowerIsBetter = true).first, better(peA, peB, lowerIsBetter = true).second)
        row("P/E (forward)", numText2(fundA?.forwardPe), numText2(fundB?.forwardPe))
        val pmA = fundA?.profitMargin; val pmB = fundB?.profitMargin
        row("Profit margin", pctText2(pmA), pctText2(pmB), better(pmA, pmB).first, better(pmA, pmB).second)
        val rgA = fundA?.revenueGrowth; val rgB = fundB?.revenueGrowth
        row("Revenue growth", pctText2(rgA), pctText2(rgB), better(rgA, rgB).first, better(rgA, rgB).second)
        val betaA = fundA?.beta; val betaB = fundB?.beta
        row("Beta", numText2(betaA), numText2(betaB))
    }

    // --- Verdict in plain words -------------------------------------------
    val verdict = StringBuilder()
    if (r20a != null && r20b != null) {
        val lead = kotlin.math.abs(r20a - r20b)
        val leader = if (r20a > r20b) tickerA else tickerB
        val laggard = if (r20a > r20b) tickerB else tickerA
        verdict.append(when {
            lead < 1.5 -> "Over the last 20 sessions $tickerA and $tickerB moved almost identically \u2014 there is no meaningful performance gap between them. "
            else -> "$leader leads $laggard by ${String.format(Locale.US, "%.1f", lead)} points over the last 20 sessions. "
        })
    }
    if (volA != null && volB != null && effA != null && effB != null) {
        val calmer = if (volA < volB) tickerA else tickerB
        val efficient = if (effA > effB) tickerA else tickerB
        verdict.append("$calmer is the calmer of the two (${String.format(Locale.US, "%.0f%%", minOf(volA, volB))} vs ${String.format(Locale.US, "%.0f%%", maxOf(volA, volB))} annualised), ")
        verdict.append(if (calmer == efficient) "and it also delivered more return per unit of risk. " else "but $efficient delivered more return per unit of risk. ")
    }
    if (corr != null) {
        verdict.append(when {
            corr >= 0.75 -> "They move closely together (correlation ${String.format(Locale.US, "%.2f", corr)}), so holding both adds little diversification \u2014 it is largely one bet in two names."
            corr >= 0.4 -> "They are moderately correlated (${String.format(Locale.US, "%.2f", corr)}): related, but not interchangeable."
            corr >= 0.0 -> "They are only loosely correlated (${String.format(Locale.US, "%.2f", corr)}), so together they genuinely spread risk."
            else -> "They tend to move in opposite directions (${String.format(Locale.US, "%.2f", corr)}) \u2014 one hedges the other."
        })
    }
    if (verdict.isNotBlank()) {
        content.addView(TextView(context).apply {
            text = "IN SHORT"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
            setTextColor(accent); setPadding(0, dp(20), 0, dp(6))
        })
        content.addView(TextView(context).apply {
            text = verdict.toString().trim(); textSize = 13f; setTextColor(Color.rgb(205, 212, 226)); setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), dp(12), Color.rgb(35, 44, 66), dp(1))
        }, LinearLayout.LayoutParams(-1, -2))
    }
    content.addView(TextView(context).apply {
        text = "Window: last 3 months of daily closes. Informational only \u2014 not investment advice."
        textSize = 10f; setTextColor(Color.rgb(120, 130, 152)); setPadding(0, dp(14), 0, 0)
    })
}

private class RelativeChartView(
    context: Context, private val a: List<OracleOhlcvPoint>, private val b: List<OracleOhlcvPoint>,
    private val labelA: String, private val labelB: String, private val accent: Int
) : View(context) {
    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; strokeWidth = 4f; style = Paint.Style.STROKE }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 205, 45); strokeWidth = 4f; style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 12f; val top = 34f
        val startTs = maxOf(a.first().timestamp, b.first().timestamp)
        val ra = a.filter { it.timestamp >= startTs }; val rb = b.filter { it.timestamp >= startTs }
        if (ra.size < 2 || rb.size < 2) return
        val baseA = ra.first().close; val baseB = rb.first().close
        val seriesA = ra.map { (it.close / baseA - 1.0) * 100.0 }
        val seriesB = rb.map { (it.close / baseB - 1.0) * 100.0 }
        val lo = minOf(seriesA.min(), seriesB.min(), 0.0); val hi = maxOf(seriesA.max(), seriesB.max(), 0.0)
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        fun y(v: Double) = (top + (h - top - pad) * (1.0 - (v - lo) / span)).toFloat()
        fun path(series: List<Double>): Path { val p = Path()
            series.forEachIndexed { i, v -> val x = pad + (w - 2 * pad) * i / (series.size - 1); val yy = y(v); if (i == 0) p.moveTo(x, yy) else p.lineTo(x, yy) }
            return p }
        val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 70, 90); strokeWidth = 2f }
        c.drawLine(pad, y(0.0), w - pad, y(0.0), zero)
        c.drawPath(path(seriesA), paintA)
        c.drawPath(path(seriesB), paintB)
        text.color = accent
        c.drawText("$labelA ${String.format(Locale.US, "%+.1f", seriesA.last())}%", pad, 24f, text)
        text.color = Color.rgb(255, 205, 45)
        val bLabel = "$labelB ${String.format(Locale.US, "%+.1f", seriesB.last())}%"
        c.drawText(bLabel, w - pad - text.measureText(bLabel), 24f, text)
    }
}

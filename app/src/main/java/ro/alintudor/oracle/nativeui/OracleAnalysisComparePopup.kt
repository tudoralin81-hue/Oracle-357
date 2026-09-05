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
import ro.alintudor.oracle.core.OracleMarketData
import ro.alintudor.oracle.core.OracleOhlcvPoint
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
    header.addView(TextView(context).apply {
        text = "$tickerA  vs  $tickerB"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        setPadding(dp(12), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        Handler(Looper.getMainLooper()).post {
            content.removeView(loader)
            if (a.size < 2 || b.size < 2) {
                content.addView(TextView(context).apply { text = "Couldn't load 3-month data for one of these tickers."; textSize = 14f; setTextColor(Color.rgb(255, 130, 130)) })
                return@post
            }
            buildComparison(context, dp = ::dp, accent = accent, content = content, tickerA = tickerA, tickerB = tickerB, a = a, b = b)
        }
    }.start()
}

private fun buildComparison(context: Context, dp: (Int) -> Int, accent: Int, content: LinearLayout, tickerA: String, tickerB: String, a: List<OracleOhlcvPoint>, b: List<OracleOhlcvPoint>) {
    fun pctReturn(series: List<OracleOhlcvPoint>, sessions: Int): Double? {
        if (series.size <= sessions) return null
        val base = series[series.size - 1 - sessions].close
        return if (base > 0.0) (series.last().close / base - 1.0) * 100.0 else null
    }
    val techA = OracleTechnicalIndicators.fromCandles(tickerA, a)
    val techB = OracleTechnicalIndicators.fromCandles(tickerB, b)

    // Relative performance chart: both rebased to 100 at the start of the
    // shared window, so the lines are directly comparable regardless of price.
    content.addView(RelativeChartView(context, a, b, tickerA, tickerB, accent), LinearLayout.LayoutParams(-1, dp(220)).apply { bottomMargin = dp(16) })

    fun row(label: String, va: String, vb: String, colorA: Int = Color.WHITE, colorB: Int = Color.WHITE) {
        val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(9), 0, dp(9)) }
        r.addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.rgb(150, 160, 182)) }, LinearLayout.LayoutParams(0, -2, 1.1f))
        r.addView(TextView(context).apply { text = va; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colorA); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(TextView(context).apply { text = vb; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(colorB); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(r)
    }
    fun divider() = content.addView(View(context).apply { setBackgroundColor(Color.rgb(30, 38, 55)) }, LinearLayout.LayoutParams(-1, dp(1)))
    fun pctColor(v: Double?) = if ((v ?: 0.0) >= 0.0) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120)
    fun pctText(v: Double?) = v?.let { String.format(Locale.US, "%+.1f%%", it) } ?: "\u2014"

    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
    head.addView(TextView(context).apply { text = ""; }, LinearLayout.LayoutParams(0, -2, 1.1f))
    head.addView(TextView(context).apply { text = tickerA; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = tickerB; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(head)
    divider()
    row("Price", String.format(Locale.US, "%.2f", a.last().close), String.format(Locale.US, "%.2f", b.last().close))
    divider()
    val r5a = pctReturn(a, 5); val r5b = pctReturn(b, 5)
    row("Return 5D", pctText(r5a), pctText(r5b), pctColor(r5a), pctColor(r5b))
    val r20a = pctReturn(a, 20); val r20b = pctReturn(b, 20)
    row("Return 20D", pctText(r20a), pctText(r20b), pctColor(r20a), pctColor(r20b))
    val r60a = pctReturn(a, minOf(60, a.size - 1)); val r60b = pctReturn(b, minOf(60, b.size - 1))
    row("Return \u224860D", pctText(r60a), pctText(r60b), pctColor(r60a), pctColor(r60b))
    divider()
    row("RSI(14)", techA?.rsi?.let { String.format(Locale.US, "%.0f", it) } ?: "\u2014", techB?.rsi?.let { String.format(Locale.US, "%.0f", it) } ?: "\u2014")
    row("vs SMA50", techA?.let { if (a.last().close >= it.sma50) "Above" else "Below" } ?: "\u2014", techB?.let { if (b.last().close >= it.sma50) "Above" else "Below" } ?: "\u2014",
        techA?.let { if (a.last().close >= it.sma50) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120) } ?: Color.WHITE,
        techB?.let { if (b.last().close >= it.sma50) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120) } ?: Color.WHITE)
    row("ADX(14)", techA?.adx?.let { String.format(Locale.US, "%.0f", it) } ?: "\u2014", techB?.adx?.let { String.format(Locale.US, "%.0f", it) } ?: "\u2014")
    val techScoreA = techA?.techScore; val techScoreB = techB?.techScore
    divider()
    row("Oracle score", techScoreA?.let { "$it/100" } ?: "\u2014", techScoreB?.let { "$it/100" } ?: "\u2014",
        techScoreA?.let { if (it >= 65) Color.rgb(105, 245, 35) else Color.rgb(255, 205, 45) } ?: Color.WHITE,
        techScoreB?.let { if (it >= 65) Color.rgb(105, 245, 35) else Color.rgb(255, 205, 45) } ?: Color.WHITE)

    val winner = when {
        r20a == null || r20b == null -> null
        r20a > r20b -> tickerA
        r20b > r20a -> tickerB
        else -> null
    }
    if (winner != null) content.addView(TextView(context).apply {
        text = "$winner has outperformed over the last 20 sessions."
        textSize = 12f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, dp(14), 0, 0)
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

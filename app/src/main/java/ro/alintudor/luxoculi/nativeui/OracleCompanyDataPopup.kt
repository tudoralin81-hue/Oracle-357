package ro.alintudor.luxoculi.nativeui

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import ro.alintudor.luxoculi.core.OracleCompanyProfile
import ro.alintudor.luxoculi.core.OracleFundamentals
import ro.alintudor.luxoculi.core.OracleMarketUniverse
import ro.alintudor.luxoculi.core.OracleQuarterEarning
import ro.alintudor.luxoculi.core.OracleQuarterFinancial
import ro.alintudor.luxoculi.core.OracleRealData
import ro.alintudor.luxoculi.core.OracleSP500Universe
import java.util.Date
import java.util.Locale

/**
 * Resolves a ticker to its full company name: the curated S&P 500 list, then
 * the broader Nasdaq universe feed, then a small hardcoded fallback for a
 * handful of very common non-listed-elsewhere names, then the ticker itself.
 * Shared by the Compare popup and OracleAnalysisModules' single-ticker view
 * so a ticker resolves to the same name everywhere in the app.
 */
fun resolvedCompanyName(context: Context, t: String): String {
    val ticker = t.trim().uppercase(Locale.US)
    OracleSP500Universe.nameFor(context, ticker)?.takeIf { it.isNotBlank() }?.let { return it }
    OracleMarketUniverse.nameFor(context, ticker)?.takeIf { it.isNotBlank() }?.let { return it }
    return when (ticker) {
        "AAOI" -> "Applied Optoelectronics, Inc."
        "APLD" -> "Applied Digital Corporation"
        "NVDA" -> "NVIDIA Corporation"; "AAPL" -> "Apple Inc."; "MSFT" -> "Microsoft Corporation"; "AMZN" -> "Amazon.com, Inc."; "GOOGL" -> "Alphabet Inc."; "META" -> "Meta Platforms, Inc."; "TSLA" -> "Tesla, Inc."; "AMD" -> "Advanced Micro Devices, Inc."; "AVGO" -> "Broadcom Inc."; "NFLX" -> "Netflix, Inc."
        else -> ticker
    }
}

/**
 * A small, consistently-styled "ⓘ" button meant to sit next to a ticker
 * wherever one appears (Analysis, Growth, Portfolio, Watchlist) and open the
 * same Company Data popup — so the person never has to leave the app to see
 * a company's profile, recent financials, earnings track record, or
 * dividend summary, the way a TradingView-style ticker page would show it.
 */
fun companyInfoButton(host: OracleNativeModule, ticker: String, accent: Int = host.accent, size: Int = host.dp(30)): TextView =
    TextView(host.root.context).apply {
        text = "\u24D8"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(accent)
        background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), size / 2, accent, host.dp(1))
        isClickable = true; isFocusable = true
        contentDescription = "$ticker company data"
        setOnClickListener { showCompanyDataDialog(host.root.context, accent, ticker) }
    }

fun showCompanyDataDialog(context: Context, accent: Int, ticker: String) {
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
        text = ticker.uppercase(Locale.US); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        setPadding(dp(12), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header)
    root.addView(View(context).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(-1, dp(1)))

    val tabBar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(10), dp(10), dp(4)) }
    val tabs = listOf("PROFILE", "FINANCIALS", "EARNINGS", "DIVIDENDS")
    val tabViews = ArrayList<TextView>()
    root.addView(tabBar)

    val scroll = ScrollView(context)
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
    val loader = ProgressBar(context).apply { isIndeterminate = true }
    content.addView(loader, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER; topMargin = dp(60) })
    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    dialog.setContentView(root)
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    dialog.show()

    var profile: OracleCompanyProfile? = null
    var activeTab = 0
    fun selectTab(i: Int) {
        activeTab = i
        tabViews.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.setTextColor(if (on) Color.WHITE else Color.rgb(140, 150, 172))
            tv.background = if (on) OracleNativeModule.rounded(accent, dp(9)) else null
        }
        content.removeAllViews()
        val p = profile
        if (p == null) {
            content.addView(TextView(context).apply { text = "No data available for $ticker."; textSize = 14f; setTextColor(Color.rgb(255, 150, 150)); setPadding(0, dp(30), 0, 0) })
            return
        }
        when (i) {
            0 -> buildProfileTab(context, dp = ::dp, content = content, accent = accent, p = p)
            1 -> buildFinancialsTab(context, dp = ::dp, content = content, accent = accent, p = p)
            2 -> buildEarningsTab(context, dp = ::dp, content = content, accent = accent, p = p)
            3 -> buildDividendsTab(context, dp = ::dp, content = content, accent = accent, p = p)
        }
    }
    tabs.forEachIndexed { i, label ->
        val tv = TextView(context).apply {
            text = label; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.03f; gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9)); isClickable = true; isFocusable = true
            setOnClickListener { selectTab(i) }
        }
        tabViews += tv
        tabBar.addView(tv, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(4) })
    }

    Thread {
        val fetched = runCatching { OracleRealData.companyProfile(ticker) }.getOrNull()
        Handler(Looper.getMainLooper()).post {
            profile = fetched
            content.removeView(loader)
            selectTab(activeTab)
        }
    }.start()
}

private fun sectionLabel(context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, text: String) {
    content.addView(TextView(context).apply {
        this.text = text; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
        setTextColor(accent); setPadding(0, dp(16), 0, dp(6))
    })
}

private fun kv(context: Context, dp: (Int) -> Int, content: LinearLayout, label: String, value: String) {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
    row.addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.rgb(150, 160, 182)) }, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(TextView(context).apply { text = value; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1.4f))
    content.addView(row)
}

private fun buildProfileTab(context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, p: OracleCompanyProfile) {
    if (p.description != null) {
        sectionLabel(context, dp, content, accent, "ABOUT")
        content.addView(TextView(context).apply { text = p.description; textSize = 12.5f; setTextColor(Color.rgb(210, 216, 230)); setLineSpacing(dp(3).toFloat(), 1f) })
    }
    if (p.sector != null || p.industry != null || p.employees != null || p.address != null || p.website != null) {
        sectionLabel(context, dp, content, accent, "COMPANY")
        p.sector?.let { kv(context, dp, content, "Sector", it) }
        p.industry?.let { kv(context, dp, content, "Industry", it) }
        p.employees?.let { kv(context, dp, content, "Employees", "%,d".format(Locale.US, it)) }
        p.address?.let { kv(context, dp, content, "Headquarters", it) }
        p.website?.let { kv(context, dp, content, "Website", it.removePrefix("https://").removePrefix("http://")) }
    }
    if (p.officers.isNotEmpty()) {
        sectionLabel(context, dp, content, accent, "TOP EXECUTIVES")
        p.officers.forEach { (name, title) ->
            content.addView(TextView(context).apply { text = name; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, 0) })
            if (title.isNotBlank()) content.addView(TextView(context).apply { text = title; textSize = 11.5f; setTextColor(Color.rgb(150, 160, 182)) })
        }
    }
    if (p.description == null && p.officers.isEmpty() && p.sector == null) {
        content.addView(TextView(context).apply { text = "No profile data available."; textSize = 13f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, dp(20), 0, 0) })
    }
}

private fun buildFinancialsTab(context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, p: OracleCompanyProfile) {
    if (p.quarterlyFinancials.isEmpty()) {
        content.addView(TextView(context).apply { text = "No financial history available."; textSize = 13f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, dp(20), 0, 0) })
        return
    }
    sectionLabel(context, dp, content, accent, "REVENUE & NET INCOME \u2014 QUARTERLY")
    content.addView(FinancialsBarChartView(context, p.quarterlyFinancials, accent), LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(6); bottomMargin = dp(12) })
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    head.addView(TextView(context).apply { text = "Period"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)) }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = "Revenue"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = "Net Income"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(head)
    p.quarterlyFinancials.takeLast(8).reversed().forEach { q ->
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
        row.addView(TextView(context).apply { text = q.label; textSize = 12f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply { text = moneyShort(q.revenue); textSize = 12f; setTextColor(Color.rgb(105, 200, 255)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply { text = moneyShort(q.netIncome); textSize = 12f; setTextColor(if ((q.netIncome ?: 0.0) >= 0) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row)
    }
}

private fun buildEarningsTab(context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, p: OracleCompanyProfile) {
    if (p.quarterlyEarnings.isEmpty()) {
        content.addView(TextView(context).apply { text = "No earnings history available."; textSize = 13f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, dp(20), 0, 0) })
        return
    }
    sectionLabel(context, dp, content, accent, "EPS \u2014 ACTUAL VS ESTIMATE")
    content.addView(EarningsBarChartView(context, p.quarterlyEarnings, accent), LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(6); bottomMargin = dp(12) })
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    head.addView(TextView(context).apply { text = "Period"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)) }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = "Actual"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    head.addView(TextView(context).apply { text = "Estimate"; textSize = 10.5f; setTextColor(Color.rgb(150, 160, 182)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
    content.addView(head)
    p.quarterlyEarnings.takeLast(8).reversed().forEach { q ->
        val beat = q.actual != null && q.estimate != null && q.actual >= q.estimate
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, dp(7)) }
        row.addView(TextView(context).apply { text = q.label; textSize = 12f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply { text = q.actual?.let { "%.2f".format(Locale.US, it) } ?: "\u2014"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (q.actual == null) Color.rgb(150, 160, 182) else if (beat) Color.rgb(105, 245, 35) else Color.rgb(255, 120, 120)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply { text = q.estimate?.let { "%.2f".format(Locale.US, it) } ?: "\u2014"; textSize = 12f; setTextColor(Color.rgb(150, 160, 182)); gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(row)
    }
}

private fun buildDividendsTab(context: Context, dp: (Int) -> Int, content: LinearLayout, accent: Int, p: OracleCompanyProfile) {
    if (p.dividendYieldPct == null && p.payoutRatioPct == null && p.dividendRate == null) {
        content.addView(TextView(context).apply { text = "This company does not appear to pay a dividend, or no dividend data is available."; textSize = 13f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, dp(20), 0, 0) })
        return
    }
    sectionLabel(context, dp, content, accent, "SUMMARY")
    p.dividendYieldPct?.let { kv(context, dp, content, "Yield", "%.2f%%".format(Locale.US, it)) }
    p.payoutRatioPct?.let { kv(context, dp, content, "Payout ratio", "%.2f%%".format(Locale.US, it)) }
    p.dividendRate?.let { kv(context, dp, content, "Annualized payout", "%.2f".format(Locale.US, it)) }
    kv(context, dp, content, "Ex-dividend date", p.exDividendDate?.let { java.text.SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(it)) } ?: "\u2014")
}

private fun moneyShort(v: Double?): String {
    if (v == null) return "\u2014"
    val a = kotlin.math.abs(v); val sign = if (v < 0) "-" else ""
    return when {
        a >= 1e9 -> "$sign%.2fB".format(Locale.US, a / 1e9)
        a >= 1e6 -> "$sign%.1fM".format(Locale.US, a / 1e6)
        else -> "$sign%.0f".format(Locale.US, a)
    }
}

/** Simple grouped bar chart: revenue (blue) vs net income (green/red) per quarter. */
private class FinancialsBarChartView(context: Context, private val data: List<OracleQuarterFinancial>, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; textAlign = Paint.Align.CENTER }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 10f; val labelH = 30f
        val shown = data.takeLast(6)
        if (shown.isEmpty()) return
        val maxV = shown.maxOf { kotlin.math.max(it.revenue ?: 0.0, kotlin.math.abs(it.netIncome ?: 0.0)) }.takeIf { it > 0.0 } ?: 1.0
        val slot = (w - 2 * pad) / shown.size
        val zeroY = h - labelH - pad
        shown.forEachIndexed { i, q ->
            val cx = pad + slot * i + slot / 2f
            val bw = slot * 0.28f
            val revH = ((q.revenue ?: 0.0) / maxV * (zeroY - pad)).toFloat().coerceAtLeast(0f)
            paint.color = Color.rgb(70, 150, 230)
            c.drawRect(cx - bw - 3f, zeroY - revH, cx - 3f, zeroY, paint)
            val niH = (kotlin.math.abs(q.netIncome ?: 0.0) / maxV * (zeroY - pad)).toFloat().coerceAtLeast(0f)
            paint.color = if ((q.netIncome ?: 0.0) >= 0) Color.rgb(105, 245, 35) else Color.rgb(255, 90, 90)
            c.drawRect(cx + 3f, zeroY - niH, cx + bw + 3f, zeroY, paint)
            text.color = Color.rgb(150, 160, 182); text.textSize = 16f
            c.drawText(q.label, cx, h - 8f, text)
        }
    }
}

/** Grouped bar chart: EPS actual (green/red by beat/miss) vs estimate (grey). */
private class EarningsBarChartView(context: Context, private val data: List<OracleQuarterEarning>, private val accent: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; textAlign = Paint.Align.CENTER }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val pad = 10f; val labelH = 30f
        val shown = data.takeLast(6)
        if (shown.isEmpty()) return
        val vals = shown.flatMap { listOfNotNull(it.actual, it.estimate) }
        val maxV = (vals.maxOrNull() ?: 1.0).coerceAtLeast(0.01)
        val minV = kotlin.math.min(0.0, vals.minOrNull() ?: 0.0)
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        val slot = (w - 2 * pad) / shown.size
        val zeroY = h - labelH - pad - ((0.0 - minV) / span * (h - labelH - 2 * pad)).toFloat()
        shown.forEachIndexed { i, q ->
            val cx = pad + slot * i + slot / 2f
            val bw = slot * 0.28f
            fun y(v: Double) = h - labelH - pad - ((v - minV) / span * (h - labelH - 2 * pad)).toFloat()
            q.estimate?.let { est ->
                paint.color = Color.rgb(120, 130, 152)
                c.drawRect(cx - bw - 3f, kotlin.math.min(y(est), zeroY), cx - 3f, kotlin.math.max(y(est), zeroY), paint)
            }
            q.actual?.let { act ->
                val beat = q.estimate == null || act >= q.estimate
                paint.color = if (beat) Color.rgb(105, 245, 35) else Color.rgb(255, 90, 90)
                c.drawRect(cx + 3f, kotlin.math.min(y(act), zeroY), cx + bw + 3f, kotlin.math.max(y(act), zeroY), paint)
            }
            text.color = Color.rgb(150, 160, 182)
            c.drawText(q.label, cx, h - 8f, text)
        }
    }
}

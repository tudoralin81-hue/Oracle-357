package ro.alintudor.oracle.nativeui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ro.alintudor.oracle.core.*
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Functional Portfolio module: local positions, journal and model-matched file exports. */
class OraclePortfolioModule(private val host: OracleNativeModule) {
    private val context: Context get() = host.root.context
    private val repo by lazy { OracleRepository(context) }
    private val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun render(positions: List<OraclePosition>) {
        host.content.removeAllViews()
        val data = repo.snapshot()
        val items = OracleAnalytics.normalize(positions)
        host.addCard("PORTFOLIO", "Positions, value, shares, Oracle forecast, real return and indicators")
        if (items.isEmpty()) { host.addCard("NO POSITIONS", "There are no active positions in local memory."); addManagementRow(); return }
        val value = items.sumOf { it.marketValue }
        val invested = items.sumOf { it.shares * it.avgCost }
        val pnl = items.sumOf { it.pnl }
        addHero(value, pnl, if (invested == 0.0) 0.0 else pnl / invested * 100.0, items.size)
        addMetrics(items); addPositionSummary(items); addManagementRow()
        val actions = OracleAnalytics.actions(items, data.history).associateBy { it.ticker }
        val tech = OracleTechnicalIndicators.all(data.history)
        items.sortedByDescending { it.marketValue }.forEachIndexed { i, p -> card(i + 1, p, actions[p.ticker], tech[p.ticker], data.journal) }
        addBottomExports(items, data.journal)
    }

    private fun addHero(value: Double, pnl: Double, pct: Double, count: Int) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(18), host.dp(15), host.dp(18), host.dp(15)); background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(16), Color.rgb(92, 72, 28), host.dp(1)) }
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply { text = "◔"; textSize = 32f; setTextColor(Color.rgb(255, 210, 55)); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(host.dp(45), host.dp(45)))
        row.addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(10), 0, 0, 0); addView(TextView(context).apply { text = "TOTAL PORTFOLIO • $count POSITIONS"; textSize = 11f; setTextColor(Color.rgb(155, 166, 188)) }); addView(TextView(context).apply { text = money(value); textSize = 23f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, host.dp(3), 0, 0) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(context).apply { text = signedPct(pct); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (pnl >= 0) Color.rgb(145, 245, 35) else Color.rgb(255, 80, 65)) })
        box.addView(row)
        box.addView(TextView(context).apply { text = "TOTAL RETURN   ${signedPct(pct)}"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (pnl >= 0) Color.rgb(145, 245, 35) else Color.rgb(255, 80, 65)); setPadding(host.dp(55), host.dp(8), 0, 0) })
        box.addView(TextView(context).apply { text = "P/L  ${money(pnl)}"; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(host.dp(55), host.dp(3), 0, 0) })
        host.content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })
    }

    private fun addMetrics(items: List<OraclePosition>) {
        val row1 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; metric(row1, "WINNERS", items.count { it.pnl > 0 }.toString()); metric(row1, "LOSERS", items.count { it.pnl < 0 }.toString()); host.content.addView(row1)
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; metric(row2, "MAX CONCENTRATION", pct(items.maxOf { it.weight })); metric(row2, "RISK", when { items.maxOf { it.weight } >= 50 -> "HIGH"; items.maxOf { it.weight } >= 35 -> "MEDIUM"; else -> "CONTROLLED" }); host.content.addView(row2)
    }

    private fun addPositionSummary(items: List<OraclePosition>) {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(13), host.dp(11), host.dp(13), host.dp(11)); background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(12), Color.rgb(42, 52, 76), host.dp(1)) }
        box.addView(TextView(context).apply { text = "ACTIVE POSITIONS • TICKER / SHARES / VALUE"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(110, 220, 255)) })
        items.sortedBy { it.ticker }.forEach { p -> val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(7), 0, 0) }; row.addView(TextView(context).apply { text = p.ticker; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(host.dp(62), -2)); row.addView(TextView(context).apply { text = "${shares(p.shares)} shares"; textSize = 12f; setTextColor(Color.rgb(175, 183, 201)) }, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(TextView(context).apply { text = money(p.marketValue) + " ${p.currency}"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(255, 210, 55)) }); box.addView(row) }
        host.content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(8), 0, host.dp(8)) })
    }

    private fun addManagementRow() { val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(host.dp(2), 0, host.dp(2), 0) }; row.addView(btn("+ ADD POSITION", Color.rgb(145, 245, 35)) { addPositionDialog() }, LinearLayout.LayoutParams(-1, host.dp(46))); host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) }) }

    private fun card(rank: Int, p: OraclePosition, a: OracleAction?, t: OracleTechnicalSnapshot?, journal: List<OracleJournalEntry>) {
        val forecast = when (p.ticker.uppercase(Locale.US)) { "CRM" -> 8.1; "HOOD" -> 23.5; "MELI" -> 16.3; else -> journal.filter { it.ticker.equals(p.ticker, true) && it.action.contains("BUY / OPEN", true) }.minByOrNull { it.timestamp }?.score ?: a?.score ?: 0.0 }
        val action = decision(a?.action ?: "HOLD", t)
        val accent = when (action) { "BUY" -> Color.rgb(145, 245, 35); "SELL" -> Color.rgb(255, 80, 95); else -> Color.rgb(50, 220, 190) }
        val reason = when { t == null -> "Insufficient technical data; local monitoring"; t.rsi >= 70 -> "RSI overheating · trend and momentum still acceptable"; t.rsi <= 30 -> "Weak RSI · selling pressure"; action == "BUY" -> "favorable trend and momentum"; action == "SELL" -> "negative signal · rising risk"; else -> "trend and momentum still acceptable" }
        val cardBg = OracleNativeModule.rounded(Color.rgb(6, 10, 20), host.dp(15), accent, host.dp(1))
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(15), host.dp(13), host.dp(12), host.dp(13)); background = cardBg }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply { text = "%02d".format(rank); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent) }, LinearLayout.LayoutParams(host.dp(34), host.dp(30)))
        top.addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(TextView(context).apply { text = p.ticker; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }); addView(TextView(context).apply { text = "${p.company} • ${shares(p.shares)} shares • entry ${money(p.avgCost)}"; textSize = 10f; setTextColor(Color.rgb(155, 166, 188)); setPadding(0, host.dp(2), 0, 0) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        val topAction = TextView(context).apply { text = action; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.CENTER }; top.addView(topAction, LinearLayout.LayoutParams(host.dp(62), host.dp(30))); pulseSignal(topAction, action); c.addView(top)
        c.addView(TextView(context).apply { text = "${money(p.marketValue)} ${p.currency}   •   ${pct(p.weight)} WEIGHT   •   ${shares(p.shares)} SHARES"; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(host.dp(34), host.dp(5), 0, 0) })
        val forecasts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(host.dp(34), host.dp(10), 0, 0) }
        forecasts.addView(valueBox("ORACLE FORECAST", signedPct(forecast), Color.rgb(55, 215, 255)), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, host.dp(4), 0) }); forecasts.addView(valueBox("ACTUAL NOW", signedPct(p.pnlPercent), if (p.pnlPercent >= 0) Color.rgb(65, 225, 135) else Color.rgb(255, 85, 105)), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(host.dp(4), 0, 0, 0) }); c.addView(forecasts)
        val decision = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(15), host.dp(9), host.dp(15), host.dp(9)); background = OracleNativeModule.rounded(Color.rgb(8, 16, 25), host.dp(11), accent, host.dp(1)) }
        val decisionSignal = TextView(context).apply { text = action; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent) }; decision.addView(decisionSignal); pulseSignal(decisionSignal, action); decision.addView(TextView(context).apply { text = reason; textSize = 12f; setTextColor(Color.rgb(190, 198, 215)); setPadding(0, host.dp(4), 0, 0) }); c.addView(decision, LinearLayout.LayoutParams(-1, -2).apply { setMargins(host.dp(34), host.dp(8), 0, 0) })
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(34), host.dp(8), 0, 0) }
        two(grid, "P/L", "${money(p.pnl)} (${signedPct(p.pnlPercent)})", "Score", a?.score?.let { String.format(Locale.US, "%.0f/100", it) } ?: "N/A")
        two(grid, "RSI", t?.rsi?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A", "SMA50", t?.sma50?.takeIf { it.isFinite() && it > 0.0 }?.let { money(it) } ?: "N/A")
        two(grid, "Momentum 5D", t?.momentum5D?.takeIf { it.isFinite() }?.let { signedPct(it) } ?: "N/A", "Momentum 20D", t?.momentum20D?.takeIf { it.isFinite() }?.let { signedPct(it) } ?: "N/A")
        two(grid, "Support 20D", technicalPrice(t?.support20D, p.currentPrice), "Resistance 20D", technicalPrice(t?.resistance20D, p.currentPrice)); c.addView(grid)
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(host.dp(34), host.dp(9), 0, 0) }; buttons.addView(btn("SELL SHARES", Color.rgb(255, 205, 65)) { partialSell(p, forecast) }, LinearLayout.LayoutParams(0, host.dp(43), 1f).apply { setMargins(0, 0, host.dp(4), 0) }); buttons.addView(btn("FULL SELL", Color.rgb(255, 80, 105)) { fullSell(p, forecast) }, LinearLayout.LayoutParams(0, host.dp(43), 1f).apply { setMargins(host.dp(4), 0, 0, 0) }); c.addView(buttons)
        c.addView(TextView(context).apply { text = "Updated locally • ${date.format(Date())}"; textSize = 9f; setTextColor(Color.rgb(105, 120, 145)); setPadding(host.dp(34), host.dp(7), 0, 0) }); host.content.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })
        c.alpha = 0f; c.translationY = host.dp(24).toFloat()
        c.animate().alpha(1f).translationY(0f).setStartDelay((rank - 1) * 90L).setDuration(380L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        // Continuous, clearly-visible pulse on the card border (not just the one-time entrance).
        val strokePx = host.dp(1)
        val ar = Color.red(accent); val ag = Color.green(accent); val ab = Color.blue(accent)
        android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1900L
            startDelay = (rank - 1) * 150L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (!c.isAttachedToWindow) { anim.cancel(); return@addUpdateListener }
                val q = anim.animatedValue as Float
                cardBg.setStroke(strokePx, Color.argb((150 + 105 * q).toInt(), ar, ag, ab))
            }
        }.start()
    }

    private fun technicalPrice(value: Double?, fallback: Double): String { val v = value?.takeIf { it.isFinite() && it > 0.0 } ?: fallback.takeIf { it.isFinite() && it > 0.0 }; return if (v == null) "N/A" else money(v) }
    private fun pulseSignal(view: TextView, action: String) { if (action != "SELL" && action != "HOLD") return; ObjectAnimator.ofFloat(view, "alpha", 1f, 0.38f, 1f).apply { duration = 1150L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART; start() } }
    private fun valueBox(label: String, value: String, color: Int) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(9), host.dp(8), host.dp(9), host.dp(8)); background = OracleNativeModule.rounded(Color.rgb(8, 13, 27), host.dp(10), color, host.dp(1)); addView(TextView(context).apply { text = label; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(155, 166, 188)) }); addView(TextView(context).apply { text = value; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); setPadding(0, host.dp(2), 0, 0) }) }
    private fun two(g: LinearLayout, a: String, av: String, b: String, bv: String) { val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; metric(r, a, av); metric(r, b, bv); g.addView(r) }
    private fun metric(row: LinearLayout, label: String, value: String) { val b = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(13), host.dp(10), host.dp(13), host.dp(10)); background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(11), Color.rgb(35, 44, 66), host.dp(1)) }; b.addView(TextView(context).apply { text = label; textSize = 9f; setTextColor(Color.rgb(145, 155, 176)) }); b.addView(TextView(context).apply { text = value; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, host.dp(3), 0, 0) }); row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(host.dp(2), host.dp(4), host.dp(2), host.dp(5)) }) }
    private fun btn(label: String, color: Int, click: () -> Unit) = TextView(context).apply { text = label; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(color); background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), host.dp(10), color, host.dp(1)); isClickable = true; isFocusable = true; setOnClickListener { click() } }

    private fun addPositionDialog() { val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(4), 0, host.dp(4), 0) }; val ticker = field("TICKER", "CRM"); val company = field("COMPANY", "Salesforce"); val shares = field("NUMBER OF SHARES", "1"); val entry = field("ENTRY PRICE", "100"); val current = field("CURRENT PRICE", "100"); listOf(ticker, company, shares, entry, current).forEach { panel.addView(it.first); panel.addView(it.second) }; AlertDialog.Builder(context).setTitle("ADD POSITION").setMessage("The position is saved locally in Oracle.").setView(panel).setNegativeButton("CANCEL", null).setPositiveButton("ADD") { _, _ -> val t = ticker.second.text.toString().trim().uppercase(Locale.US); val c = company.second.text.toString().trim().ifEmpty { t }; val q = shares.second.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0; val e = entry.second.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0; val cp = current.second.text.toString().replace(',', '.').toDoubleOrNull() ?: e; if (t.isEmpty() || q <= 0.0 || e <= 0.0 || cp <= 0.0) { toast("Invalid position data"); return@setPositiveButton }; val existing = repo.cachedPositions().filterNot { it.ticker.equals(t, true) }.toMutableList(); existing += OracleCalculations.position(t, c, q, e, cp); repo.savePositions(OracleCalculations.withWeights(existing)); val now = System.currentTimeMillis(); repo.saveJournal(repo.cachedJournal() + OracleJournalEntry(now, t, "BUY / OPEN", 0.0, "Position added locally", "ACTIVE", q, e, 0.0, 0.0, q * e, 0.0, 0.0, "manual_$now")); toast("$t added to portfolio"); render(repo.cachedPositions()) }.show() }
    private fun field(label: String, value: String): Pair<TextView, EditText> { val labelView = TextView(context).apply { text = label; textSize = 9f; setTextColor(Color.rgb(145, 155, 176)); setPadding(0, host.dp(5), 0, host.dp(2)) }; val edit = EditText(context).apply { setText(value); setTextColor(Color.WHITE); setSingleLine(true); textSize = 15f; setSelectAllOnFocus(true) }; return labelView to edit }
    private fun partialSell(p: OraclePosition, forecast: Double) { val input = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(shares(p.shares / 2)) }; AlertDialog.Builder(context).setTitle("SELL SHARES • ${p.ticker}").setMessage("This action is local to Oracle; it does not execute broker trades.\n\nQuantity:").setView(input).setNegativeButton("CANCEL", null).setPositiveButton("CONFIRM") { _, _ -> val q = input.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0; if (q <= 0 || q > p.shares) { toast("Invalid quantity"); return@setPositiveButton }; sell(p, q, false, forecast) }.show() }
    private fun fullSell(p: OraclePosition, forecast: Double) { AlertDialog.Builder(context).setTitle("FULL SELL • ${p.ticker}").setMessage("Closes the local position at ${money(p.currentPrice)}. Not sent to the broker.").setNegativeButton("CANCEL", null).setPositiveButton("FULL SELL") { _, _ -> sell(p, p.shares, true, forecast) }.show() }
    private fun sell(p: OraclePosition, q: Double, full: Boolean, forecast: Double) { val now = System.currentTimeMillis(); val old = repo.cachedPositions().filterNot { it.ticker.equals(p.ticker, true) }.toMutableList(); val remain = p.shares - q; if (!full && remain > 0) old += p.copy(shares = remain); repo.savePositions(OracleCalculations.withWeights(old)); val j = repo.cachedJournal().toMutableList(); j += OracleJournalEntry(now, p.ticker, if (full) "SELL (FULL)" else "SELL (PARTIAL)", forecast, if (full) "Local position closed" else "Local partial sale", if (full) "CLOSED" else "ACTIVE", q, p.avgCost, p.currentPrice, if (p.shares <= 0.0) 100.0 else q / p.shares * 100.0, q * p.avgCost, q * p.currentPrice, q * (p.currentPrice - p.avgCost), "sell_$now"); repo.saveJournal(j); toast(if (full) "${p.ticker}: position closed locally" else "${p.ticker}: sale recorded"); render(repo.cachedPositions()) }

    private fun addBottomExports(p: List<OraclePosition>, journal: List<OracleJournalEntry>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(host.dp(2), host.dp(5), host.dp(2), 0) }
        row.addView(btn("DOWNLOAD XLSX", Color.rgb(65, 225, 135)) { saveExcel(p, journal) }, LinearLayout.LayoutParams(0, host.dp(46), 1f).apply { setMargins(0, 0, host.dp(3), 0) })
        row.addView(btn("DOWNLOAD PDF", Color.rgb(255, 205, 65)) { savePdf(p, journal) }, LinearLayout.LayoutParams(0, host.dp(46), 1f).apply { setMargins(host.dp(3), 0, 0, 0) })
        host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private data class ExportRow(val ticker: String, val company: String, val shares: String, val entry: String, val current: String, val value: String, val pnl: String, val pnlPct: String, val weight: String, val status: String)

    private fun exportRows(p: List<OraclePosition>, journal: List<OracleJournalEntry>): List<ExportRow> {
        val active = p.sortedBy { it.ticker }.map { ExportRow(it.ticker, it.company, shares(it.shares), money(it.avgCost), money(it.currentPrice), money(it.marketValue), money(it.pnl), signedPct(it.pnlPercent), pct(it.weight), "ACTIVE") }
        val activeTickers = p.map { it.ticker.uppercase(Locale.US) }.toSet()
        val sold = journal.asSequence().filter { it.status.equals("CLOSED", true) || it.action.contains("SELL (FULL)", true) }.sortedByDescending { it.timestamp }.map { e -> ExportRow(e.ticker, e.ticker, shares(e.shares), money(e.entryPrice), money(e.salePrice), money(e.saleValue), money(e.realizedPnl), if (e.entryValue != 0.0) signedPct(e.realizedPnl / e.entryValue * 100.0) else "0.0%", "0.00%", "SOLD") }.filter { !activeTickers.contains(it.ticker.uppercase(Locale.US)) }.distinctBy { it.ticker.uppercase(Locale.US) }.toList()
        return active + sold
    }

    private fun totalReturn(p: List<OraclePosition>, journal: List<OracleJournalEntry>): Double {
        val activeInvested = p.sumOf { it.shares * it.avgCost }
        val activePnl = p.sumOf { it.pnl }
        val closed = journal.filter { it.status.equals("CLOSED", true) || it.action.contains("SELL (FULL)", true) }
        val realized = closed.sumOf { it.realizedPnl }
        val base = activeInvested + closed.sumOf { it.entryValue }
        return if (base == 0.0) 0.0 else (activePnl + realized) / base * 100.0
    }

    private fun xmlCell(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun xlsxInlineCell(ref: String, value: String, style: Int = 0): String = "<c r=\"$ref\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xmlCell(value)}</t></is></c>"

    private fun saveExcel(p: List<OraclePosition>, journal: List<OracleJournalEntry>) {
        val total = totalReturn(p, journal)
        val rows = exportRows(p, journal)
        saveDownload("oracle_portfolio_${stamp()}.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") { out ->
            val headers = listOf("TICKER", "COMPANY", "SHARES", "ENTRY", "CURRENT / SALE", "VALUE", "P/L", "RETURN", "WEIGHT", "STATUS")
            val sheet = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\" showGridLines=\"1\"/></sheetViews><sheetData>")
                append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\" s=\"1\"><is><t>AI STOCK ORACLE — PORTFOLIO</t></is></c></row>")
                append("<row r=\"2\"><c r=\"A2\" t=\"inlineStr\" s=\"2\"><is><t>TOTAL PORTFOLIO RETURN: ${xmlCell(signedPct(total))}</t></is></c></row>")
                append("<row r=\"3\"><c r=\"A3\" t=\"inlineStr\" s=\"0\"><is><t>Generated ${xmlCell(date.format(Date()))}</t></is></c></row>")
                append("<row r=\"5\">")
                headers.forEachIndexed { i, h -> append(xlsxInlineCell("${('A'.code + i).toChar()}5", h, 3)) }
                append("</row>")
                rows.forEachIndexed { ri, r ->
                    val rr = ri + 6
                    val values = listOf(r.ticker, r.company, r.shares, r.entry, r.current, r.value, r.pnl, r.pnlPct, r.weight, r.status)
                    append("<row r=\"$rr\">")
                    values.forEachIndexed { i, v -> append(xlsxInlineCell("${('A'.code + i).toChar()}$rr", v, if (i == 9) 4 else 0)) }
                    append("</row>")
                }
                append("</sheetData><mergeCells count=2><mergeCell ref=\"A1:J1\"/><mergeCell ref=\"A2:J2\"/></mergeCells><cols>")
                val widths = listOf(12, 24, 12, 14, 18, 16, 14, 14, 12, 12)
                widths.forEachIndexed { i, w -> append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
                append("</cols></worksheet>")
            }
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                entry("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
                entry("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
                entry("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Portfolio\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
                entry("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
                entry("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"4\"><font><sz val=\"11\"/><name val=\"Aptos\"/></font><font><b/><sz val=\"18\"/><name val=\"Aptos Display\"/></font><font><b/><sz val=\"16\"/><color rgb=\"FF70EFFF\"/><name val=\"Aptos Display\"/></font><font><b/><sz val=\"11\"/><name val=\"Aptos\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8EEF8\"/><bgColor indexed=\"64\"/></patternFill></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"5\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"3\" fillId=\"1\" borderId=\"0\"/><xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"center\"/></xf></cellXfs></styleSheet>")
                entry("xl/worksheets/sheet1.xml", sheet)
            }
        }
    }

    private fun savePdf(p: List<OraclePosition>, journal: List<OracleJournalEntry>) {
        val total = totalReturn(p, journal); val rows = exportRows(p, journal)
        saveDownload("oracle_portfolio_${stamp()}.pdf", "application/pdf") { out ->
            val doc = PdfDocument(); val pageW = 595f; val pageH = 842f; val margin = 22f
            val widths = floatArrayOf(46f, 82f, 45f, 58f, 62f, 62f, 52f, 52f, 48f, 52f)
            val rp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 7f; typeface = Typeface.DEFAULT }
            var pageNo = 1; var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNo).create()); var canvas = page.canvas
            fun header() { rp.typeface = Typeface.DEFAULT_BOLD; rp.textSize = 15f; canvas.drawText("AI STOCK ORACLE — PORTFOLIO", margin, 27f, rp); rp.textSize = 19f; rp.color = if (total >= 0) Color.rgb(30, 150, 80) else Color.rgb(210, 55, 70); canvas.drawText("TOTAL RETURN: ${signedPct(total)}", margin, 50f, rp); rp.color = Color.rgb(15, 23, 42); rp.typeface = Typeface.DEFAULT; rp.textSize = 7f; canvas.drawText("Generated ${date.format(Date())}", margin, 64f, rp) }
            header(); var y = 80f; val headers = listOf("TICKER", "COMPANY", "SHARES", "ENTRY", "CURRENT/SALE", "VALUE", "P/L", "RET.", "WT.", "STATUS")
            fun drawRow(row: List<String>, headerRow: Boolean) { var x = margin; val h = if (headerRow) 24f else 21f; val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = if (headerRow) Color.rgb(210, 220, 235) else Color.rgb(246, 248, 251); style = android.graphics.Paint.Style.FILL }; rp.typeface = if (headerRow) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; rp.textSize = if (headerRow) 6.3f else 6.8f; rp.color = Color.rgb(15,23,42); row.forEachIndexed { i, v -> canvas.drawRect(x, y, x + widths[i], y + h, bg); canvas.drawText(v.take(if (headerRow) 13 else 18), x + 2f, y + if (headerRow) 15f else 14f, rp); x += widths[i] }; y += h }
            drawRow(headers, true)
            rows.forEach { r -> if (y > pageH - 45f) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNo).create()); canvas = page.canvas; header(); y = 80f; drawRow(headers, true) }; drawRow(listOf(r.ticker, r.company, r.shares, r.entry, r.current, r.value, r.pnl, r.pnlPct, r.weight, r.status), false) }
            rp.typeface = Typeface.DEFAULT_BOLD; rp.textSize = 7f; canvas.drawText("TOTAL PORTFOLIO RETURN: ${signedPct(total)}", margin, pageH - 20f, rp); doc.finishPage(page); doc.writeTo(out); doc.close()
        }
    }

    private fun saveDownload(fileName: String, mime: String, writer: (OutputStream) -> Unit): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, fileName); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Oracle"); put(MediaStore.Downloads.IS_PENDING, 1) }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create the file in Downloads")
            try { context.contentResolver.openOutputStream(uri)?.use(writer) ?: error("Could not write the file"); context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) } catch (e: Exception) { context.contentResolver.delete(uri, null, null); throw e }
        } else { val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir; dir.mkdirs(); File(dir, fileName).outputStream().use(writer) }
        toast("Saved: $fileName"); true
    }.onFailure { toast("Export failed: ${it.message ?: it.javaClass.simpleName}") }.getOrDefault(false)

    private fun decision(action: String, t: OracleTechnicalSnapshot?) = when { (t?.rsi?.takeIf { it.isFinite() } ?: 50.0) >= 70.0 -> "HOLD"; action == "BUY" -> "BUY"; action == "SELL" -> "SELL"; else -> "HOLD" }
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun money(v: Double) = String.format(Locale.US, "%,.2f", v)
    private fun pct(v: Double) = String.format(Locale.US, "%.2f%%", v)
    private fun signedPct(v: Double) = String.format(Locale.US, "%+.1f%%", v)
    private fun shares(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(Locale.US, "%.2f", v)
    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_LONG).show()
}

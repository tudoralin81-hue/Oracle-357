package ro.alintudor.luxoculi.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Persistent append-only Growth recommendation journal. */
class OracleGrowthJournalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("oracle_growth_journal", Context.MODE_PRIVATE)
    private val zone = TimeZone.getTimeZone("Europe/Bucharest")
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")).apply { timeZone = zone }

    @Synchronized
    fun record(items: List<OracleGrowthRecommendation>) {
        if (items.isEmpty()) return
        val current = load().toMutableList()
        val keys = current.mapTo(HashSet()) { key(it) }
        items.forEach { if (keys.add(key(it))) current.add(it) }
        current.sortWith(compareBy<OracleGrowthRecommendation> { it.referenceTimestamp }.thenBy { horizonOrder(it.horizon) }.thenBy { it.ticker })
        save(current.takeLast(5000))
    }

    fun load(): List<OracleGrowthRecommendation> = parse(prefs.getString("entries", "[]") ?: "[]")

    @Synchronized
    fun clear() { prefs.edit().remove("entries").apply() }

    fun exportPdf(): String? {
        // Export the complete journal, including historical recommendations from 01.09.2026 onward.
        val cutoff = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")).apply { timeZone = zone }
            .parse("01.09.2026 00:00")?.time ?: 0L
        val entries = load().filter { it.referenceTimestamp >= cutoff }
        if (entries.isEmpty()) return null

        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 34f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 20f }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 10f }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = margin

        fun newPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = margin
            canvas.drawText("LUX OCULI GROWTH • FULL JOURNAL", margin, y, titlePaint)
            y += 28f
        }

        canvas.drawText("LUX OCULI GROWTH • FULL JOURNAL", margin, y, titlePaint)
        y += 18f
        canvas.drawText("Export PDF • ${dateFormat.format(Date())}", margin, y, mutedPaint)
        y += 24f

        val colX = floatArrayOf(margin, 88f, 155f, 205f, 262f, 326f, 392f, 458f)
        val headers = arrayOf("T0", "Ticker", "Horizon", "Score", "Signal", "Risk", "Allocation", "Forecast")
        headers.forEachIndexed { i, h -> canvas.drawText(h, colX[i], y, headerPaint) }
        y += 8f
        canvas.drawLine(margin, y, pageWidth - margin, y, mutedPaint)
        y += 16f

        entries.sortedByDescending { it.referenceTimestamp }.forEach { item ->
            if (y > pageHeight - 54f) newPage()
            val values = arrayOf(
                dateFormat.format(Date(item.referenceTimestamp)), item.ticker, item.horizon,
                "${item.score}/100", item.signal.replace(" ", "\n"), item.risk,
                "${format(item.allocationMax)}%", signed(item.forecastPct)
            )
            values.forEachIndexed { i, value -> canvas.drawText(value.take(15), colX[i], y, bodyPaint) }
            y += 13f
            canvas.drawText("${item.company} • ${item.sector} • Momentum 5D ${signed(item.momentum5D)} / 20D ${signed(item.momentum20D)} • Src: ${if (item.computedLocally) "L" else "S"}", margin, y, mutedPaint)
            y += 15f
        }

        document.finishPage(page)
        return runCatching {
            val filename = "LuxOculi_Growth_Journal_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.pdf"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
                context.contentResolver.openOutputStream(uri).use { out -> document.writeTo(out!!) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri.toString()
            } else {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching null
                val file = File(dir, filename)
                FileOutputStream(file).use { document.writeTo(it) }
                file.absolutePath
            }
        }.also { document.close() }.getOrNull()
    }

    private fun save(items: List<OracleGrowthRecommendation>) {
        val json = org.json.JSONArray().apply {
            items.forEach { item ->
                put(org.json.JSONObject().apply {
                    put("horizon", item.horizon); put("ticker", item.ticker); put("company", item.company); put("sector", item.sector)
                    put("score", item.score); put("signal", item.signal); put("risk", item.risk); put("allocationMax", item.allocationMax)
                    put("forecastPct", item.forecastPct); put("momentum5D", item.momentum5D); put("momentum20D", item.momentum20D)
                    put("referencePrice", (item.referencePrice ?: item.currentPrice)?.takeIf { it > 0.0 } ?: org.json.JSONObject.NULL)
                    put("weights", org.json.JSONArray().apply { item.weights.forEach { put(it) } })
                    put("newsTitle", item.newsTitle); put("newsSource", item.newsSource); put("referenceTimestamp", item.referenceTimestamp)
                    put("computedLocally", item.computedLocally)
                })
            }
        }
        prefs.edit().putString("entries", json.toString()).apply()
    }

    private fun parse(s: String): List<OracleGrowthRecommendation> = runCatching {
        val a = org.json.JSONArray(s)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val w = o.optJSONArray("weights") ?: org.json.JSONArray()
            OracleGrowthRecommendation(
                o.optString("horizon"), o.optString("ticker"), o.optString("company"), o.optString("sector"),
                o.optInt("score"), o.optString("signal"), o.optString("risk"), o.optDouble("allocationMax"),
                o.optDouble("forecastPct"), o.optDouble("momentum5D"), o.optDouble("momentum20D"),
                List(w.length()) { n -> w.optInt(n) }, o.optString("newsTitle"), o.optString("newsSource"), o.optLong("referenceTimestamp"),
                referencePrice = if (o.isNull("referencePrice")) null else o.optDouble("referencePrice").takeIf { it.isFinite() && it > 0.0 },
                computedLocally = o.optBoolean("computedLocally", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun key(item: OracleGrowthRecommendation) = "${item.referenceTimestamp}|${item.horizon.uppercase(Locale.US)}|${item.ticker.uppercase(Locale.US)}"
    private fun horizonOrder(h: String) = when (h.uppercase(Locale.US)) { "SHORT" -> 0; "MEDIUM" -> 1; else -> 2 }
    private fun format(v: Double) = "%.1f".format(Locale.US, v)
    private fun signed(v: Double) = if (v >= 0) "+${format(v)}%" else "${format(v)}%"
}

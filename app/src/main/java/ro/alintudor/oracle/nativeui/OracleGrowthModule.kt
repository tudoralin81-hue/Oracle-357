package ro.alintudor.oracle.nativeui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import ro.alintudor.oracle.core.OracleDemo
import ro.alintudor.oracle.core.OracleGrowthEngine
import ro.alintudor.oracle.core.OracleGrowthJournalStore
import ro.alintudor.oracle.core.OracleGrowthPhase
import ro.alintudor.oracle.core.OracleGrowthProgress
import ro.alintudor.oracle.core.OracleGrowthRecommendation
import ro.alintudor.oracle.core.OracleLoaderQuotes
import ro.alintudor.oracle.core.OracleNews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Growth module. Visual structure follows the approved mobile Growth mockup,
 * while values remain the persisted Oracle snapshot and are never recalculated here.
 */
class OracleGrowthModule(private val host: OracleNativeModule) {
    private val bg = Color.rgb(6, 10, 20)
    private val panel = Color.rgb(7, 14, 28)
    private val border = Color.rgb(49, 82, 125)
    private val muted = Color.rgb(165, 174, 195)
    private val cyan = Color.rgb(75, 225, 255)
    private val orange = Color.rgb(255, 160, 25)
    private val green = Color.rgb(105, 245, 35)
    private val red = Color.rgb(255, 80, 90)
    private val white = Color.WHITE
    private val journalStore = OracleGrowthJournalStore(host.root.context)

    fun render(items: List<OracleGrowthRecommendation>, fallbackNews: List<OracleNews> = emptyList()) {
        host.content.removeAllViews()
        if (items.isEmpty()) {
            addLoadingState()
            return
        }

        // GrowthBanner from the shared module shell is the single Growth hero.
        // Do not add a second Growth banner here.
        journalStore.record(items)
        addRegimeBanner(items)
        addSummary(items)

        val ordered = listOf("SHORT", "MEDIUM", "LONG").mapNotNull { horizon ->
            items.firstOrNull { it.horizon.equals(horizon, true) }
        }
        if (ordered.isNotEmpty()) addRecommendations(ordered, fallbackNews)
        addNews(ordered, fallbackNews)
        addHistory(journalStore.load())
    }

    // B540 — investor quotes rotated in the loader every 15s (Requirement #7).
    // Shared with the app boot loader via OracleLoaderQuotes so both use the
    // exact same pool. Local strings only; no network request is made to show them.
    private val loaderQuotes = OracleLoaderQuotes.ALL

    /**
     * B540 loading state (Requirement #6/#7/#11).
     *
     * Shows real progress ("DATA LOADED: X%", updated in steps of 50),
     * an ETA computed from actual throughput, and a rotating investor quote.
     * If [OracleGrowthEngine] has already finished with zero OHLCV received,
     * this renders an explicit error state instead — it never spins forever.
     */
    private fun addLoadingState() {
        val initial = OracleGrowthEngine.growthProgress()
        if (initial.phase == OracleGrowthPhase.NO_DATA) {
            addNoDataState(initial)
            return
        }

        val card = card(18)
        card.gravity = Gravity.CENTER
        val spinner = ImageView(host.root.context).apply {
            setImageResource(ro.alintudor.oracle.R.drawable.ic_oracle)
            contentDescription = "Oracle is calculating"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val rotation = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1100L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            rotation.start()
        }
        card.addView(spinner, LinearLayout.LayoutParams(host.dp(54), host.dp(54)).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 10).apply { gravity = Gravity.CENTER })
        card.addView(text("Calculating recommendations…", 13f, Typeface.DEFAULT, muted, 0, 5).apply { gravity = Gravity.CENTER })
        val progressLabel = text("DATA LOADED: 0%", 12f, Typeface.DEFAULT_BOLD, cyan, 0, 10).apply { gravity = Gravity.CENTER }
        card.addView(progressLabel)
        val progressBar = ProgressBar(host.root.context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = initial.total.coerceAtLeast(1); progress = 0; isIndeterminate = false
        }
        card.addView(progressBar, LinearLayout.LayoutParams(-1, host.dp(9)).apply { setMargins(host.dp(10), host.dp(6), host.dp(10), host.dp(3)) })
        val etaLabel = text("Estimated time: calculating…", 10f, Typeface.DEFAULT_BOLD, green, 0, 5).apply { gravity = Gravity.CENTER }
        card.addView(etaLabel)
        val quoteLabel = text(loaderQuotes.first(), 10f, Typeface.DEFAULT, white, 0, 9).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }
        card.addView(quoteLabel)
        card.addView(text("Analysis runs in the background. Values appear only once the current calculation finishes.", 9f, Typeface.DEFAULT, muted, 0, 9).apply { gravity = Gravity.CENTER })
        card.addView(text("Target maximum: 20 seconds", 9f, Typeface.DEFAULT_BOLD, muted, 0, 6).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(390)).apply { setMargins(0, 0, 0, host.dp(10)) })

        val handler = Handler(Looper.getMainLooper())
        var quoteIndex = 0
        val quoteRunnable = object : Runnable {
            override fun run() {
                quoteIndex = (quoteIndex + 1) % loaderQuotes.size
                quoteLabel.text = loaderQuotes[quoteIndex]
                handler.postDelayed(this, 15_000L)
            }
        }
        handler.postDelayed(quoteRunnable, 15_000L)

        val progressRunnable = object : Runnable {
            override fun run() {
                val p = OracleGrowthEngine.growthProgress()
                if (p.phase == OracleGrowthPhase.NO_DATA) {
                    handler.removeCallbacksAndMessages(null)
                    addNoDataState(p)
                    return
                }
                val total = p.total.coerceAtLeast(1)
                val loaded = p.loaded.coerceIn(0, total)
                // Requirement #6: the visible counter steps in increments of 50;
                // the engine tracks the exact count internally.
                val shown = if (loaded >= total) total else (loaded / 50) * 50
                progressBar.max = total
                progressBar.progress = shown
                val pct = (shown * 100 / total).coerceIn(0, 100)
                progressLabel.text = "DATA LOADED: $pct%"
                if (p.startedAtNanos > 0L) {
                    val elapsed = (System.nanoTime() - p.startedAtNanos).coerceAtLeast(1L) / 1_000_000_000.0
                    etaLabel.text = if (p.phase == OracleGrowthPhase.RUNNING) {
                        if (shown > 0) {
                            val eta = (elapsed * (total - shown) / shown).coerceAtLeast(0.0)
                            "Estimated time: ~${formatEta(eta)}"
                        } else "Estimated time: calculating…"
                    } else {
                        "Data analysis: finished in ${String.format(Locale.US, "%.1f", elapsed)} s"
                    }
                }
                if (p.phase == OracleGrowthPhase.RUNNING) handler.postDelayed(this, 500L)
            }
        }
        handler.post(progressRunnable)
    }

    /** Requirement #6/#11: explicit, non-infinite error state when 0 OHLCV was received. */
    private fun addNoDataState(progress: OracleGrowthProgress) {
        host.content.removeAllViews()
        val card = card(18)
        card.gravity = Gravity.CENTER
        card.background = rounded(bg, red, 1, 16)
        card.addView(text("⚠", 28f, Typeface.DEFAULT_BOLD, red, 0, 0).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 10).apply { gravity = Gravity.CENTER })
        card.addView(text("The OHLCV data source did not respond.", 13f, Typeface.DEFAULT_BOLD, red, 0, 6).apply { gravity = Gravity.CENTER })
        card.addView(text("Growth recommendations could not be calculated (${progress.loaded} / ${progress.total} symbols received).", 11f, Typeface.DEFAULT, muted, 0, 6).apply { gravity = Gravity.CENTER })
        card.addView(text("Tap ↻ (top right) to retry.", 11f, Typeface.DEFAULT_BOLD, cyan, 0, 6).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(200)).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun formatEta(seconds: Double): String {
        val rounded = kotlin.math.ceil(seconds).toInt().coerceAtLeast(0)
        return if (rounded < 60) "$rounded sec" else "${rounded / 60} min ${rounded % 60} sec"
    }

    /** One line, only when it matters: the ranking is relative, this says
     *  whether the market as a whole is with it. */
    private fun addRegimeBanner(items: List<OracleGrowthRecommendation>) {
        val first = items.firstOrNull() ?: return
        val level = first.marketRegime.uppercase(Locale.US)
        if (level == "NORMAL" || first.regimeNote.isBlank()) return
        val color = if (level == "DEFENSIVE") Color.rgb(255, 80, 95) else Color.rgb(255, 170, 40)
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(host.dp(14), host.dp(11), host.dp(14), host.dp(11))
            background = OracleNativeModule.rounded(Color.rgb(12, 8, 10), host.dp(12), color, host.dp(1))
        }
        box.addView(text("MARKET REGIME: $level", 12f, Typeface.DEFAULT_BOLD, color, 0, 0))
        box.addView(text(first.regimeNote, 11f, Typeface.DEFAULT, Color.rgb(205, 210, 222), 0, 4))
        host.content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun addSummary(items: List<OracleGrowthRecommendation>) {
        val card = card(14)
        card.addView(text("GROWTH RECOMMENDATIONS", 18f, Typeface.DEFAULT_BOLD, green, 0, 0))
        card.addView(text("Oracle Growth • daily snapshot 16:00", 13f, Typeface.DEFAULT, muted, 0, 5))
        val line = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.dp(12), 0, 0)
        }
        line.addView(metric("HORIZONS", items.map { it.horizon }.distinct().size.toString(), cyan), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(metric("RECOMMENDATIONS", items.size.toString(), orange), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(metric("ANCHOR", formatT0(items.first().referenceTimestamp), white), LinearLayout.LayoutParams(0, -2, 1.55f))
        card.addView(line)
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun addRecommendations(items: List<OracleGrowthRecommendation>, news: List<OracleNews>) {
        val section = TextView(host.root.context).apply {
            text = "ACTIVE RECOMMENDATIONS SUMMARY"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(host.dp(4), host.dp(3), host.dp(4), host.dp(7))
        }
        host.content.addView(section)
        items.forEachIndexed { index, item -> addRecommendationCard(item, news, index) }
    }

    private fun addRecommendationCard(item: OracleGrowthRecommendation, news: List<OracleNews>, index: Int = 0) {
        val accent = when (item.horizon.uppercase(Locale.US)) { "SHORT" -> cyan; "MEDIUM" -> orange; else -> green }
        val cardBg = rounded(bg, accent, 1, 15)
        val card = card(12).apply { background = cardBg }
        val top = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        val horizonLabelView = text(horizonLabel(item.horizon), 13f, Typeface.DEFAULT_BOLD, accent, 0, 0)
        left.addView(horizonLabelView)
        left.addView(text(horizonRange(item.horizon), 11f, Typeface.DEFAULT, muted, 0, 3))
        top.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text(formatT0(item.referenceTimestamp), 10f, Typeface.DEFAULT, muted, 0, 0))
        card.addView(top)

        val identity = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(10), 0, host.dp(8)) }
        val ticker = text(item.ticker, 30f, Typeface.DEFAULT_BOLD, white, 0, 0)
        identity.addView(ticker, LinearLayout.LayoutParams(host.dp(120), -2))
        val company = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        company.addView(text(item.company, 15f, Typeface.DEFAULT_BOLD, white, 0, 0))
        company.addView(text(item.sector, 11f, Typeface.DEFAULT_BOLD, Color.rgb(150, 170, 205), 0, 4))
        identity.addView(company, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(identity)
        card.addView(divider())

        val metrics = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, host.dp(7), 0, host.dp(4)) }
        val demo = OracleDemo.active(host.root.context)
        metrics.addView(metric("SCORE", if (demo) OracleDemo.LOCK else "${item.score}/100", cyan), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("SIGNAL", compactSignal(item.signal), signalColor(item.signal)), LinearLayout.LayoutParams(0, -2, 1.15f))
        metrics.addView(metric("RISK", item.risk, riskColor(item.risk)), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric("ALLOCATION", if (demo) OracleDemo.LOCK else "${format(item.allocationMax)}%", orange), LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(metrics)

        val lower = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(5), 0, host.dp(4)) }
        val forecast = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        // Honest label: this number is the ATR-based expected range for the
        // horizon (2×/4.5×/8×ATR), not a prediction of where price will go.
        forecast.addView(text("Expected range (ATR)", 10f, Typeface.DEFAULT, muted, 0, 0))
        forecast.addView(text(if (demo) OracleDemo.LOCK else signedPct(item.forecastPct), 22f, Typeface.DEFAULT_BOLD, green, 0, 2))
        item.earningsInDays?.takeIf { it <= 14 }?.let { d ->
            forecast.addView(text(if (d == 0) "Earnings today" else "Earnings in $d day${if (d == 1) "" else "s"}", 10f, Typeface.DEFAULT_BOLD, orange, 0, 2))
        }
        lower.addView(forecast, LinearLayout.LayoutParams(0, -2, 1.15f))
        val momentum = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        momentum.addView(text("Momentum", 10f, Typeface.DEFAULT, muted, 0, 0))
        momentum.addView(text("5D: ${signedPct(item.momentum5D)}", 11f, Typeface.DEFAULT_BOLD, cyan, 0, 2))
        momentum.addView(text("20D: ${signedPct(item.momentum20D)}", 11f, Typeface.DEFAULT_BOLD, cyan, 0, 2))
        lower.addView(momentum, LinearLayout.LayoutParams(0, -2, 1.1f))
        lower.addView(SparklineView(host.root.context, accent), LinearLayout.LayoutParams(host.dp(112), host.dp(52)))
        card.addView(lower)
        addCompactWeights(card, item.weights)

        val linked = news.firstOrNull { it.ticker.equals(item.ticker, true) }
        val newsTitle = if (item.newsTitle.isNotBlank()) item.newsTitle else linked?.title.orEmpty()
        val source = if (item.newsSource.isNotBlank()) item.newsSource else linked?.source.orEmpty()
        if (newsTitle.isNotBlank()) {
            card.addView(text("▣  ${if (source.isBlank()) "NEWS" else source}", 10f, Typeface.DEFAULT_BOLD, cyan, 0, 5))
            card.addView(text(newsTitle, 11f, Typeface.DEFAULT, white, 0, 4))
        }
        card.addView(text("This data is informational and does not constitute investment advice.", 9f, Typeface.DEFAULT, Color.rgb(125, 135, 155), 0, 8))
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })

        // Entrance: fade + rise in, staggered per card so they don't all pop at once.
        card.alpha = 0f
        card.translationY = host.dp(26).toFloat()
        card.animate().alpha(1f).translationY(0f).setStartDelay(index * 120L).setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()

        // Continuous subtle pulse on the accent border, so the cards feel alive.
        val strokePx = host.dp(1)
        val r = Color.red(accent); val g = Color.green(accent); val b = Color.blue(accent)
        val pulse = android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1900L
            startDelay = index * 220L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (!card.isAttachedToWindow) { anim.cancel(); return@addUpdateListener }
                val q = anim.animatedValue as Float
                cardBg.setStroke((strokePx * (1f + 0.7f * q)).toInt().coerceAtLeast(1), Color.argb((150 + 105 * q).toInt(), r, g, b))
            }
        }
        pulse.start()

        // Slow pulse on the SHORT/MEDIUM/LONG TERM label.
        val horizonPulse = android.animation.ValueAnimator.ofFloat(0.55f, 1f, 0.55f).apply {
            duration = 2600L
            startDelay = index * 180L
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (!card.isAttachedToWindow) { anim.cancel(); return@addUpdateListener }
                horizonLabelView.alpha = anim.animatedValue as Float
            }
        }
        horizonPulse.start()
    }

    private fun addCompactWeights(parent: LinearLayout, weights: List<Int>) {
        if (weights.isEmpty()) return
        parent.addView(text("Weights", 10f, Typeface.DEFAULT_BOLD, white, 0, 5))
        val names = listOf("News", "BO", "Trend", "Mom", "Vol", "S/R", "Fund", "BB", "Ichimoku", "Mkt", "R/R", "ADX")
        val maxWeight = weights.maxOrNull()?.takeIf { it > 0 } ?: 1
        val grid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, host.dp(2), 0, host.dp(1)) }
        val columns = 6
        for (r in 0 until 2) {
            val row = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for (c in 0 until columns) {
                val i = r * columns + c
                val cell = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2)) }
                cell.addView(text(names[i], 8f, Typeface.DEFAULT, muted, 0, 0))
                addWeightBar(cell, weights.getOrNull(i) ?: 0, maxWeight)
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            grid.addView(row)
        }
        parent.addView(grid)
    }

    /** A 5-segment horizontal bar (20% per segment) standing in for the raw
     *  weight number — filled proportionally to this factor's importance
     *  relative to the strongest factor for the horizon, colored by that
     *  same proportion (green = high, orange = medium, red = low). */
    private fun addWeightBar(parent: LinearLayout, value: Int, maxValue: Int) {
        val pct = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        val filledSegments = kotlin.math.round(pct * 5f).toInt().coerceIn(if (value > 0) 1 else 0, 5)
        val color = when {
            pct >= 0.6f -> green
            pct >= 0.3f -> orange
            else -> red
        }
        val bar = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, host.dp(3), 0, 0)
        }
        for (seg in 0 until 5) {
            val filled = seg < filledSegments
            val segView = android.view.View(host.root.context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (filled) color else Color.TRANSPARENT)
                    cornerRadius = host.dp(1).toFloat()
                    if (!filled) setStroke(host.dp(1), Color.rgb(60, 68, 84))
                }
            }
            bar.addView(segView, LinearLayout.LayoutParams(host.dp(7), host.dp(9)).apply { if (seg < 4) marginEnd = host.dp(2) })
        }
        parent.addView(bar)
    }

    private fun addNews(items: List<OracleGrowthRecommendation>, fallbackNews: List<OracleNews>) {
        fun isCleanTitle(title: String) = title.isNotBlank() && !title.contains("Google News", true) && !title.contains(" when:", true)
        val recent = items.mapNotNull { item ->
            val n = fallbackNews.firstOrNull { it.ticker.equals(item.ticker, true) && isCleanTitle(it.title) }
            if (n != null) n else if (isCleanTitle(item.newsTitle)) OracleNews(item.ticker, item.newsTitle, item.newsSource, "", item.referenceTimestamp, false) else null
        }.distinctBy { it.ticker }
        if (recent.isEmpty()) return
        val card = card(12)
        card.addView(text("RECENT NEWS & CATALYSTS", 15f, Typeface.DEFAULT_BOLD, green, 0, 0))
        recent.forEach { n ->
            card.addView(text("▣  ${n.title}", 11f, Typeface.DEFAULT, white, 0, 7))
            card.addView(text("${formatT0(n.publishedAt)} • ${n.source}", 9f, Typeface.DEFAULT, muted, host.dp(18), 2))
        }
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) })
    }

    private fun addHistory(entries: List<OracleGrowthRecommendation>) {
        val card = card(12)
        val header = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("LATEST RECOMMENDATIONS", 15f, Typeface.DEFAULT_BOLD, cyan, 0, 0), LinearLayout.LayoutParams(-2, -2))

        // Arrow sits right next to the title. Collapsed by default (nothing shown);
        // hidden entirely only if there are no recent recommendations at all.
        val arrow = TextView(host.root.context).apply {
            text = "⌄"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(host.dp(4), 0, 0, host.dp(2))
            isClickable = true
            isFocusable = true
        }
        header.addView(arrow, LinearLayout.LayoutParams(host.dp(38), host.dp(40)))

        // Flexible spacer pushes the PDF button to the far right.
        header.addView(View(host.root.context), LinearLayout.LayoutParams(0, -2, 1f))

        val download = TextView(host.root.context).apply {
            text = "⇩  PDF"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(8, 15, 28), cyan, 1, 10)
            setPadding(host.dp(13), host.dp(8), host.dp(13), host.dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Download Growth journal as PDF"
            setOnClickListener {
                val path = journalStore.exportPdf()
                if (path != null) Toast.makeText(host.root.context, "Growth journal saved to Downloads.", Toast.LENGTH_LONG).show()
                else Toast.makeText(host.root.context, "There are no recommendations to export.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(download, LinearLayout.LayoutParams(host.dp(94), host.dp(40)))
        card.addView(header)

        val all = entries
            .filter { it.referenceTimestamp > 0L && it.referenceTimestamp >= startHistoryTimestamp() }
            .sortedWith(compareByDescending<OracleGrowthRecommendation> { it.referenceTimestamp }
                .thenBy { horizonOrder(it.horizon) }.thenBy { it.ticker })
            .take(15)
        val rows = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        card.addView(rows)

        if (all.isEmpty()) {
            arrow.visibility = View.GONE
            rows.visibility = View.VISIBLE
            rows.addView(historyRow(null), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
            host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
            return
        }

        all.forEach { item ->
            rows.addView(historyRow(item), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }
        var expanded = false
        val toggle: () -> Unit = {
            expanded = !expanded
            arrow.text = if (expanded) "⌃" else "⌄"
            rows.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        arrow.setOnClickListener { toggle() }
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun historyRow(item: OracleGrowthRecommendation?): LinearLayout {
        val accent = item?.let { when (it.horizon.uppercase(Locale.US)) { "SHORT" -> cyan; "MEDIUM" -> orange; else -> green } } ?: Color.rgb(60, 70, 90)
        return LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(10), host.dp(8), host.dp(10), host.dp(8))
            background = rounded(bg, accent, 1, 11)
            if (item == null) {
                val placeholderTop = LinearLayout(host.root.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                placeholderTop.addView(text("—", 18f, Typeface.DEFAULT_BOLD, muted, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
                addView(placeholderTop)
                addView(text("No recommendations yet", 10f, Typeface.DEFAULT, muted, 0, 2))
            } else {
                val top = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(text(item.ticker, 16f, Typeface.DEFAULT_BOLD, white, 0, 0), LinearLayout.LayoutParams(host.dp(72), -2))
                val identity = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
                identity.addView(text(item.company.ifBlank { "—" }, 11f, Typeface.DEFAULT_BOLD, white, 0, 0))
                identity.addView(text(item.sector.ifBlank { "—" }, 9f, Typeface.DEFAULT_BOLD, Color.rgb(150, 170, 205), 0, 2))
                top.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
                top.addView(text(item.horizon, 9f, Typeface.DEFAULT_BOLD, accent, 0, 0))
                addView(top)
                val details = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(5), 0, 0) }
                details.addView(text(formatT0(item.referenceTimestamp), 9f, Typeface.DEFAULT, muted, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(text("Forecast ${signedPct(item.forecastPct)}", 9f, Typeface.DEFAULT_BOLD, green, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(text("Score ${item.score}/100", 9f, Typeface.DEFAULT_BOLD, cyan, 0, 0), LinearLayout.LayoutParams(0, -2, .8f))
                addView(details)
            }
        }
    }

    private fun startHistoryTimestamp(): Long {
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO"))
        f.timeZone = TimeZone.getTimeZone("Europe/Bucharest")
        return f.parse("01.09.2026 00:00")?.time ?: 0L
    }

    private fun horizonOrder(horizon: String) = when (horizon.uppercase(Locale.US)) { "SHORT" -> 0; "MEDIUM" -> 1; else -> 2 }
    private fun horizonLabel(horizon: String) = when (horizon.uppercase(Locale.US)) { "SHORT" -> "●  SHORT TERM"; "MEDIUM" -> "●  MEDIUM TERM"; else -> "●  LONG TERM" }
    private fun horizonRange(horizon: String) = when (horizon.uppercase(Locale.US)) { "SHORT" -> "1–10 trading days"; "MEDIUM" -> "2–12 weeks"; else -> "3–12 months" }
    private fun compactSignal(signal: String) = signal.replace("STRONG ", "STRONG\n").trim()
    private fun signalColor(signal: String): Int {
        val s = signal.uppercase(Locale.US)
        return when {
            s.contains("STRONG BUY") || s == "BUY" -> green
            s.contains("AVOID") || s.contains("SELL") -> red
            else -> orange // HOLD, WATCH
        }
    }
    private fun riskColor(risk: String): Int {
        val r = risk.uppercase(Locale.US)
        return when {
            r.contains("HIGH") -> red
            r.contains("LOW") -> green
            else -> orange // MEDIUM
        }
    }
    private fun signedPct(v: Double) = if (v >= 0) "+${format(v)}%" else "${format(v)}%"
    private fun format(v: Double) = "%.1f".format(Locale.US, v)

    private fun formatT0(timestamp: Long): String {
        if (timestamp <= 0L) return "—"
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")); f.timeZone = TimeZone.getTimeZone("Europe/Bucharest"); return f.format(Date(timestamp))
    }

    private fun card(pad: Int): LinearLayout = LinearLayout(host.root.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(host.dp(pad), host.dp(pad), host.dp(pad), host.dp(pad))
        background = rounded(panel, border, 1, 16)
    }

    private fun text(value: String, size: Float, face: Typeface, color: Int, left: Int, bottom: Int): TextView = TextView(host.root.context).apply {
        text = value
        textSize = size
        typeface = face
        setTextColor(color)
        if (left != 0 || bottom != 0) setPadding(host.dp(left), 0, 0, host.dp(bottom))
    }

    private fun metric(label: String, value: String, color: Int): LinearLayout = LinearLayout(host.root.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(text(label, 8f, Typeface.DEFAULT, muted, 0, 2))
        addView(text(value, 13f, Typeface.DEFAULT_BOLD, color, 0, 0))
    }

    private fun divider(): View = View(host.root.context).apply { setBackgroundColor(border); layoutParams = LinearLayout.LayoutParams(-1, host.dp(1)) }

    private fun rounded(fill: Int, stroke: Int, width: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(host.dp(width), stroke)
        cornerRadius = host.dp(radius).toFloat()
    }

    private class SparklineView(context: android.content.Context, private val accent: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.color = accent
            val w = width.toFloat(); val h = height.toFloat()
            val points = floatArrayOf(.02f,.65f,.18f,.42f,.34f,.58f,.50f,.28f,.66f,.48f,.82f,.18f,.98f,.02f)
            path.reset()
            for (i in points.indices step 2) {
                val x = points[i] * w
                val y = points[i + 1] * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
            canvas.drawCircle(points[points.size - 2] * w, points[points.size - 1] * h, 2.8f, paint)
        }
    }
}

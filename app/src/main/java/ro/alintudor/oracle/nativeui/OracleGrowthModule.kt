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
import ro.alintudor.oracle.core.OracleGrowthEmergency
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

    fun render(items: List<OracleGrowthRecommendation>, fallbackNews: List<OracleNews> = emptyList(), silent: Boolean = false) {
        host.content.removeAllViews()
        if (items.isEmpty()) {
            addLoadingState()
            return
        }

        // GrowthBanner from the shared module shell is the single Growth hero.
        // Do not add a second Growth banner here.
        journalStore.record(items)
        addLocalModeBanner(items)
        addRegimeBanner(items)
        addSummary(items)

        val ordered = listOf("SHORT", "MEDIUM", "LONG").mapNotNull { horizon ->
            items.firstOrNull { it.horizon.equals(horizon, true) }
        }
        if (ordered.isNotEmpty()) addRecommendations(ordered, fallbackNews, silent)
        addNews(ordered, fallbackNews)
        addHistory(journalStore.load())
    }

    // B540 — investor quotes rotated in the loader every 15s (Requirement #7).
    // Shared with the app boot loader via OracleLoaderQuotes so both use the
    // exact same pool. Local strings only; no network request is made to show them.
    private val loaderQuotes = OracleLoaderQuotes.ALL  // kept for size(); rendering now goes through OracleLoaderQuotes.random()/spanned()

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
        if (initial.phase == OracleGrowthPhase.NO_LOCAL_WEIGHTS) {
            addNoLocalWeightsState()
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
        var currentQuote = OracleLoaderQuotes.random()
        val quoteLabel = TextView(host.root.context).apply {
            text = OracleLoaderQuotes.spanned(currentQuote, white, muted)
            textSize = 10f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
            setPadding(0, host.dp(9), 0, host.dp(9))
        }
        card.addView(quoteLabel)
        card.addView(text("Values appear once the calculation finishes.", 9f, Typeface.DEFAULT, muted, 0, 9).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(390)).apply { setMargins(0, 0, 0, host.dp(10)) })

        val handler = Handler(Looper.getMainLooper())
        val quoteRunnable = object : Runnable {
            override fun run() {
                currentQuote = OracleLoaderQuotes.random(excluding = currentQuote)
                quoteLabel.text = OracleLoaderQuotes.spanned(currentQuote, white, muted)
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
                if (p.phase == OracleGrowthPhase.NO_LOCAL_WEIGHTS) {
                    handler.removeCallbacksAndMessages(null)
                    addNoLocalWeightsState()
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

    /** Distinct from addNoDataState above: this isn't a network/OHLCV failure
     *  at all — the server has nothing usable AND the on-device formula has
     *  been deliberately removed from this build, so local ranking has
     *  nothing to compute with unless the owner's GrowthLocal-emergency file
     *  is loaded (TOOLS > Admin Only). Reusing the OHLCV wording here would
     *  describe a real network problem that isn't what actually happened. */
    private fun addNoLocalWeightsState() {
        host.content.removeAllViews()
        val card = card(18)
        card.gravity = Gravity.CENTER
        card.background = rounded(bg, Color.rgb(255, 170, 40), 1, 16)
        card.addView(text("🔌", 28f, Typeface.DEFAULT_BOLD, Color.rgb(255, 170, 40), 0, 0).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 10).apply { gravity = Gravity.CENTER })
        card.addView(text("Local computation unavailable.", 13f, Typeface.DEFAULT_BOLD, Color.rgb(255, 170, 40), 0, 6).apply { gravity = Gravity.CENTER })
        card.addView(text("The server has no usable picks right now, and no GrowthLocal-emergency file is loaded on this device — load one in TOOLS > Admin Only to compute locally.", 11f, Typeface.DEFAULT, muted, 0, 6).apply { gravity = Gravity.CENTER })
        card.addView(text("Tap ↻ (top right) to retry the server.", 11f, Typeface.DEFAULT_BOLD, cyan, 0, 6).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(200)).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun formatEta(seconds: Double): String {
        val rounded = kotlin.math.ceil(seconds).toInt().coerceAtLeast(0)
        return if (rounded < 60) "$rounded sec" else "${rounded / 60} min ${rounded % 60} sec"
    }

    /** One line, only when it matters: the ranking is relative, this says
     *  whether the market as a whole is with it. */
    private fun addLocalModeBanner(items: List<OracleGrowthRecommendation>) {
        val first = items.firstOrNull() ?: return
        if (!first.computedLocally) return
        val color = Color.rgb(255, 170, 40)
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(host.dp(14), host.dp(11), host.dp(14), host.dp(11))
            background = OracleNativeModule.rounded(Color.rgb(14, 10, 5), host.dp(12), color, host.dp(1))
        }
        box.addView(text("\uD83D\uDD0C LOCAL MODE", 12f, Typeface.DEFAULT_BOLD, color, 0, 0))
        box.addView(text(
            if (OracleGrowthEmergency.isForcingLocal(host.root.context)) "Forced on for testing — computed on this device, not the server."
            else "Server unreachable right now — computed on this device as a fallback.",
            11f, Typeface.DEFAULT, Color.rgb(205, 210, 222), 0, 4
        ))
        host.content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

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

    private fun addRecommendations(items: List<OracleGrowthRecommendation>, news: List<OracleNews>, silent: Boolean = false) {
        val section = TextView(host.root.context).apply {
            text = "ACTIVE RECOMMENDATIONS SUMMARY"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(host.dp(4), host.dp(3), host.dp(4), host.dp(7))
        }
        host.content.addView(section)
        items.forEachIndexed { index, item -> addRecommendationCard(item, news, index, silent) }
    }

    private fun addRecommendationCard(item: OracleGrowthRecommendation, news: List<OracleNews>, index: Int = 0, silent: Boolean = false) {
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
        val tickerGroup = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tickerGroup.addView(ticker)
        tickerGroup.addView(companyInfoButton(host, item.ticker), LinearLayout.LayoutParams(host.dp(26), host.dp(26)).apply { setMargins(host.dp(6), 0, 0, 0) })
        identity.addView(tickerGroup, LinearLayout.LayoutParams(host.dp(120), -2))
        // WRAP_CONTENT + a capped maxWidth (not weight=1f) so this column
        // sizes to the name's actual width instead of stretching to fill the
        // whole card — on a wide/tablet card that used to push the logo all
        // the way to the far edge, away from the name it belongs next to.
        val companyNameView = text(item.company, 15f, Typeface.DEFAULT_BOLD, white, 0, 0).apply { maxWidth = host.dp(220) }
        val companySectorView = text(item.sector, 11f, Typeface.DEFAULT_BOLD, Color.rgb(150, 170, 205), 0, 4).apply { maxWidth = host.dp(220) }
        val company = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        company.addView(companyNameView)
        company.addView(companySectorView)
        identity.addView(company, LinearLayout.LayoutParams(-2, -2))
        // Company logo, right next to the name now — no background box (was
        // reading as a white frame around the logo art).
        val logo = ImageView(host.root.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "${item.ticker} logo"
        }
        identity.addView(logo, LinearLayout.LayoutParams(host.dp(40), host.dp(40)).apply { setMargins(host.dp(8), 0, 0, 0) })
        OracleLogoLoader.load(host.root.context, item.ticker, logo)
        card.addView(identity)
        card.addView(divider())

        val demo = OracleDemo.active(host.root.context)

        // ---- 1+2. VERDICT GRID: four equal boxes, 2x2 ----
        // SCORE | SIGNAL·RISK·ALLOCATION on the first row, FAIR VALUATION |
        // FINANCIAL HEALTH on the second — every box the same size, the row
        // filling the card's width with no dead space on the right.
        val row1 = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, host.dp(8), 0, 0) }
        row1.addView(scoreMetric(item.score, demo, silent), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, host.dp(4), 0) })
        val decisionBox = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(10), host.dp(6), host.dp(10), host.dp(6))
            background = OracleNativeModule.rounded(Color.rgb(6, 10, 20), host.dp(12), Color.rgb(40, 48, 68), host.dp(1))
        }
        decisionBox.addView(badgeRow("SIGNAL", item.signal, signalColor(item.signal)))
        decisionBox.addView(badgeRow("RISK", item.risk, riskColor(item.risk)))
        decisionBox.addView(badgeRow("ALLOCATION", if (demo) OracleDemo.LOCK else "${format(item.allocationMax)}%", orange))
        row1.addView(decisionBox, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(host.dp(4), 0, 0, 0) })
        card.addView(row1)

        val row2 = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, host.dp(8), 0, host.dp(8)) }
        // Same lock as SCORE/ALLOCATION: these are engine output for this
        // specific ticker, not a general explainer — a demo visitor sees the
        // boxes exist without the real read.
        row2.addView(verdictBox("FAIR VALUATION", if (demo) OracleDemo.LOCK else item.fairValueLabel, if (demo) null else item.fairValueScore, if (demo) muted else fairValueColor(item.fairValueLabel)), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(0, 0, host.dp(4), 0) })
        row2.addView(verdictBox("FINANCIAL HEALTH", if (demo) OracleDemo.LOCK else item.financialHealthLabel, if (demo) null else item.financialHealthScore, if (demo) muted else healthColor(item.financialHealthLabel)), LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(host.dp(4), 0, 0, 0) })
        card.addView(row2)

        // ---- 3. EVIDENCE: the 18-parameter grid, display unchanged ----
        OracleFactorGrid.add(host, card, "Weights", item.weights, item.weights.maxOrNull() ?: 1, silent)

        // ---- 4. CONTEXT: explanatory, not decisional, so it sits after the evidence ----
        val lower = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(8), 0, host.dp(4)) }
        val forecast = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        // Honest label: this number is the ATR-based expected range for the
        // horizon (2×/4.5×/8×ATR), not a prediction of where price will go.
        forecast.addView(text("Expected range (ATR)", 10f, Typeface.DEFAULT, muted, 0, 0))
        forecast.addView(text(if (demo) OracleDemo.LOCK else signedPct(item.forecastPct), 20f, Typeface.DEFAULT_BOLD, green, 0, 2))
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

        val linked = news.firstOrNull { it.ticker.equals(item.ticker, true) }
        val newsTitle = if (item.newsTitle.isNotBlank()) item.newsTitle else linked?.title.orEmpty()
        val source = if (item.newsSource.isNotBlank()) item.newsSource else linked?.source.orEmpty()
        if (newsTitle.isNotBlank()) {
            card.addView(text("▣  ${if (source.isBlank()) "NEWS" else source}", 10f, Typeface.DEFAULT_BOLD, cyan, 0, 5))
            card.addView(text(newsTitle, 11f, Typeface.DEFAULT, white, 0, 4))
        }
        card.addView(text("This data is informational and does not constitute investment advice.", 9f, Typeface.DEFAULT, Color.rgb(125, 135, 155), 0, 8))
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })

        // Entrance: fade + rise in, staggered per card so they don't all pop at
        // once — but only on a genuine fresh navigation. A silent background
        // refresh re-renders the exact same cards a user is already looking
        // at; replaying this (plus the two pulse animations below) is what
        // reads as an unprompted flicker/flash on a screen nothing was done to.
        if (!silent) {
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
    }

    private fun fairValueColor(label: String) = when { label.contains("UNDERVALUED") -> green; label.contains("OVERVALUED") -> red; label.contains("FAIRLY") -> orange; else -> muted }
    private fun healthColor(label: String) = when { label.contains("STRONG") -> green; label.contains("STABLE") -> orange; label.contains("DISTRESSED") -> red; label.contains("WEAK") -> Color.rgb(255, 150, 60); else -> muted }
    private fun verdictBox(title: String, label: String, score: Int?, color: Int): LinearLayout {
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(host.dp(11), host.dp(9), host.dp(11), host.dp(9))
            background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(11), color, host.dp(1))
        }
        box.addView(text(title, 9f, Typeface.DEFAULT_BOLD, muted, 0, 0))
        box.addView(text(if (label.isBlank()) "\u2014" else label, 12f, Typeface.DEFAULT_BOLD, color, 0, 3))
        if (score != null) box.addView(text("$score/100", 10f, Typeface.DEFAULT, Color.rgb(170, 178, 196), 0, 1))
        return box
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
                if (OracleDemo.active(host.root.context)) { Toast.makeText(host.root.context, "${OracleDemo.LOCK} Exporting needs an account", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
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
                val srcColor = if (item.computedLocally) orange else cyan
                top.addView(TextView(host.root.context).apply {
                    text = if (item.computedLocally) "L" else "S"
                    textSize = 8f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(srcColor)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(6, 10, 20)); setStroke(host.dp(1), srcColor) }
                }, LinearLayout.LayoutParams(host.dp(16), host.dp(16)))
                addView(top)
                val demo = OracleDemo.active(host.root.context)
                val details = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(5), 0, 0) }
                details.addView(text(item.horizon, 9f, Typeface.DEFAULT_BOLD, accent, 0, 0), LinearLayout.LayoutParams(0, -2, .7f))
                details.addView(text(formatT0(item.referenceTimestamp), 9f, Typeface.DEFAULT, muted, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(text(if (demo) "Forecast ${OracleDemo.LOCK}" else "Forecast ${signedPct(item.forecastPct)}", 9f, Typeface.DEFAULT_BOLD, green, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))
                details.addView(text(if (demo) "Score ${OracleDemo.LOCK}" else "Score ${item.score}/100", 9f, Typeface.DEFAULT_BOLD, cyan, 0, 0), LinearLayout.LayoutParams(0, -2, .8f))
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

    /** One single-line labelled badge: small muted label on the left, the
     *  value in its own color on the right. Used for the SIGNAL / RISK /
     *  ALLOCATION stack next to the hero score. */
    private fun badgeRow(label: String, value: String, color: Int): LinearLayout = LinearLayout(host.root.context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(0, host.dp(3), 0, host.dp(3))
        // Label and value sit side by side, no stretch between them — "SIGNAL [BUY]"
        // reads as one unit instead of a label on the left and its value a screen away.
        // The label gets its natural (small, fixed) width FIRST — it must never be
        // the one squeezed to nothing. The badge gets whatever's left and shrinks
        // into that space instead: with the weight on the label, the longest
        // possible value ("STRONG BUY") could claim the row's entire width and
        // leave the label at 0dp, which forces a TextView to wrap one letter per
        // line — exactly the "S / I / G / N / A / L" stack this was producing on
        // narrow phones.
        addView(text(label, 8f, Typeface.DEFAULT, muted, 0, 0), LinearLayout.LayoutParams(-2, -2))
        addView(TextView(host.root.context).apply {
            // Up to 2 lines, no ellipsize: "STRONG BUY" wraps at its natural space
            // into "STRONG" / "BUY" instead of being cut down to "STRO…" — the
            // label already has first claim on width (see above), so the badge
            // shrinking to 2 lines here can no longer squeeze it to 0dp either.
            // Auto-size (8-12sp) rather than a fixed size: on a narrow phone even
            // "STRONG" alone can be wider than the available box, which forces a
            // hard mid-word break ("STR"/"ONG") since there's no space to wrap at
            // within that single word — auto-size finds the largest size in that
            // range where "STRONG" still fits whole on its own line. Short values
            // ("BUY", "LOW", "3.6%") always fit at 12sp, so they render unchanged.
            text = value; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); maxLines = 2
            gravity = Gravity.CENTER; setPadding(host.dp(8), host.dp(2), host.dp(8), host.dp(2))
            background = OracleNativeModule.rounded(Color.rgb(6, 10, 20), host.dp(8), color, host.dp(1))
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                setAutoSizeTextTypeUniformWithConfiguration(8, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            } else {
                textSize = 12f
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = host.dp(6) })
    }


    /** The one number the whole card exists to show — deliberately bigger
     *  and boxed apart from its SIGNAL/RISK/ALLOCATION siblings, with a
     *  count-up animation so it draws the eye first on every card. */
    private fun scoreMetric(score: Int, demo: Boolean, silent: Boolean = false): LinearLayout {
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(host.dp(4), host.dp(10), host.dp(4), host.dp(10))
            background = OracleNativeModule.rounded(Color.rgb(6, 16, 22), host.dp(12), cyan, host.dp(1))
        }
        box.addView(text("SCORE", 8f, Typeface.DEFAULT, muted, 0, 2))
        if (demo) {
            box.addView(text(OracleDemo.LOCK, 20f, Typeface.DEFAULT_BOLD, cyan, 0, 0))
            return box
        }
        if (silent) {
            box.addView(text("$score/100", 24f, Typeface.DEFAULT_BOLD, cyan, 0, 0))
            return box
        }
        val number = text("0", 24f, Typeface.DEFAULT_BOLD, cyan, 0, 0)
        box.addView(number)
        android.animation.ValueAnimator.ofInt(0, score).apply {
            duration = 700L; interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { anim -> if (number.isAttachedToWindow) number.text = "${anim.animatedValue}/100" }
            start()
        }
        return box
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

package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.*
import ro.alintudor.oracle.core.*
import java.util.Locale
import kotlin.math.abs

// ANALYSIS_RAW_VALUES_V4

class OracleSimpleModule(private val host: OracleNativeModule, private val moduleTitle: String, private val onWatchlistTickerClick: (String) -> Unit = {}) {
    companion object {
        @Volatile private var watchlistScoring = false
        @Volatile private var tickerDraft: String = ""
        fun setTickerDraft(ticker: String) { tickerDraft = ticker.trim().uppercase(Locale.US) }
    }

    fun render(actions: List<OracleAction> = emptyList(), knowledge: List<OracleKnowledgeItem> = emptyList(), positions: List<OraclePosition> = emptyList(), history: List<OracleHistoryPoint> = emptyList(), watchlist: List<String> = OracleWatchlistStore(host.root.context).load()) {
        host.content.rebuildWithoutFlicker {
            host.content.removeAllViews()
            val p = OracleAnalytics.normalize(positions)
            val computed = OracleAnalytics.actions(p, history)
            when (moduleTitle) {
                "GROWTH" -> renderGrowth()
                "ANALYSIS" -> renderAnalysis()
                "WATCHLIST" -> renderWatchlist(watchlist)
                "KNOWLEDGE" -> renderKnowledgeSynced()
                else -> renderActions(if (computed.isNotEmpty()) computed else actions)
            }
        }
    }

    private fun renderGrowth() {
        val r = OracleRepository(host.root.context)
        OracleGrowthModule(host).render(r.cachedGrowth(), r.cachedNews())
    }

    private fun renderKnowledgeSynced() {
        val context = host.root.context
        val cached = OracleKnowledgeSync.load(context)
        OracleKnowledgeModule(host).render(items = cached)
        // Automatic: every time this screen renders (open, header refresh),
        // sync in the background and re-render once it lands. Throttled in
        // shouldAutoSync so the re-render can't retrigger it in a loop.
        if (cached.isEmpty() || OracleKnowledgeSync.shouldAutoSync(context)) {
            OracleKnowledgeSync.refreshAsync(context) { _, _ -> if (host.root.isAttachedToWindow) renderKnowledgeSynced() }
        }
    }

    private fun renderAnalysis() {
        host.addSectionLabel("ANALYSIS • SINGLE TICKER")
        val input = EditText(host.root.context).apply {
            hint = "Enter a ticker (e.g. NVDA)"
            setSingleLine(true)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(130, 145, 170))
            setPadding(host.dp(16), 0, host.dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(8, 14, 28))
                cornerRadius = host.dp(14).toFloat()
                setStroke(host.dp(1), host.accent)
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = true
            if (tickerDraft.isNotBlank()) setText(tickerDraft)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                tickerDraft = s?.toString() ?: ""
                if (tickerDraft.isNotBlank()) {
                    input.post {
                        if (input.windowToken != null) {
                            input.requestFocus()
                            (host.root.context.getSystemService(InputMethodManager::class.java))?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        host.fixedToolbar.addView(input, LinearLayout.LayoutParams(-1, host.dp(52)).apply { setMargins(0, host.dp(3), 0, host.dp(6)) })
        if (tickerDraft.isNotBlank()) {
            input.setSelection(input.text.length)
            input.post {
                input.requestFocus()
                (host.root.context.getSystemService(InputMethodManager::class.java))?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        input.postDelayed({
            input.requestFocus()
            val imm = host.root.context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            (host.root.context as? android.app.Activity)?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }, 180L)
        val button = Button(host.root.context).apply {
            text = "ANALYZE TICKER"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(15, 75, 110))
                cornerRadius = host.dp(13).toFloat()
            }
        }
        host.fixedToolbar.addView(button, LinearLayout.LayoutParams(-1, host.dp(48)).apply { setMargins(0, 0, 0, host.dp(8)) })

        // Demo: the engine only ever runs against the 3 sample tickers — typing
        // anything else would be free, unlimited use of the same proprietary
        // engine Growth locks behind an account. Real analysis is a paid feature.
        fun run() {
            val t = input.text.toString().trim().uppercase(Locale.US)
            if (t.isBlank()) {
                input.error = "Enter a ticker"
                return
            }
            if (OracleDemo.active(host.root.context) && t !in OracleDemo.TICKERS) {
                Toast.makeText(host.root.context, "${OracleDemo.LOCK} Demo analysis is limited to AAPL and NVDA \u2014 create an account to analyze any ticker.", Toast.LENGTH_LONG).show()
                return
            }
            tickerDraft = t
            button.isEnabled = false
            button.text = "ANALYZING…"
            Thread {
                val x = runCatching { OracleAnalysisEngine.analyze(t) }
                host.root.post {
                    button.isEnabled = true
                    button.text = "ANALYZE TICKER"
                    x.onSuccess { renderResult(it) }
                        .onFailure { Toast.makeText(host.root.context, "Analysis failed: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        button.setOnClickListener { run() }
        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { run(); true } else false }
        // AUTO_WATCHLIST_ANALYZE: a Watchlist navigation opens the actual ticker analysis, not only the input field.
        if (tickerDraft.isNotBlank()) input.postDelayed({ run() }, 220L)
    }

    private fun renderResult(r: OracleAnalysisEngine.Result?) {
        if (r == null) {
            Toast.makeText(host.root.context, "The ticker could not be found / analyzed.", Toast.LENGTH_LONG).show()
            return
        }
        if (host.content.childCount > 1) host.content.removeViews(1, host.content.childCount - 1)

        val topBg = GradientDrawable().apply {
            setColor(Color.rgb(5, 10, 19))
            cornerRadius = host.dp(16).toFloat()
            setStroke(host.dp(1), host.accent)
        }
        val top = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14))
            background = topBg
        }
        val headline = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // Ticker and price sit together on the left — reading "MU · 1016.59"
        // as one unit is faster than hunting for the price at the far edge.
        val tickerPriceGroup = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tickerPriceGroup.addView(TextView(host.root.context).apply {
            text = r.ticker
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        tickerPriceGroup.addView(TextView(host.root.context).apply {
            text = money(r.price)
            textSize = 26f   // matches the ticker's own size now that they sit together
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(45, 232, 92))
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(host.dp(10), 0, 0, 0) })
        headline.addView(tickerPriceGroup, LinearLayout.LayoutParams(0, -2, 1f))
        val logo = ImageView(host.root.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "${r.ticker} logo"
        }
        headline.addView(logo, LinearLayout.LayoutParams(host.dp(40), host.dp(40)).apply { setMargins(0, 0, host.dp(10), 0) })
        OracleLogoLoader.load(host.root.context, r.ticker, logo)
        val watchStore = OracleWatchlistStore(host.root.context)
        val watchTicker = r.ticker.trim().uppercase(Locale.US)
        var watchButtonRef: Button? = null
        fun updateWatchUi(present: Boolean, eye: WatchlistEyeView? = null) {
            eye?.setSelectedState(present)
            watchButtonRef?.apply {
                text = if (present) "✓  IN WATCHLIST" else "＋  ADD TO WATCHLIST"
                background = GradientDrawable().apply {
                    setColor(if (present) Color.rgb(25, 75, 45) else Color.rgb(95, 55, 10))
                    cornerRadius = host.dp(13).toFloat()
                }
            }
        }
        val watchEye = WatchlistEyeView(host.root.context, host.dp(42)).apply {
            tag = "oracle_watchlist_eye_direct"
            isClickable = true
            isFocusable = true
            contentDescription = "Add or remove $watchTicker from Watchlist"
            setSelectedState(watchStore.load().any { it.equals(watchTicker, true) })
            setOnClickListener {
                val current = watchStore.load().toMutableList()
                val present = current.any { it.equals(watchTicker, true) }
                if (!present && OracleDemo.active(host.root.context) && current.size >= 3) { Toast.makeText(host.root.context, "${OracleDemo.LOCK} Demo watchlist holds 3 tickers \u2014 create an account for more", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (present) current.removeAll { it.equals(watchTicker, true) } else current.add(watchTicker)
                watchStore.save(current)
                updateWatchUi(!present, this)
                Toast.makeText(host.root.context, if (!present) "$watchTicker added to Watchlist" else "$watchTicker removed from Watchlist", Toast.LENGTH_SHORT).show()
            }
        }
        // Each icon carries a caption underneath — an eye and a two-arrow
        // glyph are not self-explanatory on their own.
        fun labelledIcon(icon: View, caption: String): LinearLayout =
            LinearLayout(host.root.context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(icon, LinearLayout.LayoutParams(host.dp(42), host.dp(42)))
                addView(TextView(host.root.context).apply {
                    text = caption; textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
                    gravity = Gravity.CENTER; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, host.dp(3), 0, 0)
                }, LinearLayout.LayoutParams(-2, -2))
            }
        val compareIcon = CompareGlyphView(host.root.context, host.dp(42)).apply {
            isClickable = true; isFocusable = true; contentDescription = "Compare ${watchTicker} with another ticker"
            setOnClickListener { showCompareDialog(host, watchTicker) }
        }
        headline.addView(labelledIcon(watchEye, "WATCH"), LinearLayout.LayoutParams(-2, -2).apply { setMargins(host.dp(4), 0, host.dp(10), 0) })
        headline.addView(labelledIcon(compareIcon, "COMPARE"), LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, host.dp(10), 0) })
        headline.addView(labelledIcon(companyInfoButton(host, watchTicker), "INFO"), LinearLayout.LayoutParams(-2, -2))
        top.addView(headline)
        top.addView(TextView(host.root.context).apply {
            text = companyName(host.root.context, r.ticker)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(205, 213, 228))
            setPadding(0, host.dp(4), 0, 0)
        })
        top.addView(TextView(host.root.context).apply {
            text = "Sector: ${r.sector ?: "Sector unavailable"}"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(145, 158, 180))
            setPadding(0, host.dp(2), 0, 0)
        })
        host.content.addView(top, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
        top.alpha = 0f
        top.translationY = host.dp(24).toFloat()
        top.animate().alpha(1f).translationY(0f).setDuration(400L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        run {
            val strokePx = host.dp(1)
            val ar = Color.red(host.accent); val ag = Color.green(host.accent); val ab = Color.blue(host.accent)
            android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 2000L
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    if (!top.isAttachedToWindow) { anim.cancel(); return@addUpdateListener }
                    val q = anim.animatedValue as Float
                    topBg.setStroke((strokePx * (1f + 0.7f * q)).toInt().coerceAtLeast(1), Color.argb((150 + 105 * q).toInt(), ar, ag, ab))
                }
            }.start()
        }

        val f = r.fundamentals
        val fairValue = OracleValuation.fairValue(f, f?.sector ?: r.sector)
        val health = OracleValuation.financialHealth(f)

        // ==== 2. VALUE & HEALTH — identical boxes to Growth ====
        val verdicts = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, host.dp(10)) }
        // Same lock Growth applies to these two boxes — engine output for this
        // ticker, not general explainer text.
        val verdictsDemo = OracleDemo.active(host.root.context)
        verdicts.addView(verdictBox("FAIR VALUATION", if (verdictsDemo) OracleDemo.LOCK else fairValue.label, if (verdictsDemo) null else fairValue.score, if (verdictsDemo) vMuted else fairValueColor(fairValue.label)), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, host.dp(4), 0) })
        verdicts.addView(verdictBox("FINANCIAL HEALTH", if (verdictsDemo) OracleDemo.LOCK else health.label, if (verdictsDemo) null else health.score, if (verdictsDemo) vMuted else healthColor(health.label)), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(host.dp(4), 0, 0, 0) })
        host.content.addView(verdicts)

        // ==== 3. EVIDENCE — the same 18-cell grid Growth draws, with this ticker's factor VALUES ====
        host.addSectionLabel("FACTOR SCORES \u2022 SAME ENGINE AS GROWTH")
        val gridCard = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(10), host.dp(15), host.dp(10))
            background = GradientDrawable().apply { setColor(Color.rgb(7, 12, 23)); cornerRadius = host.dp(15).toFloat(); setStroke(host.dp(1), Color.rgb(34, 55, 82)) }
        }
        if (OracleDemo.active(host.root.context)) {
            // The factor values ARE the engine's output for this ticker — the
            // same thing the Growth score locks. Demo shows the grid exists,
            // not the numbers.
            gridCard.addView(TextView(host.root.context).apply {
                text = "${OracleDemo.LOCK}  Factor scores are for account holders. The 17 factors and LO are shown with real values once you sign in."
                textSize = 12f; setTextColor(Color.rgb(205, 213, 228)); setLineSpacing(host.dp(3).toFloat(), 1f)
            })
        } else {
            OracleFactorGrid.add(host, gridCard, "Factor scores (0\u2013100)", r.growthComponents, 100)
        }
        host.content.addView(gridCard, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })

        // ==== 4. CONTEXT — the chart ====
        addTechnicalChart(r.ticker)

        // ==== 5. DETAILS — raw numbers, grouped and collapsed by default ====
        // Whoever wants a specific figure can open its group; nobody has to
        // scroll past ~28 cards to reach the chart.
        host.addSectionLabel("DETAILS")
        val technical = mutableListOf<Pair<String, String>>()
        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            if (!name.equals("Fundamentals", ignoreCase = true) && !name.equals("Market / Sector", ignoreCase = true)) {
                technical.add(name to (r.rawValues.getOrNull(i + 1) ?: "Value unavailable"))
            }
        }
        technical.add("RSI (14)" to fmt(r.rsi))
        technical.add("MACD (12/26)" to metricPair(r.macd, r.macdSignal))
        technical.add("52W HIGH / LOW" to "${moneyOrDash(r.week52High)} / ${moneyOrDash(r.week52Low)}")
        technical.add("ATR" to "${money(r.atrValue)}  \u2022  ${fmt(r.atrPct)}%")
        collapsible("TECHNICAL", technical.size) { box -> addMetricGrid(box, technical) }

        // Sector is already in the identity header — not repeated here.
        val fundamentalsList = mutableListOf<Pair<String, String>>()
        fundamentalsList.add("Industry" to (f?.industry ?: "\u2014"))
        fundamentalsList.add("P/E" to num2(f?.trailingPe))
        fundamentalsList.add("Fwd P/E" to num2(f?.forwardPe))
        fundamentalsList.add("P/B" to num2(f?.priceToBook))
        fundamentalsList.add("Revenue growth (YoY)" to pctFund(f?.revenueGrowth))
        fundamentalsList.add("Earnings growth" to pctFund(f?.earningsGrowth))
        fundamentalsList.add("Net margin" to pctFund(f?.profitMargin))
        fundamentalsList.add("Operating margin" to pctFund(f?.operatingMargin))
        fundamentalsList.add("ROE" to pctFund(f?.returnOnEquity))
        fundamentalsList.add("D/E" to num2(f?.debtToEquity))
        fundamentalsList.add("Current ratio" to num2(f?.currentRatio))
        fundamentalsList.add("Quick ratio" to num2(f?.quickRatio))
        fundamentalsList.add("Market cap" to capText(f?.marketCap))
        fundamentalsList.add("Fair Valuation" to fairValueText(fairValue))
        fundamentalsList.add("Financial Health" to financialHealthText(health))
        collapsible("FUNDAMENTALS", fundamentalsList.size) { box -> addMetricGrid(box, fundamentalsList) }

        val market = mutableListOf<Pair<String, String>>()
        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            if (name.equals("Market / Sector", ignoreCase = true)) market.add(name to (r.rawValues.getOrNull(i + 1) ?: "Value unavailable"))
        }
        market.add("Beta" to num2(f?.beta))
        collapsible("MARKET", market.size) { box -> addMetricGrid(box, market) }

        collapsible("ORACLE READING", analysisLines(r).size) { box ->
            analysisLines(r).forEach { line ->
                box.addView(TextView(host.root.context).apply {
                    text = "\u2014 $line"; textSize = 13f; setTextColor(Color.rgb(205, 213, 228)); setPadding(host.dp(4), host.dp(4), host.dp(4), host.dp(4))
                })
            }
        }

        val inWatchNow = watchStore.load().any { it.equals(watchTicker, true) }
        val watchButton = Button(host.root.context).apply {
            text = if (inWatchNow) "✓  IN WATCHLIST" else "＋  ADD TO WATCHLIST"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (inWatchNow) Color.rgb(25, 75, 45) else Color.rgb(95, 55, 10))
                cornerRadius = host.dp(13).toFloat()
            }
        }
        watchButtonRef = watchButton
        watchButton.setOnClickListener {
            val current = watchStore.load().toMutableList()
            val present = current.any { it.equals(watchTicker, true) }
            if (!present && OracleDemo.active(host.root.context) && current.size >= 3) { Toast.makeText(host.root.context, "${OracleDemo.LOCK} Demo watchlist holds 3 tickers \u2014 create an account for more", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (present) current.removeAll { it.equals(watchTicker, true) } else current.add(watchTicker)
            watchStore.save(current)
            updateWatchUi(!present, watchEye)
            Toast.makeText(host.root.context, if (!present) "$watchTicker added to Watchlist" else "$watchTicker removed from Watchlist", Toast.LENGTH_SHORT).show()
        }
        host.content.addView(watchButton, LinearLayout.LayoutParams(-1, host.dp(50)).apply { setMargins(0, 0, 0, host.dp(16)) })
    }

    private fun addMetricGrid(container: LinearLayout, items: List<Pair<String, String>>) {
        var row: LinearLayout? = null

        fun equalizeRow(target: LinearLayout) {
            var maxHeight = 0
            for (j in 0 until target.childCount) {
                val child = target.getChildAt(j)
                child.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(target.measuredWidth / 2, android.view.View.MeasureSpec.AT_MOST),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
                )
                maxHeight = maxOf(maxHeight, child.measuredHeight)
            }
            if (maxHeight > 0) {
                for (j in 0 until target.childCount) {
                    val child = target.getChildAt(j)
                    val lp = child.layoutParams
                    if (lp.height != maxHeight) {
                        lp.height = maxHeight
                        child.layoutParams = lp
                    }
                }
            }
        }

        items.forEachIndexed { index, item ->
            if (index % 2 == 0) {
                row = LinearLayout(host.root.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    clipChildren = false
                    clipToPadding = false
                }
                container.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, host.dp(6))
                })
            }

            val card = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(11), host.dp(8), host.dp(11), host.dp(8))
                clipChildren = false
                clipToPadding = false
                background = GradientDrawable().apply {
                    setColor(Color.rgb(6, 12, 24))
                    cornerRadius = host.dp(12).toFloat()
                    setStroke(host.dp(1), Color.rgb(35, 65, 98))
                }
            }
            card.addView(TextView(host.root.context).apply {
                text = item.first.uppercase(Locale.US)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .07f
                setTextColor(Color.rgb(85, 190, 235))
                includeFontPadding = true
                maxLines = 2
                setHorizontallyScrolling(false)
            })
            card.addView(TextView(host.root.context).apply {
                text = item.second
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(metricValueColor(item.first, item.second))
                setPadding(0, host.dp(2), 0, 0)
                includeFontPadding = true
                setHorizontallyScrolling(false)
                maxLines = Int.MAX_VALUE
                ellipsize = null
            })
            row?.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index % 2 == 1) setMargins(host.dp(4), 0, 0, 0)
                else setMargins(0, 0, host.dp(4), 0)
            })

            row?.post { equalizeRow(row!!) }
        }

        container.post {
            for (i in 0 until container.childCount) {
                val r = container.getChildAt(i) as? LinearLayout ?: continue
                equalizeRow(r)
            }
            container.requestLayout()
        }
    }

    private fun metricValueColor(label: String, value: String): Int {
        val l = label.uppercase(Locale.US)
        val v = value.uppercase(Locale.US)
        if (value == "—" || value.contains("UNAVAILABLE") || value.contains("UNAVAILABLE")) return Color.rgb(205, 165, 38)

        fun numberAfter(token: String): Double? {
            val m = Regex(Regex.escape(token) + "\\s*(-?\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(value) ?: return null
            return m.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        fun firstNumber(): Double? = Regex("-?\\d+(?:[.,]\\d+)?").find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
        fun pctNumber(): Double? = firstNumber()

        return when {
            l == "SECTOR" || l == "INDUSTRY" -> Color.rgb(50, 220, 135)
            l == "BREAKOUT" -> if (v.contains("BREAKOUT: YES")) Color.rgb(50, 220, 135) else Color.rgb(205, 165, 38)
            l == "FAIR VALUATION" -> when { v.contains("UNDERVALUED") -> Color.rgb(50, 220, 135); v.contains("OVERVALUED") -> Color.rgb(244, 67, 54); v.contains("FAIRLY") -> Color.rgb(205, 165, 38); else -> Color.rgb(140, 150, 172) }
            l == "FINANCIAL HEALTH" -> when { v.contains("STRONG") -> Color.rgb(50, 220, 135); v.contains("STABLE") -> Color.rgb(205, 165, 38); v.contains("WEAK") -> Color.rgb(255, 150, 60); v.contains("DISTRESSED") -> Color.rgb(244, 67, 54); else -> Color.rgb(140, 150, 172) }
            l == "TREND" -> {
                val p = numberAfter("Price"); val s50 = numberAfter("SMA50"); val s200 = numberAfter("SMA200")
                when {
                    p != null && s50 != null && s200 != null && p >= s50 && p >= s200 -> Color.rgb(50, 220, 135)
                    p != null && s50 != null && s200 != null && p < s50 && p < s200 -> Color.rgb(244, 67, 54)
                    else -> Color.rgb(205, 165, 38)
                }
            }
            l == "MOMENTUM" -> {
                val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(value).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
                when {
                    nums.size >= 2 && nums[0] > 0 && nums[1] > 0 -> Color.rgb(50, 220, 135)
                    nums.size >= 2 && nums[0] < 0 && nums[1] < 0 -> Color.rgb(244, 67, 54)
                    else -> Color.rgb(205, 165, 38)
                }
            }
            l == "VOLUME" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 0.8..1.8 -> Color.rgb(50, 220, 135); n < 0.8 -> Color.rgb(205, 165, 38); else -> Color.rgb(205, 165, 38) }
            }
            l == "SUPPORT / RESISTANCE" -> Color.rgb(205, 165, 38)
            l == "BOLLINGER" -> {
                val pos = numberAfter("Position")
                when { pos == null -> Color.rgb(205, 165, 38); pos in -20.0..20.0 -> Color.rgb(50, 220, 135); pos < -20.0 -> Color.rgb(244, 67, 54); else -> Color.rgb(205, 165, 38) }
            }
            l == "ICHIMOKU" -> if (v.contains("BULLISH")) Color.rgb(50, 220, 135) else Color.rgb(244, 67, 54)
            l == "MARKET / SECTOR" -> Color.rgb(50, 220, 135)
            l == "RISK / REWARD" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n <= 5.0 -> Color.rgb(50, 220, 135); n <= 8.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "ADX" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n >= 20.0 -> Color.rgb(50, 220, 135); else -> Color.rgb(205, 165, 38) }
            }
            l == "RSI (14)" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 30.0..70.0 -> Color.rgb(50, 220, 135); n < 30.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "MACD (12/26)" -> {
                val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(value).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
                when { nums.size >= 2 && nums[0] > nums[1] -> Color.rgb(50, 220, 135); nums.size >= 2 && nums[0] < nums[1] -> Color.rgb(244, 67, 54); else -> Color.rgb(205, 165, 38) }
            }
            l == "ATR" -> {
                val n = Regex("(-?\\d+(?:[.,]\\d+)?)%", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                when { n == null -> Color.rgb(205, 165, 38); n in 2.0..6.0 -> Color.rgb(50, 220, 135); n > 6.0 -> Color.rgb(244, 67, 54); else -> Color.rgb(205, 165, 38) }
            }
            l == "52W HIGH / LOW" -> Color.rgb(205, 165, 38)
            l == "P/E" || l == "FWD P/E" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 10.0..30.0 -> Color.rgb(50, 220, 135); n < 10.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "P/B" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 1.0..5.0 -> Color.rgb(50, 220, 135); n < 1.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l.startsWith("REVENUE GROWTH") -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(205, 165, 38); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "EARNINGS GROWTH" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(205, 165, 38); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "NET MARGIN" || l == "OPERATING MARGIN" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(205, 165, 38); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "ROE" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(205, 165, 38); n >= 15.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "D/E" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n <= 1.0 -> Color.rgb(50, 220, 135); n <= 2.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "CURRENT RATIO" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 1.5..3.0 -> Color.rgb(50, 220, 135); n >= 1.0 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "QUICK RATIO" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 1.0..2.0 -> Color.rgb(50, 220, 135); n >= 0.7 -> Color.rgb(205, 165, 38); else -> Color.rgb(244, 67, 54) }
            }
            l == "BETA" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(205, 165, 38); n in 0.8..1.5 -> Color.rgb(50, 220, 135); n > 1.5 -> Color.rgb(244, 67, 54); else -> Color.rgb(205, 165, 38) }
            }
            l == "MARKET CAP" -> Color.rgb(50, 220, 135)
            else -> Color.rgb(205, 165, 38)
        }
    }
    private fun metricPair(value: Double?, signal: Double?): String = "${num2(value)}  •  SIG ${num2(signal)}"
    // ---- Unified-anatomy helpers (styled to match the Growth card) ----
    private val vGreen = Color.rgb(105, 245, 35); private val vOrange = Color.rgb(255, 160, 25); private val vRed = Color.rgb(255, 80, 90)
    private val vMuted = Color.rgb(165, 174, 195)

    private fun fairValueColor(label: String) = when { label.contains("UNDERVALUED") -> vGreen; label.contains("OVERVALUED") -> vRed; label.contains("FAIRLY") -> vOrange; else -> vMuted }
    private fun healthColor(label: String) = when { label.contains("STRONG") -> vGreen; label.contains("STABLE") -> vOrange; label.contains("DISTRESSED") -> vRed; label.contains("WEAK") -> Color.rgb(255, 150, 60); else -> vMuted }



    private fun verdictBox(title: String, label: String, score: Int?, color: Int): LinearLayout {
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(host.dp(11), host.dp(9), host.dp(11), host.dp(9))
            background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(11), color, host.dp(1))
        }
        box.addView(TextView(host.root.context).apply { text = title; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(vMuted) })
        box.addView(TextView(host.root.context).apply { text = if (label.isBlank()) "\u2014" else label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); setPadding(0, host.dp(3), 0, 0) })
        if (score != null) box.addView(TextView(host.root.context).apply { text = "$score/100"; textSize = 10f; setTextColor(Color.rgb(170, 178, 196)); setPadding(0, host.dp(1), 0, 0) })
        return box
    }

    /** Collapsed-by-default section: a tappable header with a count and a chevron; content is built once, lazily, on first open. */
    private fun collapsible(title: String, count: Int, build: (LinearLayout) -> Unit) {
        val header = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
            background = OracleNativeModule.rounded(Color.rgb(7, 12, 23), host.dp(12), Color.rgb(34, 55, 82), host.dp(1))
            isClickable = true; isFocusable = true
        }
        header.addView(TextView(host.root.context).apply { text = title; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f; setTextColor(host.accent) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(host.root.context).apply { text = "$count"; textSize = 10f; setTextColor(vMuted); setPadding(0, 0, host.dp(10), 0) })
        val chevron = TextView(host.root.context).apply { text = "\u2304"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(vMuted) }
        header.addView(chevron)
        val body = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, host.dp(6), 0, 0) }
        var built = false
        header.setOnClickListener {
            if (!built) { build(body); built = true }
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.text = if (open) "\u2303" else "\u2304"
        }
        host.content.addView(header, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(6)) })
        host.content.addView(body, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(6)) })
    }

    private fun fairValueText(fv: OracleValuation.FairValue): String {
        if (fv.score == null) return "${fv.label} — need P/E and growth data"
        val peg = fv.peg?.let { "PEG ${"%.2f".format(Locale.US, it)}" } ?: "PEG n/a"
        val pb = fv.priceToBook?.let { "P/B ${"%.2f".format(Locale.US, it)}" } ?: "P/B n/a"
        return "${fv.label} (${fv.score}/100) • $peg • $pb • sector ref P/E ${"%.0f".format(Locale.US, fv.sectorPe ?: 20.0)}"
    }
    private fun financialHealthText(h: OracleValuation.FinancialHealth): String {
        if (h.score == null) return "${h.label} — need balance-sheet data"
        val cr = h.currentRatio?.let { "current ratio ${"%.2f".format(Locale.US, it)}" } ?: "current ratio n/a"
        val de = h.debtToEquity?.let { "D/E ${"%.0f".format(Locale.US, it)}" } ?: "D/E n/a"
        val roe = h.returnOnEquity?.let { "ROE ${"%.1f".format(Locale.US, it * 100.0)}%" } ?: "ROE n/a"
        return "${h.label} (${h.score}/100) • $cr • $de • $roe"
    }
    private fun num2(value: Double?): String = value?.let { "%.2f".format(Locale.US, it) } ?: "—"
    private fun pctFund(value: Double?): String = value?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "—"
    private fun capText(value: Double?): String = when { value == null -> "—"; value >= 1e12 -> "%.2fT".format(Locale.US, value / 1e12); value >= 1e9 -> "%.2fB".format(Locale.US, value / 1e9); value >= 1e6 -> "%.2fM".format(Locale.US, value / 1e6); else -> "%.0f".format(Locale.US, value) }

    private fun addTechnicalChart(ticker: String) {
        val chartTitle = TextView(host.root.context).apply {
            text = "TECHNICAL CHART • LIVE DATA"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .10f
            setTextColor(host.accent)
            setPadding(host.dp(5), host.dp(10), host.dp(5), host.dp(10))
        }
        host.content.addView(chartTitle, LinearLayout.LayoutParams(-1, -2))
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(3, 7, 14))
                cornerRadius = host.dp(15).toFloat()
                setStroke(host.dp(1), Color.rgb(34, 55, 82))
            }
        }
        val chart = OracleAnalysisChartView(host.root.context, ticker)
        box.addView(chart, LinearLayout.LayoutParams(-1, host.dp(660)))

        val ranges = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val activeRange = Color.rgb(20, 70, 105)
        val inactiveRange = Color.rgb(12, 20, 34)
        fun styleRange(b: Button, active: Boolean) {
            b.alpha = 1f
            b.background = GradientDrawable().apply {
                setColor(if (active) activeRange else inactiveRange)
                cornerRadius = host.dp(9).toFloat()
                setStroke(host.dp(1), Color.rgb(45, 65, 90))
            }
        }
        listOf("5M", "30M", "1H", "1D", "5D", "1M", "3M", "1Y").forEachIndexed { i, label ->
            val b = Button(host.root.context).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
            }
            styleRange(b, i == 0)
            b.setOnClickListener {
                chart.setMode(label)
                for (j in 0 until ranges.childCount) {
                    val other = ranges.getChildAt(j) as Button
                    styleRange(other, other === b)
                }
            }
            ranges.addView(b, LinearLayout.LayoutParams(0, host.dp(44), 1f).apply { setMargins(host.dp(2), host.dp(6), host.dp(2), 0) })
        }
        box.addView(ranges)

        val indicators = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf("BB", "MA/EMA", "MA Cross", "ICHI", "RSI", "ADX").forEach { label ->
            val b = Button(host.root.context).apply {
                text = label
                textSize = if (label == "MA Cross") 8.5f else 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(205, 213, 228))
                setPadding(0, 0, 0, 0)
            }
            val initiallyActive = label != "ICHI"
            fun styleIndicator(active: Boolean) {
                b.alpha = 1f
                b.background = GradientDrawable().apply {
                    setColor(if (active) activeRange else Color.rgb(8, 14, 25))
                    cornerRadius = host.dp(8).toFloat()
                    setStroke(host.dp(1), Color.rgb(40, 55, 78))
                }
            }
            styleIndicator(initiallyActive)
            var active = initiallyActive
            b.setOnClickListener {
                chart.toggleIndicator(label)
                active = !active
                styleIndicator(active)
            }
            indicators.addView(b, LinearLayout.LayoutParams(0, host.dp(38), 1f).apply { setMargins(host.dp(2), host.dp(5), host.dp(2), 0) })
        }
        box.addView(indicators)

        val legend = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(7))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 12, 23))
                cornerRadius = host.dp(10).toFloat()
                setStroke(host.dp(1), Color.rgb(34, 55, 82))
            }
        }
        listOf(
            "— Green/red line: trend direction calculated from the latest candles.",
            "— Parallel lines: the trend's variation channel.",
            "— Blue line: recent technical support.",
            "— Gold line: recent technical resistance.",
            "— Arrow: the projected trend scenario based on the current structure.",
            "— Green/red crosses: bullish/bearish MA Cross signals.",
            "— BB / MA-EMA / MA Cross / Ichimoku / RSI / ADX: the toggleable indicators above."
        ).forEach { t ->
            legend.addView(TextView(host.root.context).apply {
                text = t
                textSize = 12.5f
                setTextColor(Color.rgb(195, 205, 220))
                setPadding(0, host.dp(3), 0, host.dp(3))
            })
        }
        box.addView(legend)
        host.content.addView(box, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(14)) })
    }

    private fun analysisLines(r: OracleAnalysisEngine.Result): List<String> {
        val f = r.factors
        return listOf(
            when { f[2] >= 75 -> "The trend is strongly positive, with price above the relevant averages."; f[2] >= 50 -> "The trend is constructive but lacks strong confirmation."; else -> "The trend is fragile and needs confirmation before an entry." },
            when { f[3] >= 70 -> "Momentum supports the move continuing."; f[3] >= 50 -> "Momentum is mixed and offers no clear edge."; else -> "Momentum is weak and reduces the quality of the move." },
            when { f[1] >= 90 -> "The breakout is confirmed by volume."; f[1] >= 60 -> "Price is testing the breakout, but confirmation is incomplete."; else -> "There is no convincing technical breakout." },
            if (r.volumeRatio >= 1.25) "Volume above the 20D average better validates the move." else "Volume does not decisively validate the current move.",
            when { f[5] >= 70 -> "Positioning relative to support and resistance is favorable."; f[5] >= 45 -> "Positioning relative to support and resistance is intermediate."; else -> "Positioning within the recent technical range is unfavorable." },
            if (f[7] >= 65) "Bollinger indicates a favorable technical positioning." else "Bollinger does not confirm a clear bullish extension.",
            if (f[8] >= 80) "Ichimoku confirms the bullish structure." else "Ichimoku does not confirm a complete bullish structure.",
            when { (r.adx ?: 0.0) >= 25 -> "ADX indicates a sufficiently strong trend."; (r.adx ?: 0.0) >= 20 -> "ADX indicates a moderate trend."; else -> "ADX indicates a weak or unconfirmed trend." },
            if (r.rsi > 70) "RSI is elevated; overbought risk should be monitored." else if (r.rsi < 35) "RSI is low; selling pressure remains relevant." else "RSI is in a relatively balanced technical zone.",
            "Volatility is reflected by ATR ${fmt(r.atrPct)}%; risk management is essential.",
            if (r.sma50 != null && r.sma200 != null && r.sma50 > r.sma200) "SMA50 above SMA200 supports the longer-term trend structure." else "The moving-average structure does not offer a complete bullish confirmation.",
            if (f[4] >= 65) "Volume flow supports interest in the current move." else "Volume flow does not offer a decisive confirmation.",
            "The technical context should be monitored alongside nearby support and volatility.",
            if (f[10] >= 65) "The technical risk/reward ratio is relatively favorable." else "The risk/reward ratio calls for caution before entry.",
            "Conclusion: the current setup should be re-evaluated dynamically at the next session."
        )
    }

    // Live universes (S&P 500 curated names, then the broader Nasdaq feed)
    // are tried first, so any real ticker gets its actual full name — the
    // hardcoded map below is only a fast-path/offline fallback for a
    // handful of very common names, not the primary source.
    // Shared with the Compare popup (OracleCompanyDataPopup.kt) so a ticker
    // resolves to the same full name everywhere in the app.
    private fun companyName(context: android.content.Context, t: String): String = resolvedCompanyName(context, t)

    private fun renderWatchlist(items: List<String>) {
        host.content.rebuildWithoutFlicker {
        host.content.removeAllViews()
        host.addSectionLabel("WATCHLIST • SAVED TICKERS")
        if (items.isEmpty()) {
            host.addCard("WATCHLIST EMPTY", "Add a ticker from Analysis. This list is separate from the Portfolio.")
            return@rebuildWithoutFlicker
        }

        val store = OracleWatchlistStore(host.root.context)
        val ctx = host.root.context
        if (items.any { OracleTickerScoreCache.isStale(ctx, it) } && !watchlistScoring) {
            watchlistScoring = true
            Thread {
                runCatching { OracleTickerScoreCache.refresh(ctx, items, maxFetches = 12) }
                host.root.post { watchlistScoring = false; if (host.root.isAttachedToWindow) renderWatchlist(store.load()) }
            }.start()
        }
        items.map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEachIndexed { index, ticker ->
                val rowBg = GradientDrawable().apply {
                    setColor(Color.rgb(7, 12, 23))
                    cornerRadius = host.dp(14).toFloat()
                    setStroke(host.dp(1), Color.rgb(45, 70, 105))
                }
                val row = LinearLayout(host.root.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(host.dp(8), host.dp(8), host.dp(8), host.dp(8))
                    background = rowBg
                }

                // Real Android Button: this is deliberately the primary navigation control.
                // Every saved ticker gets its own independent clickable control.
                val tickerButton = Button(host.root.context).apply {
                    text = ticker
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(host.dp(10), 0, host.dp(4), 0)
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    isAllCaps = false
                    minHeight = 0
                    minimumHeight = 0
                    contentDescription = "Open $ticker in Analysis"
                    setOnClickListener {
                        onWatchlistTickerClick(ticker)
                    }
                }
                row.addView(tickerButton, LinearLayout.LayoutParams(0, host.dp(84), 1f))
                row.addView(companyInfoButton(host, ticker), LinearLayout.LayoutParams(host.dp(28), host.dp(28)).apply { setMargins(0, 0, host.dp(8), 0); gravity = Gravity.CENTER_VERTICAL })

                // Live Growth-style score for the ticker (same engine as Growth),
                // refreshed in the background when older than an hour.
                val sc = OracleTickerScoreCache.get(host.root.context, ticker)
                val watchDemo = OracleDemo.active(host.root.context)
                val scoreBox = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                val scoreColor = when (sc?.signal) { "STRONG BUY" -> Color.rgb(120, 255, 45); "BUY" -> Color.rgb(145, 245, 35); "HOLD" -> Color.rgb(50, 220, 190); "WATCH" -> Color.rgb(255, 205, 45); "AVOID" -> Color.rgb(255, 90, 90); else -> Color.rgb(120, 130, 152) }
                scoreBox.addView(TextView(host.root.context).apply { text = if (watchDemo) OracleDemo.LOCK else sc?.let { "${it.score}" } ?: "\u2014"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(scoreColor); gravity = Gravity.CENTER })
                scoreBox.addView(TextView(host.root.context).apply { text = if (watchDemo) "locked" else sc?.signal ?: "scoring\u2026"; textSize = 8.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f; setTextColor(scoreColor); gravity = Gravity.CENTER })
                row.addView(scoreBox, LinearLayout.LayoutParams(host.dp(78), host.dp(84)))

                val openButton = Button(host.root.context).apply {
                    text = "›"
                    textSize = 30f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(host.accent)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    minHeight = 0
                    minimumHeight = 0
                    contentDescription = "Open $ticker in Analysis"
                    setOnClickListener {
                        onWatchlistTickerClick(ticker)
                    }
                }
                row.addView(openButton, LinearLayout.LayoutParams(host.dp(54), host.dp(84)))

                val deleteButton = Button(host.root.context).apply {
                    text = "DELETE"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(255, 105, 105))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    minHeight = 0
                    minimumHeight = 0
                    contentDescription = "Remove $ticker from Watchlist"
                    setOnClickListener {
                        val current = store.load().toMutableList()
                        current.removeAll { it.equals(ticker, true) }
                        store.save(current)
                        renderWatchlist(store.load())
                    }
                }
                row.addView(deleteButton, LinearLayout.LayoutParams(host.dp(112), host.dp(84)))

                host.content.addView(row, LinearLayout.LayoutParams(-1, host.dp(100)).apply {
                    setMargins(0, 0, 0, host.dp(12))
                })
                row.alpha = 0f
                row.translationY = host.dp(20).toFloat()
                row.animate().alpha(1f).translationY(0f).setStartDelay(index * 80L).setDuration(360L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                val rowStrokePx = host.dp(1)
                android.animation.ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                    duration = 2000L
                    startDelay = index * 160L
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        if (!row.isAttachedToWindow) { anim.cancel(); return@addUpdateListener }
                        val q = anim.animatedValue as Float
                        rowBg.setStroke((rowStrokePx * (1f + 0.7f * q)).toInt().coerceAtLeast(1), Color.argb((130 + 100 * q).toInt(), 75, 225, 255))
                    }
                }.start()
            }
        }
    }

    private fun renderActions(actions: List<OracleAction>) {
        host.addCard("ACTIONS", "Local signal engine — prioritized by score")
        if (actions.isEmpty()) return
        val buys = actions.count { it.action.equals("BUY", true) }
        val sells = actions.count { it.action.equals("SELL", true) }
        host.addCard("SIGNAL SUMMARY", "BUY $buys • HOLD ${actions.size - buys - sells} • SELL $sells\nTotal semnale ${actions.size}")
        actions.sortedByDescending { abs(it.score) }.take(50).forEachIndexed { i, a -> addItem("${i + 1}. ${a.action} • ${a.ticker}", "Scor ${fmt(a.score)}\n${a.reason}") }
    }

    private fun addItem(title: String, body: String) {
        val c = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(host.dp(16), host.dp(13), host.dp(16), host.dp(13))
            background = GradientDrawable().apply { setColor(Color.rgb(6, 10, 20)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(1), Color.rgb(34, 43, 65)) }
        }
        val row = LinearLayout(host.root.context).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(host.root.context).apply { text = "◆"; textSize = 9f; setTextColor(host.accent) }, LinearLayout.LayoutParams(host.dp(22), host.dp(22)))
        row.addView(TextView(host.root.context).apply { text = title.uppercase(); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(host.root.context).apply { text = "›"; textSize = 24f; setTextColor(host.accent) }, LinearLayout.LayoutParams(host.dp(24), host.dp(30)))
        c.addView(row)
        c.addView(TextView(host.root.context).apply { text = body; textSize = 14f; setTextColor(Color.rgb(175, 183, 201)); setPadding(host.dp(22), host.dp(5), 0, 0) })
        host.content.addView(c, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })
    }

    private fun fmt(v: Double) = "%.1f".format(Locale.US, v)
    private fun money(v: Double) = "%.2f USD".format(Locale.US, v)
    private fun moneyOrDash(v: Double?) = v?.let { money(it) } ?: "—"
    private class WatchlistEyeView(context: android.content.Context, private val sizePx: Int) : android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (sizePx * 0.055f).coerceAtLeast(2f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private var selected = false

        fun setSelectedState(value: Boolean) {
            selected = value
            paint.color = if (selected) Color.rgb(255, 210, 45) else Color.rgb(125, 135, 155)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val rx = width * 0.32f
            val ry = height * 0.22f
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
            canvas.drawCircle(cx, cy, width * 0.105f, paint)
            if (selected) {
                paint.style = android.graphics.Paint.Style.FILL
                canvas.drawCircle(cx, cy, width * 0.052f, paint)
                paint.style = android.graphics.Paint.Style.STROKE
            }
        }
    }

    /** Compare glyph drawn in the same thin-stroke style as WatchlistEyeView:
     *  two small candle/bar columns of different heights side by side, i.e.
     *  "this one against that one" — a plain \u21C4 arrow read as a swap/refresh. */
    private class CompareGlyphView(context: android.content.Context, private val sizePx: Int) : android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (sizePx * 0.055f).coerceAtLeast(2f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = Color.rgb(85, 205, 255)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val baseline = h * 0.72f
            val leftX = w * 0.34f; val rightX = w * 0.66f
            val barW = w * 0.17f
            // Left bar: taller (the winner); right bar: shorter.
            canvas.drawRect(leftX - barW / 2f, h * 0.30f, leftX + barW / 2f, baseline, paint)
            canvas.drawRect(rightX - barW / 2f, h * 0.46f, rightX + barW / 2f, baseline, paint)
            canvas.drawLine(w * 0.18f, baseline, w * 0.82f, baseline, paint)
        }
    }


    /** Faceless classical bust silhouette (Greek laurel-crowned or plain Roman
     *  style) — a rounded head, draped shoulders, optional laurel band. No
     *  facial features are drawn, which keeps it dignified and low-risk to
     *  render well, rather than attempting a face. */
    private class ClassicalBustView(context: android.content.Context, private val hasLaurel: Boolean) : android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val ink = Color.rgb(205, 202, 195)

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            val headR = kotlin.math.min(w, h) * 0.22f
            val headCy = headR * 1.3f

            paint.color = ink; paint.alpha = 205; paint.strokeWidth = w * 0.035f
            canvas.drawCircle(cx, headCy, headR, paint)

            val body = android.graphics.Path().apply {
                moveTo(cx - headR * 0.8f, headCy + headR * 0.75f)
                cubicTo(cx - w * 0.40f, h * 0.60f, cx - w * 0.42f, h * 0.95f, cx - w * 0.36f, h * 0.97f)
                lineTo(cx + w * 0.36f, h * 0.97f)
                cubicTo(cx + w * 0.42f, h * 0.95f, cx + w * 0.40f, h * 0.60f, cx + headR * 0.8f, headCy + headR * 0.75f)
            }
            canvas.drawPath(body, paint)

            paint.strokeWidth = w * 0.014f; paint.alpha = 120
            canvas.drawLine(cx - w * 0.16f, headCy + headR * 1.2f, cx - w * 0.20f, h * 0.90f, paint)
            canvas.drawLine(cx + w * 0.16f, headCy + headR * 1.2f, cx + w * 0.20f, h * 0.90f, paint)

            if (hasLaurel) {
                paint.color = ink; paint.alpha = 210; paint.strokeWidth = w * 0.02f
                val bounds = android.graphics.RectF(cx - headR * 1.05f, headCy - headR * 1.05f, cx + headR * 1.05f, headCy + headR * 1.05f)
                canvas.drawArc(bounds, 195f, 150f, false, paint)
                paint.strokeWidth = w * 0.012f
                for (deg in intArrayOf(205, 230, 255, 280, 305, 330)) {
                    val rad = Math.toRadians(deg.toDouble())
                    val r1 = headR * 1.05f
                    val px = cx + r1 * kotlin.math.cos(rad).toFloat()
                    val py = headCy + r1 * kotlin.math.sin(rad).toFloat()
                    val leafRad = rad + Math.toRadians(35.0)
                    val len = w * 0.06f
                    canvas.drawLine(px, py, px + len * kotlin.math.cos(leafRad).toFloat(), py + len * kotlin.math.sin(leafRad).toFloat(), paint)
                }
            }
        }
    }

    /** A handful of other classical/scholarly line drawings, for variety
     *  alongside the busts: a Doric column, a pair of compasses (geometry),
     *  and a balance scale. Same dignified single-stroke style. */
    private class KnowledgeMotifView(context: android.content.Context, private val kind: Int) : android.view.View(context) {
        companion object { const val KIND_COLUMN = 0; const val KIND_COMPASS = 1; const val KIND_SCALE = 2 }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val ink = Color.rgb(205, 202, 195)

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cx = w * 0.5f
            paint.color = ink; paint.alpha = 205

            when (kind) {
                KIND_COLUMN -> {
                    val baseH = h * 0.07f; val baseW = w * 0.62f
                    val capH = h * 0.08f
                    val shaftW = w * 0.34f
                    paint.strokeWidth = w * 0.03f
                    canvas.drawRect(cx - baseW / 2f, h * 0.93f - baseH, cx + baseW / 2f, h * 0.93f, paint)
                    canvas.drawRect(cx - shaftW / 2f, h * 0.14f + capH, cx + shaftW / 2f, h * 0.93f - baseH, paint)
                    canvas.drawRect(cx - baseW * 0.46f, h * 0.14f, cx + baseW * 0.46f, h * 0.14f + capH, paint)
                    paint.strokeWidth = w * 0.014f; paint.alpha = 120
                    for (i in -1..1) canvas.drawLine(cx + i * shaftW * 0.32f, h * 0.14f + capH + h * 0.02f, cx + i * shaftW * 0.32f, h * 0.93f - baseH - h * 0.02f, paint)
                }
                KIND_COMPASS -> {
                    val pivotY = h * 0.10f
                    val footY = h * 0.90f
                    val spread = w * 0.34f
                    paint.strokeWidth = w * 0.028f
                    canvas.drawCircle(cx, pivotY, w * 0.035f, paint)
                    canvas.drawLine(cx, pivotY, cx - spread, footY, paint)
                    canvas.drawLine(cx, pivotY, cx + spread, footY, paint)
                    paint.strokeWidth = w * 0.02f; paint.alpha = 160
                    canvas.drawLine(cx - spread * 0.7f, h * 0.62f, cx + spread * 0.7f, h * 0.62f, paint)
                }
                else -> { // KIND_SCALE
                    val topY = h * 0.10f
                    val postBottomY = h * 0.88f
                    val beamW = w * 0.78f
                    val beamY = topY + h * 0.06f
                    paint.strokeWidth = w * 0.022f
                    canvas.drawLine(cx, topY, cx, postBottomY, paint)
                    canvas.drawLine(cx - beamW / 2f, beamY, cx + beamW / 2f, beamY, paint)
                    canvas.drawLine(cx - w * 0.16f, postBottomY, cx + w * 0.16f, postBottomY, paint)
                    paint.strokeWidth = w * 0.014f
                    canvas.drawLine(cx - beamW / 2f, beamY, cx - beamW / 2f, beamY + h * 0.22f, paint)
                    canvas.drawLine(cx + beamW / 2f, beamY, cx + beamW / 2f, beamY + h * 0.22f, paint)
                    canvas.drawArc(android.graphics.RectF(cx - beamW / 2f - w * 0.13f, beamY + h * 0.22f, cx - beamW / 2f + w * 0.13f, beamY + h * 0.22f + h * 0.09f), 0f, 180f, false, paint)
                    canvas.drawArc(android.graphics.RectF(cx + beamW / 2f - w * 0.13f, beamY + h * 0.22f, cx + beamW / 2f + w * 0.13f, beamY + h * 0.22f + h * 0.09f), 0f, 180f, false, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    canvas.drawCircle(cx, beamY, w * 0.022f, paint)
                    paint.style = android.graphics.Paint.Style.STROKE
                }
            }
        }
    }

}

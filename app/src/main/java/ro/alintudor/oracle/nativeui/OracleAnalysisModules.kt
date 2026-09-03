package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
        @Volatile private var tickerDraft: String = ""
        fun setTickerDraft(ticker: String) { tickerDraft = ticker.trim().uppercase(Locale.US) }
    }

    fun render(actions: List<OracleAction> = emptyList(), knowledge: List<OracleKnowledgeItem> = emptyList(), positions: List<OraclePosition> = emptyList(), history: List<OracleHistoryPoint> = emptyList(), watchlist: List<String> = OracleWatchlistStore(host.root.context).load()) {
        host.content.removeAllViews()
        val p = OracleAnalytics.normalize(positions)
        val computed = OracleAnalytics.actions(p, history)
        when (moduleTitle) {
            "GROWTH" -> renderGrowth()
            "ANALYSIS" -> renderAnalysis()
            "WATCHLIST" -> renderWatchlist(watchlist)
            "KNOWLEDGE" -> renderKnowledge(knowledge)
            else -> renderActions(if (computed.isNotEmpty()) computed else actions)
        }
    }

    private fun renderGrowth() {
        val r = OracleRepository(host.root.context)
        OracleGrowthModule(host).render(r.cachedGrowth(), r.cachedNews())
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

        fun run() {
            val t = input.text.toString().trim().uppercase(Locale.US)
            if (t.isBlank()) {
                input.error = "Enter a ticker"
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
        headline.addView(TextView(host.root.context).apply {
            text = r.ticker
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
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
                if (present) current.removeAll { it.equals(watchTicker, true) } else current.add(watchTicker)
                watchStore.save(current)
                updateWatchUi(!present, this)
                Toast.makeText(host.root.context, if (!present) "$watchTicker added to Watchlist" else "$watchTicker removed from Watchlist", Toast.LENGTH_SHORT).show()
            }
        }
        headline.addView(watchEye, LinearLayout.LayoutParams(host.dp(42), host.dp(42)).apply { setMargins(host.dp(4), 0, host.dp(8), 0) })
        headline.addView(TextView(host.root.context).apply {
            text = money(r.price)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(45, 232, 92))
            gravity = Gravity.END
        })
        top.addView(headline)
        top.addView(TextView(host.root.context).apply {
            text = companyName(r.ticker)
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
                    topBg.setStroke(strokePx, Color.argb((150 + 105 * q).toInt(), ar, ag, ab))
                }
            }.start()
        }

        // ANALYSIS_PARAMETERS_V8
        // All market-relevant values are presented in one two-column matrix:
        // Oracle factors + supplementary technical indicators + fundamentals.
        host.addSectionLabel("RELEVANT MARKET PARAMETERS")
        val relevantGrid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        val f = r.fundamentals
        val relevantParameters = mutableListOf<Pair<String, String>>()

        // Oracle factors: rawValues[0] is internal News; visible factors start at rawValues[1].
        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            if (!name.equals("Fundamentals", ignoreCase = true)) {
                relevantParameters.add(name to (r.rawValues.getOrNull(i + 1) ?: "Value unavailable"))
            }
        }

        // Supplementary technical indicators.
        relevantParameters.add("RSI (14)" to fmt(r.rsi))
        relevantParameters.add("MACD (12/26)" to metricPair(r.macd, r.macdSignal))
        relevantParameters.add("52W HIGH / LOW" to "${moneyOrDash(r.week52High)} / ${moneyOrDash(r.week52Low)}")
        relevantParameters.add("ATR" to "${money(r.atrValue)}  •  ${fmt(r.atrPct)}%")

        // Fundamentals — kept in the same matrix, not in a separate section.
        relevantParameters.add("Sector" to (f?.sector ?: r.sector ?: "—"))
        relevantParameters.add("Industry" to (f?.industry ?: "—"))
        relevantParameters.add("P/E" to num2(f?.trailingPe))
        relevantParameters.add("Fwd P/E" to num2(f?.forwardPe))
        relevantParameters.add("P/B" to num2(f?.priceToBook))
        relevantParameters.add("Revenue growth (YoY)" to pctFund(f?.revenueGrowth))
        relevantParameters.add("Earnings growth" to pctFund(f?.earningsGrowth))
        relevantParameters.add("Net margin" to pctFund(f?.profitMargin))
        relevantParameters.add("Operating margin" to pctFund(f?.operatingMargin))
        relevantParameters.add("ROE" to pctFund(f?.returnOnEquity))
        relevantParameters.add("D/E" to num2(f?.debtToEquity))
        relevantParameters.add("Current ratio" to num2(f?.currentRatio))
        relevantParameters.add("Quick ratio" to num2(f?.quickRatio))
        relevantParameters.add("Beta" to num2(f?.beta))
        relevantParameters.add("Market cap" to capText(f?.marketCap))

        addMetricGrid(relevantGrid, relevantParameters)
        host.content.addView(relevantGrid, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(10))
        })

        host.addSectionLabel("ORACLE ANALYSIS")
        val card = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 12, 23))
                cornerRadius = host.dp(15).toFloat()
                setStroke(host.dp(1), Color.rgb(34, 55, 82))
            }
        }
        analysisLines(r).forEach { line ->
            card.addView(TextView(host.root.context).apply {
                text = "— $line"
                textSize = 13f
                setTextColor(Color.rgb(205, 213, 228))
                setPadding(0, host.dp(4), 0, host.dp(4))
            })
        }
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(12)) })

        addTechnicalChart(r.ticker)

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
            if (present) current.removeAll { it.equals(watchTicker, true) } else current.add(watchTicker)
            watchStore.save(current)
            updateWatchUi(!present, watchEye)
            Toast.makeText(host.root.context, if (!present) "$watchTicker added to Watchlist" else "$watchTicker removed from Watchlist", Toast.LENGTH_SHORT).show()
        }
        host.content.addView(watchButton, LinearLayout.LayoutParams(-1, host.dp(50)).apply { setMargins(0, 0, 0, host.dp(16)) })

// BUILD_VERSION_BOTTOM_V1
host.content.addView(TextView(host.root.context).apply {
    text = "ORACLE • V6g-FINAL-B514"
    textSize = 10f
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = .08f
    gravity = Gravity.CENTER
    setTextColor(Color.rgb(110, 120, 140))
    setPadding(0, host.dp(6), 0, host.dp(18))
}, LinearLayout.LayoutParams(-1, -2))
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
            "— Green/red dots: bullish/bearish MA Cross signals.",
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

    private fun companyName(t: String) = when (t) {
        "AAOI" -> "Applied Optoelectronics, Inc."
        "APLD" -> "Applied Digital Corporation"
        "NVDA" -> "NVIDIA Corporation"; "AAPL" -> "Apple Inc."; "MSFT" -> "Microsoft Corporation"; "AMZN" -> "Amazon.com, Inc."; "GOOGL" -> "Alphabet Inc."; "META" -> "Meta Platforms, Inc."; "TSLA" -> "Tesla, Inc."; "AMD" -> "Advanced Micro Devices, Inc."; "AVGO" -> "Broadcom Inc."; "NFLX" -> "Netflix, Inc."; else -> t
    }

    private fun renderWatchlist(items: List<String>) {
        host.content.removeAllViews()
        host.addSectionLabel("WATCHLIST • SAVED TICKERS")
        if (items.isEmpty()) {
            host.addCard("WATCHLIST EMPTY", "Add a ticker from Analysis. This list is separate from the Portfolio.")
            return
        }

        val store = OracleWatchlistStore(host.root.context)
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
                        rowBg.setStroke(rowStrokePx, Color.argb((130 + 100 * q).toInt(), 75, 225, 255))
                    }
                }.start()
            }
    }

    private fun renderKnowledge(items: List<OracleKnowledgeItem>) {
        val card = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(18), host.dp(16), host.dp(18), host.dp(16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 11, 22))
                cornerRadius = host.dp(16).toFloat()
                setStroke(host.dp(1), Color.rgb(255, 205, 55))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Open Knowledge: https://alintudor.ro/knowledge/"
            setOnClickListener {
                runCatching { host.root.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://alintudor.ro/knowledge/"))) }
            }
        }
        val headerRow = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(KnowledgeGraphicView(host.root.context), LinearLayout.LayoutParams(host.dp(56), host.dp(56)).apply { setMargins(0, 0, host.dp(14), 0) })
        val titleCol = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(TextView(host.root.context).apply { text = "KNOWLEDGE"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .04f; setTextColor(Color.WHITE) })
        titleCol.addView(TextView(host.root.context).apply { text = "Oracle library — local content"; textSize = 13f; setTextColor(Color.rgb(190, 198, 215)); setPadding(0, host.dp(3), 0, 0) })
        headerRow.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(headerRow)
        card.addView(TextView(host.root.context).apply {
            text = "OPEN: https://alintudor.ro/knowledge/"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 205, 55))
            setPadding(0, host.dp(12), 0, 0)
        })
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(16)) })

        // Fills the space below the header card with black-and-white pencil-sketch
        // style illustrations — books and a classical wise figure — instead of
        // colored topic tiles.
        host.addSectionLabel("WISDOM, SKETCHED")
        val sketchRow = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL }
        host.content.addView(sketchRow, LinearLayout.LayoutParams(-1, host.dp(150)).apply { setMargins(0, 0, 0, host.dp(10)) })
        val sketchKinds = listOf(KnowledgeSketchView.KIND_SAGE, KnowledgeSketchView.KIND_BOOKS, KnowledgeSketchView.KIND_OWL)
        sketchKinds.forEachIndexed { index, kind ->
            val panel = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.rgb(10, 10, 10))
                    cornerRadius = host.dp(14).toFloat()
                    setStroke(host.dp(1), Color.rgb(90, 90, 90))
                }
            }
            panel.addView(KnowledgeSketchView(host.root.context, kind), LinearLayout.LayoutParams(-1, -1))
            sketchRow.addView(panel, LinearLayout.LayoutParams(0, -1, 1f).apply {
                setMargins(if (index == 0) 0 else host.dp(5), 0, if (index == sketchKinds.size - 1) 0 else host.dp(5), 0)
            })
            panel.alpha = 0f
            panel.animate().alpha(1f).setStartDelay(index * 160L).setDuration(500L).start()
        }

        if (items.isEmpty()) return
        items.sortedByDescending { it.publishedAt }.forEach { addItem(it.title, "${it.category}\n${it.content}") }
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
    private fun signed(v: Double) = if (v >= 0) "+${fmt(v)}" else fmt(v)
    private fun factorColor(v: Double) = when { v >= 75 -> Color.rgb(105, 245, 35); v >= 55 -> Color.rgb(255, 210, 55); else -> Color.rgb(255, 90, 90) }
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

    /** Small animated open-book glyph with a pulsing "insight" spark, used on the Knowledge card. */
    private class KnowledgeGraphicView(context: android.content.Context) : android.view.View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val startNanos = System.nanoTime()

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val time = (System.nanoTime() - startNanos) / 1_000_000_000.0
            val cx = w / 2f; val cy = h * 0.60f
            val bookW = w * 0.42f; val bookH = h * 0.30f

            paint.strokeWidth = (w * 0.045f).coerceAtLeast(2f)
            paint.color = Color.rgb(255, 205, 55)
            paint.alpha = 235
            val left = android.graphics.Path().apply {
                moveTo(cx, cy - bookH * 0.10f)
                cubicTo(cx - bookW * 0.35f, cy - bookH * 0.55f, cx - bookW, cy - bookH * 0.30f, cx - bookW, cy + bookH * 0.35f)
                cubicTo(cx - bookW * 0.35f, cy + bookH * 0.10f, cx - bookW * 0.1f, cy + bookH * 0.15f, cx, cy + bookH * 0.45f)
            }
            canvas.drawPath(left, paint)
            val right = android.graphics.Path().apply {
                moveTo(cx, cy - bookH * 0.10f)
                cubicTo(cx + bookW * 0.35f, cy - bookH * 0.55f, cx + bookW, cy - bookH * 0.30f, cx + bookW, cy + bookH * 0.35f)
                cubicTo(cx + bookW * 0.35f, cy + bookH * 0.10f, cx + bookW * 0.1f, cy + bookH * 0.15f, cx, cy + bookH * 0.45f)
            }
            canvas.drawPath(right, paint)

            paint.strokeWidth = (w * 0.016f).coerceAtLeast(1f)
            paint.alpha = 150
            canvas.drawLine(cx - bookW * 0.55f, cy - bookH * 0.02f, cx - bookW * 0.15f, cy + bookH * 0.14f, paint)
            canvas.drawLine(cx + bookW * 0.55f, cy - bookH * 0.02f, cx + bookW * 0.15f, cy + bookH * 0.14f, paint)

            // Pulsing "insight" spark above the book.
            val q = (0.5 + 0.5 * kotlin.math.sin(time * 1.6)).toFloat()
            val sparkY = cy - bookH * 1.0f
            val sparkR = w * (0.09f + 0.02f * q)
            paint.strokeWidth = (w * 0.03f).coerceAtLeast(1.5f)
            paint.color = Color.rgb(120, 220, 255)
            paint.alpha = (110 + 130 * q).toInt()
            for (i in 0 until 4) {
                val a = Math.PI / 2.0 + i * Math.PI / 2.0
                val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                canvas.drawLine(cx + dx * sparkR * 0.55f, sparkY + dy * sparkR * 0.55f, cx + dx * sparkR, sparkY + dy * sparkR, paint)
            }
            paint.style = android.graphics.Paint.Style.FILL
            paint.alpha = (150 + 100 * q).toInt()
            canvas.drawCircle(cx, sparkY, w * 0.045f, paint)
            paint.style = android.graphics.Paint.Style.STROKE

            postInvalidateDelayed(60L)
        }
    }

    /** Black-and-white pencil-sketch style illustrations: a classical wise figure,
     *  a stack of books, and an owl with a scroll. Hand-drawn look via doubled,
     *  slightly-offset strokes and light diagonal hatching; monochrome only. */
    private class KnowledgeSketchView(context: android.content.Context, private val kind: Int) : android.view.View(context) {
        companion object { const val KIND_SAGE = 0; const val KIND_BOOKS = 1; const val KIND_OWL = 2 }
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val startNanos = System.nanoTime()
        private val ink = Color.rgb(215, 212, 205) // graphite-on-dark-paper tone

        private fun sketchLine(c: android.graphics.Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, a: Int, jitter: Float) {
            paint.strokeWidth = w; paint.color = ink; paint.alpha = a
            c.drawLine(x1, y1, x2, y2, paint)
            // A second, faint offset stroke gives the doubled hand-drawn pencil look.
            paint.alpha = (a * 0.45f).toInt()
            c.drawLine(x1 + jitter, y1 - jitter, x2 + jitter, y2 - jitter, paint)
        }
        private fun sketchPath(c: android.graphics.Canvas, path: android.graphics.Path, w: Float, a: Int) {
            paint.strokeWidth = w; paint.color = ink; paint.alpha = a
            c.drawPath(path, paint)
        }
        private fun hatch(c: android.graphics.Canvas, cx: Float, cy: Float, r: Float, lines: Int, a: Int) {
            paint.strokeWidth = 1.2f; paint.color = ink; paint.alpha = a
            for (i in 0 until lines) {
                val t = i / lines.toFloat()
                val x = cx - r + t * 2f * r
                c.drawLine(x, cy - r * 0.5f, x - r * 0.3f, cy + r * 0.5f, paint)
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val time = (System.nanoTime() - startNanos) / 1_000_000_000.0
            // Slow graphite shimmer instead of color — keeps it monochrome.
            val shimmer = (0.75 + 0.25 * kotlin.math.sin(time * 0.9)).toFloat()
            val baseAlpha = (215 * shimmer).toInt().coerceIn(150, 235)
            val cx = w / 2f

            when (kind) {
                KIND_SAGE -> {
                    val cy = h * 0.36f; val headR = w * 0.17f
                    // Head
                    sketchPath(canvas, android.graphics.Path().apply { addCircle(cx, cy, headR, android.graphics.Path.Direction.CW) }, w * 0.028f, baseAlpha)
                    // Beard
                    val beard = android.graphics.Path().apply {
                        moveTo(cx - headR * 0.75f, cy + headR * 0.55f)
                        cubicTo(cx - headR * 0.5f, cy + headR * 1.9f, cx + headR * 0.5f, cy + headR * 1.9f, cx + headR * 0.75f, cy + headR * 0.55f)
                    }
                    sketchPath(canvas, beard, w * 0.024f, baseAlpha)
                    hatch(canvas, cx, cy + headR * 1.15f, headR * 0.7f, 7, (baseAlpha * 0.5f).toInt())
                    // Simple hair strokes on top
                    for (i in -2..2) sketchLine(canvas, cx + i * headR * 0.28f, cy - headR * 0.95f, cx + i * headR * 0.32f, cy - headR * 0.55f, w * 0.014f, (baseAlpha * 0.8f).toInt(), 1.2f)
                    // Shoulders / toga
                    val toga = android.graphics.Path().apply {
                        moveTo(cx - w * 0.34f, h * 0.86f)
                        lineTo(cx - headR * 0.85f, cy + headR * 1.35f)
                        lineTo(cx + headR * 0.85f, cy + headR * 1.35f)
                        lineTo(cx + w * 0.34f, h * 0.86f)
                    }
                    sketchPath(canvas, toga, w * 0.022f, baseAlpha)
                    for (i in 0 until 4) sketchLine(canvas, cx - w * 0.18f + i * w * 0.12f, cy + headR * 1.5f, cx - w * 0.14f + i * w * 0.12f, h * 0.83f, w * 0.012f, (baseAlpha * 0.55f).toInt(), 0.8f)
                }
                KIND_BOOKS -> {
                    val baseY = h * 0.80f
                    val widths = floatArrayOf(0.62f, 0.54f, 0.46f)
                    var y = baseY
                    widths.forEachIndexed { i, wf ->
                        val bw = w * wf; val bh = h * 0.10f
                        val tilt = if (i % 2 == 0) -0.02f else 0.02f
                        val rect = android.graphics.Path().apply {
                            moveTo(cx - bw / 2f, y)
                            lineTo(cx + bw / 2f + w * tilt, y - h * 0.01f)
                            lineTo(cx + bw / 2f + w * tilt, y - bh)
                            lineTo(cx - bw / 2f, y - bh - h * 0.01f)
                            close()
                        }
                        sketchPath(canvas, rect, w * 0.02f, baseAlpha)
                        sketchLine(canvas, cx - bw / 2f + w * 0.03f, y - bh * 0.5f, cx + bw / 2f - w * 0.03f, y - bh * 0.5f, w * 0.01f, (baseAlpha * 0.5f).toInt(), 0.6f)
                        y -= bh + h * 0.015f
                    }
                    // Open book on top, pages fanned.
                    val ocy = y - h * 0.02f
                    val obw = w * 0.34f; val obh = h * 0.09f
                    val leftPage = android.graphics.Path().apply {
                        moveTo(cx, ocy); cubicTo(cx - obw * 0.5f, ocy - obh * 1.4f, cx - obw, ocy - obh * 0.4f, cx - obw, ocy + obh * 0.3f)
                        cubicTo(cx - obw * 0.5f, ocy + obh * 0.1f, cx - obw * 0.1f, ocy + obh * 0.15f, cx, ocy + obh * 0.4f)
                    }
                    val rightPage = android.graphics.Path().apply {
                        moveTo(cx, ocy); cubicTo(cx + obw * 0.5f, ocy - obh * 1.4f, cx + obw, ocy - obh * 0.4f, cx + obw, ocy + obh * 0.3f)
                        cubicTo(cx + obw * 0.5f, ocy + obh * 0.1f, cx + obw * 0.1f, ocy + obh * 0.15f, cx, ocy + obh * 0.4f)
                    }
                    sketchPath(canvas, leftPage, w * 0.018f, baseAlpha)
                    sketchPath(canvas, rightPage, w * 0.018f, baseAlpha)
                }
                else -> { // KIND_OWL
                    val cy = h * 0.42f; val bodyRx = w * 0.24f; val bodyRy = h * 0.22f
                    sketchPath(canvas, android.graphics.Path().apply { addOval(cx - bodyRx, cy - bodyRy, cx + bodyRx, cy + bodyRy, android.graphics.Path.Direction.CW) }, w * 0.024f, baseAlpha)
                    // Ear tufts
                    sketchLine(canvas, cx - bodyRx * 0.55f, cy - bodyRy * 0.85f, cx - bodyRx * 0.75f, cy - bodyRy * 1.5f, w * 0.02f, baseAlpha, 1f)
                    sketchLine(canvas, cx + bodyRx * 0.55f, cy - bodyRy * 0.85f, cx + bodyRx * 0.75f, cy - bodyRy * 1.5f, w * 0.02f, baseAlpha, 1f)
                    // Eyes
                    val eyeR = bodyRx * 0.28f
                    sketchPath(canvas, android.graphics.Path().apply { addCircle(cx - bodyRx * 0.4f, cy - bodyRy * 0.1f, eyeR, android.graphics.Path.Direction.CW) }, w * 0.016f, baseAlpha)
                    sketchPath(canvas, android.graphics.Path().apply { addCircle(cx + bodyRx * 0.4f, cy - bodyRy * 0.1f, eyeR, android.graphics.Path.Direction.CW) }, w * 0.016f, baseAlpha)
                    paint.style = android.graphics.Paint.Style.FILL; paint.color = ink; paint.alpha = (baseAlpha * 0.8f).toInt()
                    canvas.drawCircle(cx - bodyRx * 0.4f, cy - bodyRy * 0.1f, eyeR * 0.35f, paint)
                    canvas.drawCircle(cx + bodyRx * 0.4f, cy - bodyRy * 0.1f, eyeR * 0.35f, paint)
                    paint.style = android.graphics.Paint.Style.STROKE
                    // Beak
                    val beak = android.graphics.Path().apply {
                        moveTo(cx - eyeR * 0.4f, cy + bodyRy * 0.05f); lineTo(cx, cy + bodyRy * 0.35f); lineTo(cx + eyeR * 0.4f, cy + bodyRy * 0.05f)
                    }
                    sketchPath(canvas, beak, w * 0.016f, baseAlpha)
                    hatch(canvas, cx, cy + bodyRy * 0.4f, bodyRx * 0.55f, 6, (baseAlpha * 0.4f).toInt())
                    // Scroll beneath the owl.
                    val sy = h * 0.82f; val sw = w * 0.34f
                    sketchPath(canvas, android.graphics.Path().apply {
                        moveTo(cx - sw, sy); cubicTo(cx - sw * 0.6f, sy - h * 0.04f, cx + sw * 0.6f, sy - h * 0.04f, cx + sw, sy)
                    }, w * 0.02f, baseAlpha)
                    sketchPath(canvas, android.graphics.Path().apply { addCircle(cx - sw, sy, w * 0.025f, android.graphics.Path.Direction.CW) }, w * 0.018f, baseAlpha)
                    sketchPath(canvas, android.graphics.Path().apply { addCircle(cx + sw, sy, w * 0.025f, android.graphics.Path.Direction.CW) }, w * 0.018f, baseAlpha)
                }
            }
            postInvalidateDelayed(90L)
        }
    }

}

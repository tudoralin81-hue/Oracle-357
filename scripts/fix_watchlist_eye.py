from pathlib import Path

modules = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = modules.read_text()

# Analysis: keep the eye control directly inside the ticker headline.
old_headline = '''        val headline = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headline.addView(TextView(host.root.context).apply {
            text = r.ticker
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headline.addView(TextView(host.root.context).apply {
            text = money(r.price)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(45, 232, 92))
            gravity = Gravity.END
        })'''
new_headline = '''        val headline = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headline.addView(TextView(host.root.context).apply {
            text = r.ticker
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val watchStore = OracleWatchlistStore(host.root.context)
        val watchTicker = r.ticker.trim().uppercase(Locale.US)
        val watchEye = WatchlistEyeView(host.root.context, host.dp(42)).apply {
            tag = "oracle_watchlist_eye_direct"
            isClickable = true
            isFocusable = true
            contentDescription = "Adaugă sau scoate $watchTicker din Watchlist"
            setSelectedState(watchStore.load().any { it.equals(watchTicker, true) })
            setOnClickListener {
                val current = watchStore.load().toMutableList()
                val present = current.any { it.equals(watchTicker, true) }
                if (present) current.removeAll { it.equals(watchTicker, true) } else current.add(watchTicker)
                watchStore.save(current)
                setSelectedState(!present)
                Toast.makeText(host.root.context, if (!present) "$watchTicker adăugat în Watchlist" else "$watchTicker scos din Watchlist", Toast.LENGTH_SHORT).show()
            }
        }
        headline.addView(watchEye, LinearLayout.LayoutParams(host.dp(42), host.dp(42)).apply { setMargins(host.dp(4), 0, host.dp(8), 0) })
        headline.addView(TextView(host.root.context).apply {
            text = money(r.price)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(45, 232, 92))
            gravity = Gravity.END
        })'''
if old_headline in s:
    s = s.replace(old_headline, new_headline, 1)

# Watchlist: make ticker and arrow explicit child navigation targets. Delete stays independent.
old_watch_rows = '''            val row = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(15), host.dp(12), host.dp(10), host.dp(12))
                background = GradientDrawable().apply { setColor(Color.rgb(7, 12, 23)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(1), Color.rgb(45, 70, 105)) }
                isClickable = true
                isFocusable = true
                contentDescription = "$t — deschide în Analysis"
                setOnClickListener { onWatchlistTickerClick(t) }
            }
            row.addView(TextView(host.root.context).apply { text = t; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(host.root.context).apply {
                text = "›"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(host.accent); gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(host.dp(30), host.dp(38)))'''
new_watch_rows = '''            val row = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(15), host.dp(12), host.dp(10), host.dp(12))
                background = GradientDrawable().apply { setColor(Color.rgb(7, 12, 23)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(1), Color.rgb(45, 70, 105)) }
                isClickable = true
                isFocusable = true
                contentDescription = "$t — deschide în Analysis"
            }
            val tickerView = TextView(host.root.context).apply {
                text = t; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
            }
            row.addView(tickerView, LinearLayout.LayoutParams(0, -2, 1f))
            val arrowView = TextView(host.root.context).apply {
                text = "›"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(host.accent); gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
            }
            row.addView(arrowView, LinearLayout.LayoutParams(host.dp(30), host.dp(38)))'''
if old_watch_rows in s:
    s = s.replace(old_watch_rows, new_watch_rows, 1)

old_child_navigation = '''            // Explicit child navigation: ticker and arrow both open the same Analysis target.
        for (j in 0 until row.childCount) {
            val child = row.getChildAt(j)
            if (child is TextView && child.text?.toString()?.trim() == t) {
                child.isClickable = true
                child.isFocusable = true
                child.setOnClickListener { onWatchlistTickerClick(t) }
            } else if (child is TextView && child.text?.toString()?.trim() == "›") {
                child.isClickable = true
                child.isFocusable = true
                child.setOnClickListener { onWatchlistTickerClick(t) }
            }
        }
        host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) })'''
new_child_navigation = '''            // Whole row is also a navigation target; delete remains its own clickable child.
            row.setOnClickListener { onWatchlistTickerClick(t) }
            host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) })'''
if old_child_navigation in s:
    s = s.replace(old_child_navigation, new_child_navigation, 1)

# When Analysis was opened from a Watchlist row, run the selected ticker immediately.
auto_marker = '''        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { run(); true } else false }
'''
auto_code = auto_marker + '''        if (tickerDraft.isNotBlank() && !s.contains("AUTO_WATCHLIST_ANALYZE")) {
            input.postDelayed({ run() }, 220L)
        }
'''
# The marker is intentionally simple; the guard below prevents duplicate insertion on repeated CI runs.
if 'AUTO_WATCHLIST_ANALYZE' not in s:
    auto_code = '''        input.setOnEditorActionListener { _, actionId, _ -> if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { run(); true } else false }
        // AUTO_WATCHLIST_ANALYZE: a Watchlist navigation opens the actual ticker analysis, not only the input field.
        if (tickerDraft.isNotBlank()) input.postDelayed({ run() }, 220L)
'''
    if auto_marker in s:
        s = s.replace(auto_marker, auto_code, 1)

if 'private class WatchlistEyeView' not in s:
    class_code = '''
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
'''
    pos = s.rfind('\n}')
    if pos < 0:
        raise SystemExit('Could not locate end of OracleAnalysisModules.kt')
    s = s[:pos] + class_code + s[pos:]

modules.write_text(s)

main = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
m = main.read_text()
m = m.replace('        if (key == "analysis") OracleAnalysisWatchlistEyeOverlay.install(host)\n', '', 1)
main.write_text(m)

if 'oracle_watchlist_eye_direct' not in s:
    raise SystemExit('Watchlist eye patch did not apply')
if 'AUTO_WATCHLIST_ANALYZE' not in s:
    raise SystemExit('Watchlist auto-analysis patch did not apply')
if 'OracleAnalysisWatchlistEyeOverlay.install(host)' in m:
    raise SystemExit('Legacy overlay call still present')

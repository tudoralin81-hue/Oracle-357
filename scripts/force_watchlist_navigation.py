from pathlib import Path

p = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = p.read_text()

old = '''            val tickerView = TextView(host.root.context).apply {
                text = t; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
            }'''
new = '''            val tickerView = TextView(host.root.context).apply {
                text = t; textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        performClick()
                        true
                    } else true
                }
            }'''
if old in s:
    s = s.replace(old, new, 1)

old = '''            val arrowView = TextView(host.root.context).apply {
                text = "›"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(host.accent); gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
            }'''
new = '''            val arrowView = TextView(host.root.context).apply {
                text = "›"; textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(host.accent); gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onWatchlistTickerClick(t) }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        performClick()
                        true
                    } else true
                }
            }'''
if old in s:
    s = s.replace(old, new, 1)

old = '''            row.setOnClickListener { onWatchlistTickerClick(t) }'''
new = '''            row.setOnClickListener { onWatchlistTickerClick(t) }
            row.setOnTouchListener { v, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    v.performClick()
                    true
                } else false
            }'''
if old in s and 'row.setOnTouchListener' not in s:
    s = s.replace(old, new, 1)

p.write_text(s)

m = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
ms = m.read_text()
oldm = '''    private fun openWatchlistTicker(ticker: String) {
        OracleSimpleModule.setTickerDraft(ticker)
        openModule("analysis")
    }'''
newm = '''    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        OracleSimpleModule.setTickerDraft(normalized)
        root.post { openModule("analysis") }
    }'''
if oldm in ms:
    ms = ms.replace(oldm, newm, 1)
m.write_text(ms)

if 'setOnTouchListener' not in s:
    raise SystemExit('Watchlist touch patch did not apply')
if 'root.post { openModule("analysis") }' not in ms:
    raise SystemExit('MainActivity navigation patch did not apply')

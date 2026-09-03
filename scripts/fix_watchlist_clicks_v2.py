from pathlib import Path

p = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = p.read_text()
start = s.find('    private fun renderWatchlist(items: List<String>) {')
if start < 0: raise SystemExit('renderWatchlist start not found')
end = s.find('\n    private fun ', start + 10)
if end < 0: raise SystemExit('renderWatchlist end not found')
new_fn = '''    private fun renderWatchlist(items: List<String>) {
        host.content.removeAllViews()
        host.addSectionLabel("WATCHLIST • TICKERE SALVATE")
        if (items.isEmpty()) {
            host.addCard("WATCHLIST GOALĂ", "Adaugă un ticker din Analysis. Lista este separată de Portofoliu.")
            return
        }
        val store = OracleWatchlistStore(host.root.context)
        items.map { it.trim().uppercase(Locale.US) }.filter { it.isNotBlank() }.distinct().forEach { ticker ->
            fun open() { onWatchlistTickerClick(ticker) }
            val row = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(host.dp(15), host.dp(10), host.dp(8), host.dp(10))
                background = GradientDrawable().apply { setColor(Color.rgb(7,12,23)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(1), Color.rgb(45,70,105)) }
                isClickable = true
                isFocusable = true
                setOnClickListener { open() }
            }
            val tickerView = TextView(host.root.context).apply {
                text = ticker
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                isFocusable = true
                setOnClickListener { open() }
                contentDescription = "$ticker — deschide în Analysis"
            }
            row.addView(tickerView, LinearLayout.LayoutParams(0, -2, 1f))
            val arrowView = TextView(host.root.context).apply {
                text = "›"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(host.accent)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { open() }
                contentDescription = "Deschide $ticker în Analysis"
            }
            row.addView(arrowView, LinearLayout.LayoutParams(host.dp(38), host.dp(48)))
            val deleteView = TextView(host.root.context).apply {
                text = "ȘTERGE"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(255,105,105))
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val current = store.load().toMutableList()
                    current.removeAll { it.equals(ticker, true) }
                    store.save(current)
                    renderWatchlist(store.load())
                }
                contentDescription = "Șterge $ticker din Watchlist"
            }
            row.addView(deleteView, LinearLayout.LayoutParams(host.dp(105), host.dp(48)))
            host.content.addView(row, LinearLayout.LayoutParams(-1, host.dp(100)).apply { setMargins(0,0,0,host.dp(12)) })
        }
    }
'''
s = s[:start] + new_fn + s[end:]
p.write_text(s)

m = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
ms = m.read_text()
old = '''    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return
        OracleSimpleModule.setTickerDraft(normalized)
        openModule("analysis")
    }'''
new = '''    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return
        OracleSimpleModule.setTickerDraft(normalized)
        mainHandler.post { openModule("analysis") }
    }'''
if old not in ms: raise SystemExit('openWatchlistTicker block not found')
ms = ms.replace(old, new, 1)
m.write_text(ms)
print('Watchlist V3 click fix applied')

from pathlib import Path

# Growth UI history toggle and labels.
p = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s = p.read_text(encoding='utf-8')
start = s.index('    private fun addHistory(')
brace = s.index('{', start)
depth = 0
end = None
for i in range(brace, len(s)):
    if s[i] == '{': depth += 1
    elif s[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
if end is None: raise SystemExit('Could not locate addHistory end')
new = '''    private fun addHistory(entries: List<OracleGrowthRecommendation>) {
        val card = card(12)
        val header = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = text("ULTIMELE RECOMANDĂRI", 15f, Typeface.DEFAULT_BOLD, cyan, 0, 0)
        header.addView(title, LinearLayout.LayoutParams(-2, -2))
        val arrow = TextView(host.root.context).apply {
            text = "⌄"; textSize = 23f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(cyan)
            setPadding(host.dp(4), 0, host.dp(8), host.dp(2)); isClickable = true; isFocusable = true
            contentDescription = "Extinde sau restrânge ultimele recomandări"
        }
        header.addView(arrow, LinearLayout.LayoutParams(host.dp(38), host.dp(40)))
        header.addView(View(host.root.context), LinearLayout.LayoutParams(0, 1, 1f))
        val download = TextView(host.root.context).apply {
            text = "⇩  PDF"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = rounded(Color.rgb(8, 15, 28), cyan, 1, 10); setPadding(host.dp(13), host.dp(8), host.dp(13), host.dp(8))
            isClickable = true; isFocusable = true; contentDescription = "Descarcă jurnalul Growth în PDF"
            setOnClickListener {
                val path = journalStore.exportPdf()
                if (path != null) Toast.makeText(host.root.context, "Jurnalul Growth a fost salvat în Downloads.", Toast.LENGTH_LONG).show()
                else Toast.makeText(host.root.context, "Nu există recomandări pentru export.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(download, LinearLayout.LayoutParams(host.dp(94), host.dp(40)))
        card.addView(header)

        val all = entries.filter { it.referenceTimestamp > 0L && it.referenceTimestamp >= startHistoryTimestamp() }
            .sortedWith(compareByDescending<OracleGrowthRecommendation> { it.referenceTimestamp }.thenBy { horizonOrder(it.horizon) }.thenBy { it.ticker })
        val rows = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(rows)
        val summaryViews = mutableListOf<View>()
        fun addSummaryEntry(item: OracleGrowthRecommendation) {
            val row = historyRow(item); summaryViews += row
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }
        fun addPlaceholder() {
            val row = historyRow(null, "31.08.2026 16:00"); summaryViews += row
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }
        all.take(6).forEach(::addSummaryEntry)
        repeat(maxOf(0, 6 - minOf(6, all.size))) { addPlaceholder() }
        val olderViews = all.drop(6).map { item ->
            historyRow(item).also { row ->
                row.visibility = View.GONE
                rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
            }
        }
        var expanded = false
        fun applyHistoryVisibility() {
            // The latest 6 rows are always visible. The arrow only expands/collapses older rows.
            summaryViews.forEach { it.visibility = View.VISIBLE }
            olderViews.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
            arrow.text = if (expanded) "⌃" else "⌄"
            arrow.contentDescription = if (expanded) "Restrânge ultimele recomandări" else "Extinde ultimele recomandări"
        }
        arrow.setOnClickListener { expanded = !expanded; applyHistoryVisibility() }
        title.setOnClickListener { expanded = !expanded; applyHistoryVisibility() }
        applyHistoryVisibility()
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }'''
s = s[:start] + new + s[end:]
s = s.replace('private fun historyRow(item: OracleGrowthRecommendation?): LinearLayout {', 'private fun historyRow(item: OracleGrowthRecommendation?, placeholderDate: String? = null): LinearLayout {')
s = s.replace('addView(text("—", 10f, Typeface.DEFAULT, muted, 0, 2))', 'addView(text(placeholderDate ?: "—", 10f, Typeface.DEFAULT, muted, 0, 2))')
if '"RSG" -> "Republic Services, Inc."' not in s:
    s = s.replace('            "CRM" -> "Salesforce, Inc."\n', '            "CRM" -> "Salesforce, Inc."\n            "RSG" -> "Republic Services, Inc."\n')
if 'private fun displaySector(' not in s:
    marker = '    private fun horizonOrder(horizon: String)'
    helper = '''    private fun displaySector(ticker:String, stored:String):String {
        val value=stored.trim()
        if (value.isNotBlank() && value != "—") return value
        return when(ticker.uppercase(Locale.US)) {
            "RSG" -> "Industrials"
            "CF" -> "Materials"
            "LNG" -> "Energy"
            "CRM", "NOW", "ORCL" -> "Technology"
            else -> "—"
        }
    }

'''
    s = s.replace(marker, helper + marker)
s = s.replace('identity.addView(text(item.sector.ifBlank { "—" }, 9f, Typeface.DEFAULT_BOLD, Color.rgb(150, 170, 205), 0, 2))', 'identity.addView(text(displaySector(item.ticker, item.sector), 9f, Typeface.DEFAULT_BOLD, Color.rgb(150, 170, 205), 0, 2))')
s = s.replace('BUILD B536 • GROWTH', 'BUILD B539 • GROWTH')
p.write_text(s, encoding='utf-8')

# Archive the previous Growth snapshot at T0 rollover before it is replaced.
p = Path('app/src/main/java/ro/alintudor/oracle/OracleMysticActivity.kt')
s = p.read_text(encoding='utf-8')
if 'import ro.alintudor.oracle.core.OracleGrowthJournalStore' not in s:
    s = s.replace('import ro.alintudor.oracle.core.OracleBootstrap\n', 'import ro.alintudor.oracle.core.OracleBootstrap\nimport ro.alintudor.oracle.core.OracleGrowthJournalStore\n')
old = '''            Thread {
                runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
            }.start()'''
old_one_line = '        Thread { runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) } }.start()'
newwarm = '''            Thread {
                runCatching {
                    val growthJournal = OracleGrowthJournalStore(applicationContext)
                    val previous = repository.cachedGrowth()
                    val refreshed = OracleLocalProcessor.refreshGrowthOnly(repository)
                    if (previous.isNotEmpty() && refreshed.isNotEmpty() && previous.first().referenceTimestamp != refreshed.first().referenceTimestamp) {
                        growthJournal.record(previous)
                    }
                    growthJournal.record(refreshed)
                }
            }.start()'''
if old in s: s = s.replace(old, newwarm)
elif old_one_line in s:
    one_line_new = newwarm.strip().replace('            Thread {','        Thread {',1)
    s = s.replace(old_one_line, one_line_new)
else: raise SystemExit('Launcher warm-up block not found')
p.write_text(s, encoding='utf-8')

# PDF exports the Growth journal window starting 31.08.2026.
p = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthJournalStore.kt')
s = p.read_text(encoding='utf-8')
oldpdf = '''        val entries = load()
        if (entries.isEmpty()) return null'''
newpdf = '''        val cutoff = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ro", "RO")).apply { timeZone = zone }
            .parse("31.08.2026 00:00")?.time ?: 0L
        val entries = load().filter { it.referenceTimestamp >= cutoff }
        if (entries.isEmpty()) return null'''
if oldpdf in s: s = s.replace(oldpdf, newpdf)
s = s.replace('''    fun record(items: List<OracleGrowthRecommendation>) {
        if (items.isEmpty()) return''', '''    fun record(items: List<OracleGrowthRecommendation>) {
        val verified = items.filter { it.source.startsWith("ORACLE_ENGINE_V5.9.7") }
        if (verified.isEmpty()) return''')
s = s.replace('items.forEach { if (keys.add(key(it))) current.add(it) }', 'verified.forEach { if (keys.add(key(it))) current.add(it) }')
s = s.replace('put("weights", org.json.JSONArray().apply { item.weights.forEach { put(it) } })', 'put("weights", org.json.JSONArray().apply { item.weights.forEach { put(it) } }); put("factorValues", org.json.JSONArray().apply { item.factorValues.forEach { put(it) } }); put("generatedAt", item.generatedAt); put("source", item.source)')
oldctor = '''                o.optString("horizon"), o.optString("ticker"), o.optString("company"), o.optString("sector"),
                o.optInt("score"), o.optString("signal"), o.optString("risk"), o.optDouble("allocationMax"),
                o.optDouble("forecastPct"), o.optDouble("momentum5D"), o.optDouble("momentum20D"),
                List(w.length()) { n -> w.optInt(n) }, o.optString("newsTitle"), o.optString("newsSource"), o.optLong("referenceTimestamp")
            )'''
newctor = '''                horizon = o.optString("horizon"), ticker = o.optString("ticker"), company = o.optString("company"), sector = o.optString("sector"),
                score = o.optInt("score"), signal = o.optString("signal"), risk = o.optString("risk"), allocationMax = o.optDouble("allocationMax"),
                forecastPct = o.optDouble("forecastPct"), momentum5D = o.optDouble("momentum5D"), momentum20D = o.optDouble("momentum20D"),
                weights = List(w.length()) { n -> w.optInt(n) }, newsTitle = o.optString("newsTitle"), newsSource = o.optString("newsSource"),
                referenceTimestamp = o.optLong("referenceTimestamp"), factorValues = run { val fv=o.optJSONArray("factorValues") ?: org.json.JSONArray(); List(fv.length()){n->fv.optDouble(n)} },
                generatedAt = o.optLong("generatedAt", o.optLong("referenceTimestamp")), source = o.optString("source", "ORACLE_ENGINE")
            )'''
if oldctor in s: s = s.replace(oldctor, newctor)
p.write_text(s, encoding='utf-8')

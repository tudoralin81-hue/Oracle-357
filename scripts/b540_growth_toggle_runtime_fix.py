from pathlib import Path
import re

p = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s = p.read_text(encoding='utf-8')

new_history = r'''    private fun addHistory(entries: List<OracleGrowthRecommendation>) {
        val card = card(12)
        val header = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        header.addView(text("ULTIMELE RECOMANDĂRI", 15f, Typeface.DEFAULT_BOLD, cyan, 0, 0), LinearLayout.LayoutParams(0, -2, 1f))

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
            contentDescription = "Descarcă jurnalul Growth în PDF"
            setOnClickListener {
                val path = journalStore.exportPdf()
                if (path != null) Toast.makeText(host.root.context, "Jurnalul Growth a fost salvat în Downloads.", Toast.LENGTH_LONG).show()
                else Toast.makeText(host.root.context, "Nu există recomandări pentru export.", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(download, LinearLayout.LayoutParams(host.dp(94), host.dp(40)))

        val arrow = TextView(host.root.context).apply {
            text = "▼"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            isClickable = true
            isFocusable = true
            contentDescription = "Extinde sau restrânge ultimele recomandări"
        }
        header.addView(arrow, LinearLayout.LayoutParams(host.dp(48), host.dp(40)))
        card.addView(header)

        val all = entries
            .filter { it.referenceTimestamp > 0L && it.referenceTimestamp >= startHistoryTimestamp() }
            .sortedWith(compareByDescending<OracleGrowthRecommendation> { it.referenceTimestamp }
                .thenBy { horizonOrder(it.horizon) }.thenBy { it.ticker })
        val rows = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(rows)

        val visible = all.take(6)
        visible.forEach { item ->
            val row = historyRow(item)
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }
        repeat(maxOf(0, 6 - visible.size)) {
            val row = historyRow(null)
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }

        val olderViews = all.drop(6).map { item ->
            historyRow(item).apply { visibility = View.GONE }
        }
        olderViews.forEach { row ->
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(6), 0, 0) })
        }

        var expanded = false
        fun applyToggle() {
            expanded = !expanded
            arrow.text = if (expanded) "▲" else "▼"
            olderViews.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        }
        header.setOnClickListener { applyToggle() }
        arrow.setOnClickListener { applyToggle() }

        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

'''

pattern = r'    private fun addHistory\(entries: List<OracleGrowthRecommendation>\) \{.*?\n    private fun historyRow'
s, n = re.subn(pattern, new_history + '    private fun historyRow', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'addHistory replacement failed: {n}')

p.write_text(s, encoding='utf-8')
print('B540 toggle runtime fix applied: whole header clickable; PDF remains independent; six newest rows stay visible; older rows toggle with ▲/▼.')

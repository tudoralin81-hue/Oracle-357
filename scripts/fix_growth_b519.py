from pathlib import Path

GROWTH = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt")
g = GROWTH.read_text(encoding="utf-8")

old = '''    fun render(items: List<OracleGrowthRecommendation>, fallbackNews: List<OracleNews> = emptyList()) {
        host.content.removeAllViews()
        if (items.isEmpty()) {
            host.addCard("GROWTH", "Nu există încă un snapshot Growth local. Refresh va afișa ultimul rezultat Oracle disponibil.")
            return
        }
'''
new = '''    fun render(items: List<OracleGrowthRecommendation>, fallbackNews: List<OracleNews> = emptyList()) {
        host.content.removeAllViews()
        // Growth is a daily 16:00 snapshot. Never show an older trading-day snapshot.
        val validItems = items.filter { it.referenceTimestamp == currentGrowthAnchor() }
        if (validItems.isEmpty()) {
            host.addCard("GROWTH", "Se încarcă snapshot-ul Growth al sesiunii curente…")
            addBuildFooter()
            return
        }
'''
if old not in g:
    raise SystemExit("Growth render block not found")
g = g.replace(old, new, 1)

g = g.replace('''        addSummary(items)

        val ordered = listOf("SHORT", "MEDIUM", "LONG").mapNotNull { horizon ->
            items.firstOrNull { it.horizon.equals(horizon, true) }
        }''', '''        addSummary(validItems)

        val ordered = listOf("SHORT", "MEDIUM", "LONG").mapNotNull { horizon ->
            validItems.firstOrNull { it.horizon.equals(horizon, true) }
        }''', 1)

g = g.replace('''        addHistory(items)
    }

    private fun addSummary''', '''        addHistory(validItems)
        addBuildFooter()
    }

    private fun currentGrowthAnchor(): Long {
        val zone = java.time.ZoneId.of("Europe/Bucharest")
        val now = java.time.ZonedDateTime.now(zone)
        var date = if (now.toLocalTime().isBefore(java.time.LocalTime.of(16, 0))) now.toLocalDate().minusDays(1) else now.toLocalDate()
        while (!ro.alintudor.oracle.core.OracleMarketCalendar.isTradingDay(date)) date = date.minusDays(1)
        return java.time.ZonedDateTime.of(date, java.time.LocalTime.of(16, 0), zone).toInstant().toEpochMilli()
    }

    private fun addBuildFooter() {
        host.content.addView(text("BUILD B519 • V6g-FINAL", 9f, Typeface.DEFAULT_BOLD, Color.rgb(120, 135, 160), host.dp(4), 10), LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(18)) })
    }

    private fun addSummary''', 1)

GROWTH.write_text(g, encoding="utf-8")
print("B519 Growth stale-snapshot guard + build footer applied")

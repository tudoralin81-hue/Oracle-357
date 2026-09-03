from pathlib import Path
import re

SRC = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
GRADLE = Path('app/build.gradle')

s = SRC.read_text(encoding='utf-8')

# Full company name on its own line; sector stays on a separate line.
pattern = re.compile(
    r'        top\.addView\(TextView\(host\.root\.context\)\.apply \{\n'
    r'            text = "\$\{companyName\(r\.ticker\)\}.*?\n'
    r'        \}\)',
    re.S,
)
replacement = '''        top.addView(TextView(host.root.context).apply {
            text = companyName(r.ticker)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(205, 213, 228))
            setPadding(0, host.dp(4), 0, 0)
        })
        top.addView(TextView(host.root.context).apply {
            text = "Sector: ${r.sector ?: "Sector indisponibil"}"
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(145, 158, 180))
            setPadding(0, host.dp(2), 0, 0)
        })'''
s2 = pattern.sub(replacement, s, count=1)
if s2 != s:
    s = s2
elif 'text = companyName(r.ticker)' not in s:
    raise SystemExit('B513: company header could not be located')

# Keep the visual yellow deliberately softer.
s = s.replace('Color.rgb(228, 178, 28)', 'Color.rgb(205, 165, 38)')

# Ensure APLD displays its full company name.
if '"APLD" -> "Applied Digital Corporation"' not in s:
    anchor = '"AAOI" -> "Applied Optoelectronics, Inc."'
    if anchor in s:
        s = s.replace(anchor, anchor + '\n        "APLD" -> "Applied Digital Corporation"', 1)
    else:
        raise SystemExit('B513: company-name map anchor not found')

# One matrix only: Oracle technical factors + supplementary indicators + fundamentals.
# This replaces the three separate UI sections while preserving every underlying value.
start = s.find('        // ANALYSIS_PARAMETERS_V7')
end = s.find('        host.addSectionLabel("ANALIZĂ ORACLE")', start)
if start < 0 or end < 0:
    raise SystemExit('B513: Analysis parameter section anchors not found')

block = '''        // ANALYSIS_PARAMETERS_V8
        // All market-relevant values are presented in one two-column matrix:
        // Oracle factors + supplementary technical indicators + fundamentals.
        host.addSectionLabel("PARAMETRII BURSIERI RELEVANȚI")
        val relevantGrid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        val f = r.fundamentals
        val relevantParameters = mutableListOf<Pair<String, String>>()

        // Oracle factors: rawValues[0] is internal News; visible factors start at rawValues[1].
        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            relevantParameters.add(name to (r.rawValues.getOrNull(i + 1) ?: "Valoare indisponibilă"))
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

'''
s = s[:start] + block + s[end:]

# Bottom build label.
s = re.sub(r'ORACLE • V6g-FINAL-B\d+', 'ORACLE • V6g-FINAL-B513', s)
SRC.write_text(s, encoding='utf-8')

g = GRADLE.read_text(encoding='utf-8')
g = re.sub(r'versionCode\s+\d+', 'versionCode 28', g, count=1)
g = re.sub(r"versionName\s+'[^']+'", "versionName 'V6g-FINAL-B513'", g, count=1)
GRADLE.write_text(g, encoding='utf-8')

from pathlib import Path
import re

# ANALYSIS_STATUS_COLORS_V1
# Display-only patch: preserves the Oracle scoring engine and raw data.
# Three value colors: green = normal/favorable, yellow = attention/neutral,
# red = unfavorable/outside the preferred range. Missing data is yellow.

app = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = app.read_text(encoding='utf-8')

start = s.find('    private fun addMetricGrid(container: LinearLayout, items: List<Pair<String, String>>) {')
end = s.find('    private fun addTechnicalChart(ticker: String) {', start)
if start < 0 or end < 0:
    raise SystemExit('Analysis metric-grid anchors missing')

new_helpers = r'''    private fun addMetricGrid(container: LinearLayout, items: List<Pair<String, String>>) {
        var row: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % 2 == 0) {
                row = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(6)) })
            }
            val card = LinearLayout(host.root.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(host.dp(11), host.dp(9), host.dp(11), host.dp(9))
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
            })
            card.addView(TextView(host.root.context).apply {
                text = item.second
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(metricValueColor(item.first, item.second))
                setPadding(0, host.dp(4), 0, 0)
                maxLines = 4
            })
            row?.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index % 2 == 1) setMargins(host.dp(4), 0, 0, 0)
                else setMargins(0, 0, host.dp(4), 0)
            })
        }
    }

    private fun metricValueColor(label: String, value: String): Int {
        val l = label.uppercase(Locale.US)
        val v = value.uppercase(Locale.US)
        if (value == "—" || value.contains("INDISPONIBILĂ") || value.contains("INDISPONIBILĂ")) return Color.rgb(255, 193, 7)

        fun numberAfter(token: String): Double? {
            val m = Regex(Regex.escape(token) + "\\s*(-?\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE).find(value) ?: return null
            return m.groupValues[1].replace(',', '.').toDoubleOrNull()
        }
        fun firstNumber(): Double? = Regex("-?\\d+(?:[.,]\\d+)?").find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
        fun pctNumber(): Double? = firstNumber()

        return when {
            l == "SECTOR" || l == "INDUSTRY" -> Color.rgb(50, 220, 135)
            l == "BREAKOUT" -> if (v.contains("BREAKOUT: DA")) Color.rgb(50, 220, 135) else Color.rgb(255, 193, 7)
            l == "TREND" -> {
                val p = numberAfter("Preț"); val s50 = numberAfter("SMA50"); val s200 = numberAfter("SMA200")
                when {
                    p != null && s50 != null && s200 != null && p >= s50 && p >= s200 -> Color.rgb(50, 220, 135)
                    p != null && s50 != null && s200 != null && p < s50 && p < s200 -> Color.rgb(244, 67, 54)
                    else -> Color.rgb(255, 193, 7)
                }
            }
            l == "MOMENTUM" -> {
                val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(value).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
                when {
                    nums.size >= 2 && nums[0] > 0 && nums[1] > 0 -> Color.rgb(50, 220, 135)
                    nums.size >= 2 && nums[0] < 0 && nums[1] < 0 -> Color.rgb(244, 67, 54)
                    else -> Color.rgb(255, 193, 7)
                }
            }
            l == "VOLUME" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 0.8..1.8 -> Color.rgb(50, 220, 135); n < 0.8 -> Color.rgb(255, 193, 7); else -> Color.rgb(255, 193, 7) }
            }
            l == "SUPPORT / RESISTANCE" -> Color.rgb(255, 193, 7)
            l == "BOLLINGER" -> {
                val pos = numberAfter("Poziție")
                when { pos == null -> Color.rgb(255, 193, 7); pos in -20.0..20.0 -> Color.rgb(50, 220, 135); pos < -20.0 -> Color.rgb(244, 67, 54); else -> Color.rgb(255, 193, 7) }
            }
            l == "ICHIMOKU" -> if (v.contains("BULLISH")) Color.rgb(50, 220, 135) else Color.rgb(244, 67, 54)
            l == "MARKET / SECTOR" -> Color.rgb(50, 220, 135)
            l == "RISK / REWARD" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n <= 5.0 -> Color.rgb(50, 220, 135); n <= 8.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "ADX" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n >= 20.0 -> Color.rgb(50, 220, 135); else -> Color.rgb(255, 193, 54) }
            }
            l == "RSI (14)" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 30.0..70.0 -> Color.rgb(50, 220, 135); n < 30.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "MACD (12/26)" -> {
                val nums = Regex("-?\\d+(?:[.,]\\d+)?").findAll(value).mapNotNull { it.value.replace(',', '.').toDoubleOrNull() }.toList()
                when { nums.size >= 2 && nums[0] > nums[1] -> Color.rgb(50, 220, 135); nums.size >= 2 && nums[0] < nums[1] -> Color.rgb(244, 67, 54); else -> Color.rgb(255, 193, 7) }
            }
            l == "ATR" -> {
                val n = Regex("(-?\\d+(?:[.,]\\d+)?)%", RegexOption.IGNORE_CASE).find(value)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                when { n == null -> Color.rgb(255, 193, 7); n in 2.0..6.0 -> Color.rgb(50, 220, 135); n > 6.0 -> Color.rgb(244, 67, 54); else -> Color.rgb(255, 193, 7) }
            }
            l == "52W HIGH / LOW" -> Color.rgb(255, 193, 7)
            l == "P/E" || l == "FWD P/E" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 10.0..30.0 -> Color.rgb(50, 220, 135); n < 10.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "P/B" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 1.0..5.0 -> Color.rgb(50, 220, 135); n < 1.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "REVENUE GROWTH" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(255, 193, 7); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "EARNINGS GROWTH" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(255, 193, 7); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "NET MARGIN" || l == "OPERATING MARGIN" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(255, 193, 7); n >= 10.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "ROE" -> {
                val n = pctNumber()
                when { n == null -> Color.rgb(255, 193, 7); n >= 15.0 -> Color.rgb(50, 220, 135); n >= 0.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "D/E" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n <= 1.0 -> Color.rgb(50, 220, 135); n <= 2.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "CURRENT RATIO" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 1.5..3.0 -> Color.rgb(50, 220, 135); n >= 1.0 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "QUICK RATIO" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 1.0..2.0 -> Color.rgb(50, 220, 135); n >= 0.7 -> Color.rgb(255, 193, 7); else -> Color.rgb(244, 67, 54) }
            }
            l == "BETA" -> {
                val n = firstNumber()
                when { n == null -> Color.rgb(255, 193, 7); n in 0.8..1.5 -> Color.rgb(50, 220, 135); n > 1.5 -> Color.rgb(244, 67, 54); else -> Color.rgb(255, 193, 54) }
            }
            l == "MARKET CAP" -> Color.rgb(50, 220, 135)
            else -> Color.rgb(255, 193, 7)
        }
    }
    private fun metricPair(value: Double?, signal: Double?): String = "${num2(value)}  •  SIG ${num2(signal)}"
    private fun num2(value: Double?): String = value?.let { "%.2f".format(Locale.US, it) } ?: "—"
    private fun pctFund(value: Double?): String = value?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "—"
    private fun capText(value: Double?): String = when { value == null -> "—"; value >= 1e12 -> "%.2fT".format(Locale.US, value / 1e12); value >= 1e9 -> "%.2fB".format(Locale.US, value / 1e9); value >= 1e6 -> "%.2fM".format(Locale.US, value / 1e6); else -> "%.0f".format(Locale.US, value) }

'''
s = s[:start] + new_helpers + s[end:]
app.write_text(s, encoding='utf-8')

print('Analysis three-color value status patch applied')

from pathlib import Path
import re

src = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = src.read_text(encoding='utf-8')

# Remove the aggregate Fundamentals Oracle card. Fundamentals are already
# represented as individual cards in the same two-column matrix.
old_factors = '''        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            relevantParameters.add(name to (r.rawValues.getOrNull(i + 1) ?: "Valoare indisponibilă"))
        }'''
new_factors = '''        OracleAnalysisEngine.factorNames.forEachIndexed { i, name ->
            if (!name.equals("Fundamentals", ignoreCase = true)) {
                relevantParameters.add(name to (r.rawValues.getOrNull(i + 1) ?: "Valoare indisponibilă"))
            }
        }'''
if old_factors in s:
    s = s.replace(old_factors, new_factors, 1)

start = s.find('    private fun addMetricGrid(')
end = s.find('    private fun metricValueColor', start)
if start < 0 or end < 0:
    raise SystemExit('B514: addMetricGrid anchors not found')

grid = '''    private fun addMetricGrid(container: LinearLayout, items: List<Pair<String, String>>) {
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

'''
s = s[:start] + grid + s[end:]

# Preserve the company-name header already present in the source.
if 'text = companyName(r.ticker)' not in s:
    raise SystemExit('B514: company name header missing')

# Keep the APLD mapping if it is not already present.
if '"APLD" -> "Applied Digital Corporation"' not in s:
    anchor = '"AAOI" -> "Applied Optoelectronics, Inc."'
    if anchor not in s:
        raise SystemExit('B514: APLD map anchor not found')
    s = s.replace(anchor, anchor + '\n        "APLD" -> "Applied Digital Corporation"', 1)

# Slightly softer Oracle yellow used by the Analysis values.
s = s.replace('Color.rgb(228, 178, 28)', 'Color.rgb(205, 165, 38)')
s = re.sub(r'V6g-FINAL-B\d+', 'V6g-FINAL-B514', s)
src.write_text(s, encoding='utf-8')

gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
g = re.sub(r'versionCode\s+\d+', 'versionCode 29', g, count=1)
g = re.sub(r"versionName\s+'[^']+'", "versionName 'V6g-FINAL-B514'", g, count=1)
gradle.write_text(g, encoding='utf-8')

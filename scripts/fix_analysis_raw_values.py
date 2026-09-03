from pathlib import Path

# This patch is intentionally idempotent: CI runs it before every APK build.
# Analysis keeps News internal to Growth, exposes only raw values, and adds
# display-only technical/fundamental metrics without changing Oracle weights.

app = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = app.read_text(encoding='utf-8')

# Preserve the resolved sector display.
old_sector = 'text = "${companyName(r.ticker)}   •   Sector: ${sector(r.ticker)}"'
new_sector = 'text = "${companyName(r.ticker)}   •   Sector: ${r.sector ?: "Sector indisponibil"}"'
if old_sector in s:
    s = s.replace(old_sector, new_sector, 1)
elif new_sector not in s:
    raise SystemExit('Analysis sector display anchor not found')

# Replace the complete Analysis parameter area with the new visual grid.
if '// ANALYSIS_PARAMETERS_V6' not in s:
    start_marker = '        host.addSectionLabel("PARAMETRII ORACLE • VALORI")'
    end_marker = '        host.addSectionLabel("ANALIZĂ ORACLE")'
    start = s.find(start_marker)
    end = s.find(end_marker, start)
    if start < 0 or end < 0:
        raise SystemExit('Analysis parameter area anchors not found')

    new_block = '''        // ANALYSIS_PARAMETERS_V6
        // NEWS is an internal Growth factor and is intentionally absent from Analysis.
        host.addSectionLabel("PARAMETRII ORACLE • VALORI")
        val oracleGrid = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val visibleFactors = OracleAnalysisEngine.factorNames.mapIndexedNotNull { i, name ->
            if (i == 0) null else name to (r.rawValues.getOrNull(i) ?: "Valoare indisponibilă")
        }
        addMetricGrid(oracleGrid, visibleFactors)
        host.content.addView(oracleGrid, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(8))
        })

        host.addSectionLabel("INDICATORI SUPLIMENTARI")
        val extraGrid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        addMetricGrid(extraGrid, listOf(
            "RSI (14)" to fmt(r.rsi),
            "MACD (12/26)" to metricPair(r.macd, r.macdSignal),
            "52W HIGH / LOW" to "${moneyOrDash(r.week52High)} / ${moneyOrDash(r.week52Low)}",
            "ATR" to "${money(r.atrValue)}  •  ${fmt(r.atrPct)}%"
        ))
        host.content.addView(extraGrid, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(8))
        })

        host.addSectionLabel("FUNDAMENTALE")
        val f = r.fundamentals
        val fundamentalsGrid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL }
        addMetricGrid(fundamentalsGrid, listOf(
            "Sector" to (f?.sector ?: r.sector ?: "—"),
            "Industry" to (f?.industry ?: "—"),
            "P/E" to num2(f?.trailingPe),
            "Fwd P/E" to num2(f?.forwardPe),
            "P/B" to num2(f?.priceToBook),
            "Revenue growth" to pctFund(f?.revenueGrowth),
            "Earnings growth" to pctFund(f?.earningsGrowth),
            "Net margin" to pctFund(f?.profitMargin),
            "Operating margin" to pctFund(f?.operatingMargin),
            "ROE" to pctFund(f?.returnOnEquity),
            "D/E" to num2(f?.debtToEquity),
            "Current ratio" to num2(f?.currentRatio),
            "Quick ratio" to num2(f?.quickRatio),
            "Beta" to num2(f?.beta),
            "Market cap" to capText(f?.marketCap)
        ))
        host.content.addView(fundamentalsGrid, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(10))
        })

'''
    s = s[:start] + new_block + s[end:]

# Add the helper views used by the redesigned parameter area.
if 'private fun addMetricGrid(' not in s:
    marker = '    private fun addTechnicalChart(ticker: String) {'
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit('Analysis chart helper anchor not found')
    helpers = '''    private fun addMetricGrid(container: LinearLayout, items: List<Pair<String, String>>) {
        var row: LinearLayout? = null
        items.forEachIndexed { index, item ->
            if (index % 2 == 0) {
                row = LinearLayout(host.root.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                container.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    setMargins(0, 0, 0, host.dp(6))
                })
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
                setTextColor(Color.WHITE)
                setPadding(0, host.dp(4), 0, 0)
                maxLines = 4
            })
            row?.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index % 2 == 1) setMargins(host.dp(4), 0, 0, 0) else setMargins(0, 0, host.dp(4), 0)
            })
        }
    }

    private fun metricPair(value: Double?, signal: Double?): String =
        "${num2(value)}  •  SIG ${num2(signal)}"

    private fun num2(value: Double?): String = value?.let { "%.2f".format(Locale.US, it) } ?: "—"

    private fun pctFund(value: Double?): String = value?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "—"

    private fun capText(value: Double?): String = when {
        value == null -> "—"
        value >= 1e12 -> "%.2fT".format(Locale.US, value / 1e12)
        value >= 1e9 -> "%.2fB".format(Locale.US, value / 1e9)
        value >= 1e6 -> "%.2fM".format(Locale.US, value / 1e6)
        else -> "%.0f".format(Locale.US, value)
    }

'''
    s = s[:idx] + helpers + s[idx:]

app.write_text(s, encoding='utf-8')

# Oracle engine: add four display-only technical metrics and expose the real
# fundamentals object to the UI. These do NOT enter the 12-factor Oracle score.
engine = Path('app/src/main/java/ro/alintudor/oracle/core/OracleAnalysisEngine.kt')
e = engine.read_text(encoding='utf-8')
if '// ANALYSIS_TECH_EXTRAS_V1' not in e:
    e = e.replace('object OracleAnalysisEngine {', 'object OracleAnalysisEngine {\n    // ANALYSIS_TECH_EXTRAS_V1', 1)
    old_result = 'val momentum20D:Double,val volumeRatio:Double,val sma50:Double?,val sma200:Double?,val adx:Double?,val atrPct:Double,val factors:List<Double>,val rawValues:List<String>'
    new_result = 'val momentum20D:Double,val volumeRatio:Double,val sma50:Double?,val sma200:Double?,val adx:Double?,val atrPct:Double,val atrValue:Double,val macd:Double?,val macdSignal:Double?,val week52High:Double?,val week52Low:Double?,val fundamentals:OracleFundamentals?,val factors:List<Double>,val rawValues:List<String>'
    if old_result not in e:
        raise SystemExit('OracleAnalysisEngine Result anchor not found')
    e = e.replace(old_result, new_result, 1)

    old_calc = 'val atr=atr(high,low,close,14)?:p*.01;val atrPct=100*atr/p;val adx=adx(high,low,close,14)'
    new_calc = 'val atr=atr(high,low,close,14)?:p*.01;val atrPct=100*atr/p;val adx=adx(high,low,close,14);val macdPair=macd(close);val week52=close.take(252);val ema20=ema(close,20)'
    if old_calc not in e:
        raise SystemExit('OracleAnalysisEngine technical calculation anchor not found')
    e = e.replace(old_calc, new_calc, 1)

    old_return = 'return Result(ticker,p,ss,ms,ls,signal,risk,allocation,resolvedSector,null,rsi,m5,m20,vr,s50,s200,adx,atrPct,factors,rawValues)'
    new_return = 'return Result(ticker,p,ss,ms,ls,signal,risk,allocation,resolvedSector,null,rsi,m5,m20,vr,s50,s200,adx,atrPct,atr,macdPair.first,macdPair.second,week52.maxOrNull(),week52.minOrNull(),fundamentals,factors,rawValues)'
    if old_return not in e:
        raise SystemExit('OracleAnalysisEngine Result return anchor not found')
    e = e.replace(old_return, new_return, 1)

    marker = '    private fun money(v:Double?):String='
    idx = e.find(marker)
    if idx < 0:
        raise SystemExit('OracleAnalysisEngine helper anchor not found')
    helpers = '''    private fun ema(valuesDesc:List<Double>,n:Int):Double? {
        if (valuesDesc.size < n) return null
        val values=valuesDesc.asReversed()
        var e=values.take(n).average()
        val alpha=2.0/(n+1.0)
        for(i in n until values.size) e=values[i]*alpha+e*(1.0-alpha)
        return e
    }
    private fun macd(valuesDesc:List<Double>):Pair<Double?,Double?> {
        if(valuesDesc.size<35) return null to null
        val values=valuesDesc.asReversed()
        val e12=mutableListOf<Double>(); val e26=mutableListOf<Double>()
        var a12=values.take(12).average(); var a26=values.take(26).average()
        e12 += a12
        for(i in 12 until values.size){ a12=values[i]*(2.0/13.0)+a12*(11.0/13.0); e12+=a12 }
        e26 += a26
        for(i in 26 until values.size){ a26=values[i]*(2.0/27.0)+a26*(25.0/27.0); e26+=a26 }
        val series=mutableListOf<Double>()
        for(i in 25 until values.size) series += e12[i-11]-e26[i-25]
        if(series.isEmpty()) return null to null
        val signal=emaChronological(series,9)
        return series.lastOrNull() to signal
    }
    private fun emaChronological(values:List<Double>,n:Int):Double? {
        if(values.size<n) return null
        var e=values.take(n).average(); val alpha=2.0/(n+1.0)
        for(i in n until values.size) e=values[i]*alpha+e*(1.0-alpha)
        return e
    }
'''
    e = e[:idx] + helpers + e[idx:]

engine.write_text(e, encoding='utf-8')

# Real fundamentals: add four high-value raw fields. They are display-only and
# deliberately excluded from fundamentalScore so the canonical Oracle model is unchanged.
real_data = Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
r = real_data.read_text(encoding='utf-8')
if '// FUNDAMENTALS_V2' not in r:
    r = r.replace('object OracleRealData {', 'object OracleRealData {\n    // FUNDAMENTALS_V2', 1)
    old_data = 'val marketCap: Double?, val rawText: String'
    new_data = 'val marketCap: Double?, val priceToBook: Double?, val currentRatio: Double?, val quickRatio: Double?, val beta: Double?, val rawText: String'
    if old_data not in r:
        raise SystemExit('OracleFundamentals data class anchor not found')
    r = r.replace(old_data, new_data, 1)

    old_vars = 'val cap=summary?.marketCap ?: quote?.marketCap ?: ts?.marketCap\n        if (listOf(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap).all { it == null }) return null\n        return OracleFundamentals(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,\n            buildFundamentalText(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap))'
    new_vars = 'val cap=summary?.marketCap ?: quote?.marketCap ?: ts?.marketCap\n        val pb=summary?.priceToBook ?: quote?.priceToBook\n        val cr=summary?.currentRatio ?: quote?.currentRatio\n        val qr=summary?.quickRatio ?: quote?.quickRatio\n        val beta=summary?.beta ?: quote?.beta\n        if (listOf(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta).all { it == null }) return null\n        return OracleFundamentals(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta,\n            buildFundamentalText(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta))'
    if old_vars not in r:
        raise SystemExit('OracleRealData fundamentals aggregation anchor not found')
    r = r.replace(old_vars, new_vars, 1)

    old_summary_return = 'marketCap, ""\n        )'
    new_summary_return = 'marketCap,\n            num(sd,"priceToBook")?:num(ks,"priceToBook"),\n            num(fd,"currentRatio"),num(fd,"quickRatio"),num(sd,"beta"), ""\n        )'
    if old_summary_return not in r:
        raise SystemExit('OracleRealData summary return anchor not found')
    r = r.replace(old_summary_return, new_summary_return, 1)

    old_quote_return = 'num(q,"trailingPE"),num(q,"forwardPE"),null,null,null,null,null,null,null,marketCap,""'
    new_quote_return = 'num(q,"trailingPE"),num(q,"forwardPE"),null,null,null,null,null,null,null,marketCap,\n            num(q,"priceToBook"),num(q,"currentRatio"),num(q,"quickRatio"),num(q,"beta"),""'
    if old_quote_return not in r:
        raise SystemExit('OracleRealData quote return anchor not found')
    r = r.replace(old_quote_return, new_quote_return, 1)

    # Timeseries fallback has no reliable direct values for the four extra fields.
    old_ts = 'return OracleFundamentals(sector,industry,null,null,rg,eg,pm,om,roe,de,marketCap,"")'
    new_ts = 'return OracleFundamentals(sector,industry,null,null,rg,eg,pm,om,roe,de,marketCap,null,null,null,null,"")'
    if old_ts not in r:
        raise SystemExit('OracleRealData timeseries return anchor not found')
    r = r.replace(old_ts, new_ts, 1)

    old_text_sig = 'private fun buildFundamentalText(sector:String?,industry:String?,pe:Double?,fpe:Double?,rg:Double?,eg:Double?,pm:Double?,om:Double?,roe:Double?,de:Double?,cap:Double?):String'
    new_text_sig = 'private fun buildFundamentalText(sector:String?,industry:String?,pe:Double?,fpe:Double?,rg:Double?,eg:Double?,pm:Double?,om:Double?,roe:Double?,de:Double?,cap:Double?,pb:Double?,cr:Double?,qr:Double?,beta:Double?):String'
    if old_text_sig not in r:
        raise SystemExit('OracleRealData fundamental text signature anchor not found')
    r = r.replace(old_text_sig, new_text_sig, 1)
    old_text_end = 'append("D/E=${de?.let{"%.2f".format(Locale.US,it)}?:"—"}; Market cap=${moneyCap(cap)}")'
    new_text_end = 'append("D/E=${de?.let{"%.2f".format(Locale.US,it)}?:"—"}; P/B=${pb?.let{"%.2f".format(Locale.US,it)}?:"—"}; ")\n        append("Current ratio=${cr?.let{"%.2f".format(Locale.US,it)}?:"—"}; Quick ratio=${qr?.let{"%.2f".format(Locale.US,it)}?:"—"}; Beta=${beta?.let{"%.2f".format(Locale.US,it)}?:"—"}; Market cap=${moneyCap(cap)}")'
    if old_text_end not in r:
        raise SystemExit('OracleRealData fundamental text body anchor not found')
    r = r.replace(old_text_end, new_text_end, 1)

real_data.write_text(r, encoding='utf-8')

print('Analysis parameters V6 + technical extras + expanded raw fundamentals patch applied')

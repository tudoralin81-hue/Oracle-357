from pathlib import Path

# REPAIR_ANALYSIS_474_V3 — keep Analysis/RealData source stable across builds.
ui = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = ui.read_text(encoding='utf-8')
s = s.replace(
    'if (i == 0) null else name to (r.rawValues.getOrNull(i) ?: "Valoare indisponibilă")',
    'name to (r.rawValues.getOrNull(i) ?: "Valoare indisponibilă")',
    1,
)
ui.write_text(s, encoding='utf-8')

p = Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
s = p.read_text(encoding='utf-8')

# Remove any previously generated malformed helper block and rebuild it safely.
start = s.find('    private fun knownIndustry(ticker:String)')
end = s.find('    private fun knownSector(ticker:String)', start if start >= 0 else 0)
if start >= 0 and end >= 0:
    s = s[:start] + s[end:]

# Helpers required by OracleAnalysisEngine and Analysis UI.
helper = '''    private fun knownIndustry(ticker:String):String?=when(ticker){"APLD"->"Information Technology Services";"AAOI"->"Semiconductors";else->null}

    fun newsContext(ticker:String):OracleNewsContext=try{
        val q=URLEncoder.encode(ticker.uppercase(Locale.US)+" stock when:7d","UTF-8")
        val body=getText("https://news.google.com/rss/search?q=$q&hl=en-US&gl=US&ceid=US:en")
        val titles=Regex("<title>(.*?)</title>",RegexOption.IGNORE_CASE).findAll(body).map{it.groupValues[1].replace("&amp;","&").replace("&quot;","'")}.filter{!it.equals("Google News",true)}.take(8).toList()
        val positive=listOf("beat","upgrade","buy","bullish","record","strong","surge","contract","partnership","deal","approval","launch","growth","profit")
        val negative=listOf("miss","downgrade","sell","bearish","lawsuit","investigation","warning","cut guidance","recall","layoff","fraud","delay","loss","decline","plunge","offering","dilution","bankruptcy")
        val pos=titles.sumOf{t->positive.count{t.contains(it,true)}}
        val neg=titles.sumOf{t->negative.count{t.contains(it,true)}}
        OracleNewsContext((50+pos*5-neg*7).coerceIn(0,100),titles.size,pos,neg,titles.firstOrNull())
    }catch(_:Exception){OracleNewsContext(50,0,0,0,null)}

    fun marketContext(sector:String?):OracleMarketContext{
        val etf=sectorEtf(sector); val spy=returns("SPY"); val sec=etf?.let{returns(it)}
        return OracleMarketContext(spy?.first,spy?.second,sec?.first,sec?.second,etf,"SPY 5D=${pct(spy?.first)}; SPY 20D=${pct(spy?.second)}; ${etf?:"Sector ETF"} 5D=${pct(sec?.first)}; ${etf?:"Sector ETF"} 20D=${pct(sec?.second)}")
    }
    fun sectorScore(ctx:OracleMarketContext):Double?{val v=listOfNotNull(ctx.market5D,ctx.market20D,ctx.sector5D,ctx.sector20D);if(v.isEmpty())return null;return(50.0+v.map{it*100.0*2.2}.average()).coerceIn(0.0,100.0)}
    fun fundamentalScore(f:OracleFundamentals?):Double?{if(f==null)return null;val p=mutableListOf<Double>();f.revenueGrowth?.let{p+=(50+it*180).coerceIn(0.0,100.0)};f.earningsGrowth?.let{p+=(50+it*150).coerceIn(0.0,100.0)};f.profitMargin?.let{p+=(50+it*220).coerceIn(0.0,100.0)};f.operatingMargin?.let{p+=(50+it*180).coerceIn(0.0,100.0)};f.returnOnEquity?.let{p+=(50+it*100).coerceIn(0.0,100.0)};f.debtToEquity?.let{p+=(75-it*0.12).coerceIn(0.0,100.0)};f.forwardPe?.let{p+=when{it<=0->35.0;it<=15->85.0;it<=25->70.0;it<=40->55.0;else->35.0}};return p.takeIf{it.isNotEmpty()}?.average()?.coerceIn(0.0,100.0)}
    private fun returns(ticker:String):Pair<Double,Double>?{val d=OracleMarketData.fetchDaily(ticker,"3mo").sortedByDescending{it.timestamp};if(d.size<=20)return null;val p=d[0].close;val p5=d.getOrNull(5)?.close?:return null;val p20=d.getOrNull(20)?.close?:return null;return Pair(p/p5-1,p/p20-1)}
    private fun sectorEtf(sector:String?):String?{val s=sector?.lowercase(Locale.US)?:return null;return when{"semiconductor" in s||"technology" in s||"software" in s->"XLK";"communication" in s||"telecom" in s->"XLC";"health" in s||"biotech" in s->"XLV";"financial" in s||"bank" in s->"XLF";"industrial" in s->"XLI";"energy" in s->"XLE";"consumer" in s&&"cyclical" in s->"XLY";"consumer" in s&&"discretionary" in s->"XLY";"consumer" in s||"staples" in s->"XLP";"utility" in s->"XLU";"real estate" in s->"XLRE";"material" in s->"XLB";else->null}}
    private fun getText(url:String):String{val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=TIMEOUT;readTimeout=TIMEOUT;requestMethod="GET";setRequestProperty("User-Agent","Oracle-Stock-Intelligence/1.0")};return try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
    private fun getJson(url:String):JSONObject{val c=(URL(url).openConnection() as HttpURLConnection).apply{connectTimeout=TIMEOUT;readTimeout=TIMEOUT;requestMethod="GET";setRequestProperty("User-Agent","Oracle-Stock-Intelligence/1.0");setRequestProperty("Accept","application/json")};return try{JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}}
    private fun pct(v:Double?):String=v?.let{"%.2f%%".format(Locale.US,it*100)}?:"—"; private fun moneyCap(v:Double?):String=when{v==null->"—";v>=1e12->"%.2fT".format(Locale.US,v/1e12);v>=1e9->"%.2fB".format(Locale.US,v/1e9);v>=1e6->"%.2fM".format(Locale.US,v/1e6);else->"%.0f".format(Locale.US,v)}

'''
marker = '    private fun knownSector(ticker:String):String?=when(ticker){'
if marker not in s:
    raise SystemExit('knownSector anchor missing')
s = s.replace(marker, helper + marker, 1)

# Keep only one occurrence of each quarterly timeseries field.
s = s.replace(
    '"quarterlyCurrentAssets","quarterlyCurrentLiabilities","quarterlyInventory","quarterlyStockholdersEquity",\n            "quarterlyCurrentAssets","quarterlyCurrentLiabilities","quarterlyInventory",',
    '"quarterlyCurrentAssets","quarterlyCurrentLiabilities","quarterlyInventory","quarterlyStockholdersEquity",',
    1,
)
p.write_text(s, encoding='utf-8')
print('Analysis 474 V3 repair applied')
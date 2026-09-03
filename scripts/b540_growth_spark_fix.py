from pathlib import Path

M = Path('app/src/main/java/ro/alintudor/oracle/core/OracleMarketData.kt')
s = M.read_text(encoding='utf-8')
start = s.find('    fun fetchDailyBatch(')
if start < 0:
    raise SystemExit('fetchDailyBatch not found')
next_doc = s.find('\n\n    /**', start)
if next_doc < 0:
    raise SystemExit('next function boundary not found')
new_fun = '''    fun fetchDailyBatch(tickers:List<String>,range:String="1y"):Map<String,List<OracleOhlcvPoint>> {
        val syms=tickers.map{it.trim().uppercase()}.filter{it.isNotBlank()}.distinct()
        if(syms.isEmpty()) return emptyMap()
        val u=URL("https://query1.finance.yahoo.com/v7/finance/spark?symbols=${java.net.URLEncoder.encode(syms.joinToString(","),"UTF-8")}&range=$range&interval=1d&indicators=open,high,low,close,volume")
        val c=(u.openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=2500;readTimeout=6500
            setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept","application/json")
        }
        return try{
            if(c.responseCode !in 200..299) return emptyMap()
            val root=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val result=root.optJSONObject("spark")?.optJSONArray("result") ?: return emptyMap()
            buildMap{
                for(x in 0 until result.length()){
                    val item=result.optJSONObject(x) ?: continue
                    val ticker=item.optString("symbol").uppercase()
                    if(ticker.isBlank()) continue
                    val z=item.optJSONArray("response")?.optJSONObject(0) ?: continue
                    val ts=z.optJSONArray("timestamp") ?: continue
                    val q=z.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: continue
                    val o=q.optJSONArray("open") ?: continue
                    val h=q.optJSONArray("high") ?: continue
                    val l=q.optJSONArray("low") ?: continue
                    val cl=q.optJSONArray("close") ?: continue
                    val v=q.optJSONArray("volume")
                    val rows=ArrayList<OracleOhlcvPoint>(ts.length())
                    for(j in 0 until ts.length()){
                        val oo=o.optDouble(j,Double.NaN);val hh=h.optDouble(j,Double.NaN);val ll=l.optDouble(j,Double.NaN);val cc=cl.optDouble(j,Double.NaN)
                        if(oo.isFinite()&&hh.isFinite()&&ll.isFinite()&&cc.isFinite()&&hh>0&&ll>0&&cc>0) rows+=OracleOhlcvPoint(ts.optLong(j)*1000L,oo,hh,ll,cc,v?.optDouble(j,0.0)?:0.0)
                    }
                    if(rows.isNotEmpty()) put(ticker,rows.sortedBy{it.timestamp})
                }
            }
        }catch(_:Exception){ emptyMap() }finally{ c.disconnect() }
    }'''
s = s[:start] + new_fun + s[next_doc:]
M.write_text(s, encoding='utf-8')

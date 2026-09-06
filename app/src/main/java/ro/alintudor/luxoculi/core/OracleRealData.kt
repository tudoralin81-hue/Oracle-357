package ro.alintudor.luxoculi.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Real non-OHLC data used by Analysis/Growth. */
data class OracleFundamentals(
    val sector: String?, val industry: String?, val trailingPe: Double?, val forwardPe: Double?,
    val revenueGrowth: Double?, val earningsGrowth: Double?, val profitMargin: Double?,
    val operatingMargin: Double?, val returnOnEquity: Double?, val debtToEquity: Double?,
    val marketCap: Double?, val priceToBook: Double?, val currentRatio: Double?, val quickRatio: Double?, val beta: Double?, val rawText: String,
    // Extended-hours (display only — never feeds the score). marketState comes
    // straight from Yahoo: "PRE"/"PREPRE" before the open, "POST"/"POSTPOST"
    // after the close, "REGULAR" or "CLOSED" otherwise — the pre/post price
    // fields are only ever populated by Yahoo during the matching window, so
    // no separate time-of-day check is needed on our side to know when to hide them.
    val marketState: String? = null, val preMarketPrice: Double? = null, val preMarketChangePercent: Double? = null,
    val postMarketPrice: Double? = null, val postMarketChangePercent: Double? = null
)
data class OracleNewsContext(val score:Int,val headlineCount:Int,val positiveHits:Int,val negativeHits:Int,val topHeadline:String?)
data class OracleMarketContext(val market5D:Double?,val market20D:Double?,val sector5D:Double?,val sector20D:Double?,val sectorEtf:String?,val rawText:String)

// --- Company Data popup (Profile / Financials / Earnings / Dividends) ---
data class OracleQuarterEarning(val label:String, val actual:Double?, val estimate:Double?)
data class OracleQuarterFinancial(val label:String, val revenue:Double?, val netIncome:Double?)
data class OracleCompanyProfile(
    val description:String?, val sector:String?, val industry:String?, val employees:Int?,
    val address:String?, val website:String?, val officers:List<Pair<String,String>>,
    val dividendYieldPct:Double?, val payoutRatioPct:Double?, val dividendRate:Double?, val exDividendDate:Long?,
    val quarterlyEarnings:List<OracleQuarterEarning>, val quarterlyFinancials:List<OracleQuarterFinancial>
)

object OracleRealData {
    // ANALYSIS_REALDATA_AUTH_V1
    // FUNDAMENTALS_V3 — consistent-period ratios + real-data fallbacks
    private const val TIMEOUT=7000

    fun resolvedSector(ticker:String, remoteSector:String?=null):String? {
        remoteSector?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return knownSector(ticker.trim().uppercase(Locale.US))
    }

    /**
     * Fundamentals use Yahoo quoteSummary when available, then the no-crumb
     * fundamentals-timeseries endpoint, then the quote endpoint. Missing fields
     * remain genuinely missing; no Oracle score is substituted into the data.
     */
    /** Next earnings date (epoch millis) from Yahoo's calendarEvents module, or null. */
    fun nextEarningsDate(ticker:String):Long? = runCatching {
        val symbol=ticker.uppercase(Locale.US)
        val root=yahooQuoteSummary(symbol,"calendarEvents")
        val earnings=root.optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0)?.optJSONObject("calendarEvents")?.optJSONObject("earnings")
        val dates=earnings?.optJSONArray("earningsDate") ?: return@runCatching null
        var best:Long?=null
        for(i in 0 until dates.length()){
            val raw=dates.opt(i)
            val sec=when(raw){ is Number->raw.toLong(); is org.json.JSONObject->raw.optLong("raw",0L); else->0L }
            if(sec>0L){ val ms=sec*1000L; if(best==null||ms<best!!) best=ms }
        }
        best
    }.getOrNull()

    /**
     * One combined fetch (assetProfile + summaryDetail + earnings) backing the
     * Company Data popup available next to tickers throughout the app:
     * business description, sector/industry/employees/address/website/top
     * officers, dividend summary, and quarterly EPS (actual vs. estimate) and
     * revenue/net income — the same shape TradingView-style apps show.
     * Returns null (never throws) if nothing usable comes back; the popup
     * shows "data unavailable" rather than a blank/broken screen.
     */
    fun companyProfile(ticker:String):OracleCompanyProfile? = try {
        val symbol = ticker.trim().uppercase(Locale.US)
        val root = yahooQuoteSummary(symbol, "assetProfile,summaryDetail,earnings")
        val r = root.optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0)
        if (r == null) null else {
            val profile = r.optJSONObject("assetProfile")
            val summary = r.optJSONObject("summaryDetail")
            val earnings = r.optJSONObject("earnings")

            fun num(o: JSONObject?, key: String): Double? = o?.optDouble(key, Double.NaN)?.takeIf { it.isFinite() }

            val officers = ArrayList<Pair<String, String>>()
            profile?.optJSONArray("companyOfficers")?.let { arr ->
                for (i in 0 until minOf(4, arr.length())) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
                    officers += name to o.optString("title", "")
                }
            }
            val quarterlyEarnings = ArrayList<OracleQuarterEarning>()
            earnings?.optJSONObject("earningsChart")?.optJSONArray("quarterly")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val q = arr.optJSONObject(i) ?: continue
                    quarterlyEarnings += OracleQuarterEarning(q.optString("date", "?"), num(q, "actual"), num(q, "estimate"))
                }
            }
            val quarterlyFinancials = ArrayList<OracleQuarterFinancial>()
            earnings?.optJSONObject("financialsChart")?.optJSONArray("quarterly")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val q = arr.optJSONObject(i) ?: continue
                    quarterlyFinancials += OracleQuarterFinancial(q.optString("date", "?"), num(q, "revenue"), num(q, "earnings"))
                }
            }
            val address = listOfNotNull(
                profile?.optString("address1")?.takeIf { it.isNotBlank() },
                profile?.optString("city")?.takeIf { it.isNotBlank() },
                profile?.optString("country")?.takeIf { it.isNotBlank() },
            ).joinToString(", ").takeIf { it.isNotBlank() }

            val result = OracleCompanyProfile(
                description = profile?.optString("longBusinessSummary")?.takeIf { it.isNotBlank() },
                sector = profile?.optString("sector")?.takeIf { it.isNotBlank() },
                industry = profile?.optString("industry")?.takeIf { it.isNotBlank() },
                employees = profile?.optInt("fullTimeEmployees", 0)?.takeIf { it > 0 },
                address = address,
                website = profile?.optString("website")?.takeIf { it.isNotBlank() },
                officers = officers,
                dividendYieldPct = num(summary, "dividendYield")?.let { it * 100.0 },
                payoutRatioPct = num(summary, "payoutRatio")?.let { it * 100.0 },
                dividendRate = num(summary, "dividendRate"),
                exDividendDate = summary?.optLong("exDividendDate", 0L)?.takeIf { it > 0L }?.times(1000L),
                quarterlyEarnings = quarterlyEarnings,
                quarterlyFinancials = quarterlyFinancials,
            )
            if (result.description == null && result.officers.isEmpty() && quarterlyEarnings.isEmpty() && quarterlyFinancials.isEmpty() && result.dividendYieldPct == null) null else result
        }
    } catch (_: Exception) { null }

    fun fundamentals(ticker:String):OracleFundamentals? {
        val symbol=ticker.uppercase(Locale.US)
        val modules="price,summaryDetail,defaultKeyStatistics,financialData,assetProfile"
        val summary=runCatching { yahooQuoteSummary(symbol,modules) }
            .map { parseQuoteSummary(it,symbol) }.getOrNull()

        val quote=runCatching { yahooQuote(symbol) }
            .map { parseQuoteFallback(it,symbol) }.getOrNull()

        val ts=runCatching { parseTimeseriesFundamentals(fetchTimeseries(symbol),symbol) }.getOrNull()
        val sector=resolvedSector(symbol,summary?.sector ?: ts?.sector ?: quote?.sector)
        val industry=summary?.industry ?: ts?.industry ?: quote?.industry ?: knownIndustry(symbol)
        val pe=(summary?.trailingPe ?: quote?.trailingPe ?: ts?.trailingPe)?.takeIf { it.isFinite() && it > 0.0 }
        val fpe=listOf(summary?.forwardPe, quote?.forwardPe, ts?.forwardPe).firstOrNull { it != null && it.isFinite() && it > 0.0 }
        val rg=ts?.revenueGrowth ?: summary?.revenueGrowth
        val eg=summary?.earningsGrowth ?: ts?.earningsGrowth
        val pm=summary?.profitMargin ?: ts?.profitMargin
        val om=summary?.operatingMargin ?: ts?.operatingMargin
        val roe=summary?.returnOnEquity ?: ts?.returnOnEquity
        val de=summary?.debtToEquity ?: ts?.debtToEquity
        val cap=summary?.marketCap ?: quote?.marketCap ?: ts?.marketCap
        val pb=summary?.priceToBook ?: quote?.priceToBook ?: ts?.priceToBook
        val cr=summary?.currentRatio ?: quote?.currentRatio ?: ts?.currentRatio
        val qr=summary?.quickRatio ?: quote?.quickRatio ?: ts?.quickRatio
        val beta=summary?.beta ?: quote?.beta ?: ts?.beta
        if (listOf(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta).all { it == null }) return null
        return OracleFundamentals(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta,
            buildFundamentalText(sector,industry,pe,fpe,rg,eg,pm,om,roe,de,cap,pb,cr,qr,beta),
            summary?.marketState, summary?.preMarketPrice, summary?.preMarketChangePercent,
            summary?.postMarketPrice, summary?.postMarketChangePercent)
    }

    private fun parseQuoteSummary(root:JSONObject,ticker:String):OracleFundamentals? = try {
        val r=root.optJSONObject("quoteSummary")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val profile=r.optJSONObject("assetProfile")
        val sd=r.optJSONObject("summaryDetail")
        val ks=r.optJSONObject("defaultKeyStatistics")
        val fd=r.optJSONObject("financialData")
        val price=r.optJSONObject("price")
        val sector=resolvedSector(ticker,profile?.optString("sector")?.takeIf{it.isNotBlank()})
        val industry=profile?.optString("industry")?.takeIf{it.isNotBlank()}
        val marketCap = num(sd,"marketCap") ?: num(price,"marketCap") ?: run {
            val shares = num(ks,"sharesOutstanding") ?: num(ks,"impliedSharesOutstanding")
            val px = num(price,"regularMarketPrice") ?: num(price,"regularMarketPreviousClose")
            if (shares != null && px != null) shares * px else null
        }
        OracleFundamentals(
            sector,industry,
            num(sd,"trailingPE")?:num(ks,"trailingPE"),
            num(sd,"forwardPE")?:num(ks,"forwardPE"),
            num(fd,"revenueGrowth"),num(fd,"earningsGrowth"),num(fd,"profitMargins"),
            num(fd,"operatingMargins"),num(fd,"returnOnEquity"),num(fd,"debtToEquity")?.let { it / 100.0 },
            marketCap,
            num(sd,"priceToBook")?:num(ks,"priceToBook"),
            num(fd,"currentRatio"),num(fd,"quickRatio"),num(sd,"beta"), "",
            price?.optString("marketState")?.takeIf { it.isNotBlank() },
            num(price,"preMarketPrice"), num(price,"preMarketChangePercent"),
            num(price,"postMarketPrice"), num(price,"postMarketChangePercent")
        )
    } catch(_:Exception) { null }

    private fun parseQuoteFallback(root:JSONObject,ticker:String):OracleFundamentals? = try {
        val q=root.optJSONObject("quoteResponse")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val marketCap = num(q,"marketCap") ?: run {
            val shares = num(q,"sharesOutstanding") ?: num(q,"impliedSharesOutstanding")
            val px = num(q,"regularMarketPrice") ?: num(q,"regularMarketPreviousClose")
            if (shares != null && px != null) shares * px else null
        }
        OracleFundamentals(
            resolvedSector(ticker),q.optString("industry").takeIf{it.isNotBlank()},
            num(q,"trailingPE"),num(q,"forwardPE"),null,null,null,null,null,
            num(q,"debtToEquity")?.let { if (it > 20.0) it / 100.0 else it },marketCap,
            num(q,"priceToBook"),num(q,"currentRatio"),num(q,"quickRatio"),num(q,"beta"),""
        )
    } catch(_:Exception) { null }

    private data class YahooSession(val crumb:String,val cookie:String)

    private fun yahooSession():YahooSession {
        val ua="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
        var cookie=""
        val seed=(URL("https://fc.yahoo.com").openConnection() as HttpURLConnection).apply {
            connectTimeout=TIMEOUT; readTimeout=TIMEOUT; requestMethod="GET"; setRequestProperty("User-Agent",ua)
        }
        try {
            cookie=seed.headerFields.entries.firstOrNull { it.key?.equals("Set-Cookie",true)==true }
                ?.value?.firstOrNull()?.substringBefore(';') ?: ""
        } finally { runCatching { seed.inputStream.close() }; seed.disconnect() }
        if(cookie.isBlank()) throw IllegalStateException("Yahoo session cookie unavailable")

        val crumbConn=(URL("https://query1.finance.yahoo.com/v1/test/getcrumb").openConnection() as HttpURLConnection).apply {
            connectTimeout=TIMEOUT; readTimeout=TIMEOUT; requestMethod="GET"
            setRequestProperty("User-Agent",ua); setRequestProperty("Cookie",cookie); setRequestProperty("Accept","text/plain")
        }
        val crumb=try { crumbConn.inputStream.bufferedReader().use{it.readText()}.trim() } finally { crumbConn.disconnect() }
        if(crumb.isBlank() || crumb.contains("Too Many Requests",true) || crumb.startsWith("<")) throw IllegalStateException("Yahoo crumb unavailable")
        return YahooSession(crumb,cookie)
    }

    private fun yahooGetJson(url:String):JSONObject {
        val session=yahooSession()
        val sep=if(url.contains('?')) '&' else '?'
        val target=url+sep+"crumb="+URLEncoder.encode(session.crumb,"UTF-8")
        val ua="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
        val c=(URL(target).openConnection() as HttpURLConnection).apply {
            connectTimeout=TIMEOUT; readTimeout=TIMEOUT; requestMethod="GET"
            setRequestProperty("User-Agent",ua); setRequestProperty("Cookie",session.cookie); setRequestProperty("Accept","application/json"); setRequestProperty("Referer","https://finance.yahoo.com/")
        }
        return try { JSONObject(c.inputStream.bufferedReader().use{it.readText()}) } finally { c.disconnect() }
    }

    private fun yahooQuoteSummary(symbol:String,modules:String):JSONObject = yahooGetJson(
        "https://query2.finance.yahoo.com/v10/finance/quoteSummary/$symbol?modules=$modules&formatted=false&lang=en-US&region=US"
    )

    private fun yahooQuote(symbol:String):JSONObject = yahooGetJson(
        "https://query1.finance.yahoo.com/v7/finance/quote?symbols=$symbol&formatted=false&lang=en-US&region=US"
    )

    /** Company name + current price for the Portfolio "Add Position" autofill.
     *  Reuses the same authenticated Yahoo session as fundamentals()/quote()
     *  above — the plain, unauthenticated quote endpoint no longer answers
     *  reliably on its own. Returns null (never throws) on any failure; the
     *  caller leaves the field blank for the person to fill in by hand. */
    fun lookupQuote(ticker: String): OracleQuoteLookup? = try {
        val symbol = ticker.trim().uppercase(Locale.US)
        if (symbol.isBlank()) null
        else {
            val q = yahooQuote(symbol).optJSONObject("quoteResponse")?.optJSONArray("result")?.optJSONObject(0)
            if (q == null) null else {
                val name = q.optString("longName").takeIf { it.isNotBlank() } ?: q.optString("shortName").takeIf { it.isNotBlank() }
                val price = if (q.has("regularMarketPrice")) q.optDouble("regularMarketPrice", Double.NaN) else Double.NaN
                if (name == null && price.isNaN()) null else OracleQuoteLookup(name, price.takeIf { it.isFinite() && it > 0.0 })
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchTimeseries(symbol:String):JSONObject {
        val now=System.currentTimeMillis()/1000L
        val period1=now-6L*365L*24L*60L*60L
        val types=listOf(
            "annualTotalRevenue","annualOperatingIncome","annualNetIncome","annualDilutedEPS",
            "annualTotalDebt","annualStockholdersEquity",
            "quarterlyOrdinarySharesNumber","annualOrdinarySharesNumber",
            "quarterlyCurrentAssets","quarterlyCurrentLiabilities","quarterlyInventory","quarterlyStockholdersEquity",
            "quarterlyTotalDebt","quarterlyStockholdersEquity",
            "trailingTotalRevenue","trailingOperatingIncome","trailingNetIncome","trailingDilutedEPS",
            "trailingTotalDebt","trailingStockholdersEquity"
        ).joinToString(",")
        val url="https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/$symbol?symbol=$symbol&type=$types&period1=$period1&period2=$now&padTimeSeries=true&lang=en-US&region=US"
        return getJson(url)
    }

    private fun parseTimeseriesFundamentals(root:JSONObject,ticker:String):OracleFundamentals? {
        val revenue=latestTwo(root,"annualTotalRevenue")
        val income=latestTwo(root,"annualNetIncome")
        val operating=latestTwo(root,"annualOperatingIncome")
        val debt=latest(root,"quarterlyTotalDebt") ?: latest(root,"annualTotalDebt")
        val equityPair=latestTwo(root,"quarterlyStockholdersEquity")
        val equity=equityPair.first ?: latest(root,"annualStockholdersEquity")
        val currentAssets=latest(root,"quarterlyCurrentAssets")
        val currentLiabilities=latest(root,"quarterlyCurrentLiabilities")
        val inventory=latest(root,"quarterlyInventory") ?: 0.0
        val ttmRevenue=latest(root,"trailingTotalRevenue") ?: revenue.first
        val ttmIncome=latest(root,"trailingNetIncome") ?: income.first
        val ttmOperating=latest(root,"trailingOperatingIncome") ?: operating.first
        val ttmEps=latest(root,"trailingDilutedEPS")
        val rg=if(revenue.first!=null && revenue.second!=null && revenue.second!=0.0) revenue.first!!/revenue.second!!-1.0 else null
        val eg=if(income.first!=null && income.second!=null && income.second!! > 0.0 && income.first!! > 0.0) income.first!!/income.second!!-1.0 else null
        val pm=if(ttmRevenue!=null && ttmRevenue!=0.0 && ttmIncome!=null) ttmIncome/ttmRevenue else null
        val om=if(ttmRevenue!=null && ttmRevenue!=0.0 && ttmOperating!=null) ttmOperating/ttmRevenue else null
        val avgEquity=if(equityPair.first!=null && equityPair.second!=null && equityPair.first!! > 0.0 && equityPair.second!! > 0.0) (equityPair.first!!+equityPair.second!!)/2.0 else equity
        val roe=if(avgEquity!=null && avgEquity!=0.0 && ttmIncome!=null) ttmIncome/avgEquity else null
        val de=if(equity!=null && equity!=0.0 && debt!=null) debt/equity else null
        val shares=latest(root,"quarterlyOrdinarySharesNumber") ?: latest(root,"annualOrdinarySharesNumber")
        val price=runCatching { OracleMarketData.fetchDaily(ticker,"5d").maxByOrNull{it.timestamp}?.close }.getOrNull()
        val marketCap=if(shares!=null && price!=null && shares>0.0 && price>0.0) shares*price else null
        val pe=if(price!=null && ttmEps!=null && ttmEps>0.0) price/ttmEps else null
        val pb=if(marketCap!=null && equity!=null && equity>0.0) marketCap/equity else null
        val cr=if(currentAssets!=null && currentLiabilities!=null && currentLiabilities>0.0) currentAssets/currentLiabilities else null
        val qr=if(currentAssets!=null && currentLiabilities!=null && currentLiabilities>0.0) (currentAssets-inventory)/currentLiabilities else null
        val beta=computedBeta(ticker)
        val sector=resolvedSector(ticker)
        val industry=knownIndustry(ticker)
        return OracleFundamentals(sector,industry,pe,null,rg,eg,pm,om,roe,de,marketCap,pb,cr,qr,beta,"")
    }

    private fun computedBeta(ticker:String):Double? {
        return runCatching {
            val a=OracleMarketData.fetchDaily(ticker,"1y").sortedByDescending{it.timestamp}.map{it.close}
            val b=OracleMarketData.fetchDaily("SPY","1y").sortedByDescending{it.timestamp}.map{it.close}
            val n=minOf(a.size,b.size)-1
            if(n<30) return@runCatching null
            val ar=(0 until n).map{i->a[i]/a[i+1]-1.0}
            val br=(0 until n).map{i->b[i]/b[i+1]-1.0}
            val am=ar.average(); val bm=br.average()
            val cov=ar.indices.sumOf{i->(ar[i]-am)*(br[i]-bm)}/n
            val vari=br.sumOf{(it-bm)*(it-bm)}/n
            if(vari>0.0) cov/vari else null
        }.getOrNull()
    }

    private fun latestTwo(root:JSONObject,key:String):Pair<Double?,Double?> {
        val values=timeseriesValues(root,key).sortedByDescending{it.first}
        return Pair(values.getOrNull(0)?.second,values.getOrNull(1)?.second)
    }
    private fun latest(root:JSONObject,key:String):Double?=timeseriesValues(root,key).maxByOrNull{it.first}?.second
    private fun timeseriesValues(root:JSONObject,key:String):List<Pair<Long,Double>> {
        val result=root.optJSONObject("timeseries")?.optJSONArray("result") ?: return emptyList()
        val out=mutableListOf<Pair<Long,Double>>()
        for(i in 0 until result.length()) {
            val obj=result.optJSONObject(i) ?: continue
            val arr=obj.optJSONArray(key) ?: continue
            for(j in 0 until arr.length()) {
                val item=arr.optJSONObject(j) ?: continue
                val raw=item.optJSONObject("reportedValue")?.opt("raw") ?: item.opt("raw")
                val value=(raw as? Number)?.toDouble() ?: continue
                val date=item.optString("asOfDate",item.optString("date",""))
                val stamp=runCatching { java.time.Instant.parse(if(date.endsWith("Z")) date else "${date}T00:00:00Z").toEpochMilli() }.getOrDefault(j.toLong())
                out += stamp to value
            }
        }
        return out
    }

    private fun num(o:JSONObject?,key:String):Double? {
        val value=o?.opt(key) ?: return null
        val x=when(value){is Number->value.toDouble();is JSONObject->value.optDouble("raw",Double.NaN);else->Double.NaN}
        return x.takeIf { it.isFinite() }
    }

    private fun buildFundamentalText(sector:String?,industry:String?,pe:Double?,fpe:Double?,rg:Double?,eg:Double?,pm:Double?,om:Double?,roe:Double?,de:Double?,cap:Double?,pb:Double?,cr:Double?,qr:Double?,beta:Double?):String = buildString {
        append("Sector=${sector?:"—"}; Industry=${industry?:"—"}; ")
        append("P/E=${pe?.let{"%.2f".format(Locale.US,it)}?:"—"}; Fwd P/E=${fpe?.let{"%.2f".format(Locale.US,it)}?:"—"}; ")
        append("Revenue growth=${pct(rg)}; Earnings growth=${pct(eg)}; Net margin=${pct(pm)}; Op margin=${pct(om)}; ROE=${pct(roe)}; ")
        append("D/E=${de?.let{"%.2f".format(Locale.US,it)}?:"—"}; P/B=${pb?.let{"%.2f".format(Locale.US,it)}?:"—"}; ")
        append("Current ratio=${cr?.let{"%.2f".format(Locale.US,it)}?:"—"}; Quick ratio=${qr?.let{"%.2f".format(Locale.US,it)}?:"—"}; Beta=${beta?.let{"%.2f".format(Locale.US,it)}?:"—"}; Market cap=${moneyCap(cap)}")
    }

    private fun knownIndustry(ticker:String):String?=when(ticker){"APLD"->"Information Technology Services";"AAOI"->"Semiconductors";else->null}

    fun newsContext(ticker:String):OracleNewsContext=try{
        val q=URLEncoder.encode(ticker.uppercase(Locale.US)+" stock when:7d","UTF-8")
        val body=getText("https://news.google.com/rss/search?q=$q&hl=en-US&gl=US&ceid=US:en")
        val titles=Regex("<title>(.*?)</title>",RegexOption.IGNORE_CASE).findAll(body).map{it.groupValues[1].replace("&amp;","&").replace("&quot;","'")}.drop(1).filter{!it.contains("Google News",true) && !it.contains(" when:",true)}.take(8).toList()
        val per=titles.map{OracleSentiment.scoreOne(it)}
        val pos=per.count{it>0.15}; val neg=per.count{it<-0.15}
        OracleNewsContext(OracleSentiment.score(titles),titles.size,pos,neg,titles.firstOrNull())
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

    private fun knownSector(ticker:String):String?=when(ticker){
        "NVDA","AMD","AVGO","QCOM","MU","MRVL","ARM","INTC","TSM","ASML","LRCX","AMAT","KLAC","ON","MPWR","ADI","TXN","NXPI","MCHP","SWKS","STM","WDC","STX","SMCI","CRDO","AAOI","AAPL","MSFT","ORCL","CRM","NOW","ADBE","INTU","SNOW","PLTR","PANW","CRWD","NET","DDOG","MDB","SHOP","TEAM","VEEV","SNPS","CDNS","FTNT","ZS","WDAY","ROP","ACN","IBM","SAP","CSCO","ANET","DELL","HPE","APLD"->"Technology"
        "GOOGL","GOOG","META","NFLX","DIS","CMCSA","TMUS","VZ","T","CHTR","WBD","SPOT"->"Communication Services"
        "AMZN","TSLA","HD","LOW","MCD","NKE","SBUX","BKNG","ABNB","TJX","TGT","GM","F","LULU"->"Consumer Discretionary"
        "WMT","COST","PG","KO","PEP","PM","MO","CL","KMB"->"Consumer Staples"
        "LLY","JNJ","UNH","MRK","PFE","ABBV","TMO","DHR","ABT","ISRG","VRTX","REGN","GILD","AMGN","MRNA","CRSP"->"Health Care"
        "JPM","BAC","WFC","C","GS","MS","BLK","SCHW","COF","AXP","V","MA","PYPL","HOOD","COIN"->"Financials"
        "GE","CAT","DE","HON","RTX","BA","LMT","NOC","GD","ETN","EMR","UNP","UPS","FDX","RHM"->"Industrials"
        "XOM","CVX","COP","SLB","EOG","OXY","MPC","VLO","HAL","FANG"->"Energy"
        "LIN","APD","SHW","FCX","NEM","NUE","DOW","DD","ALB"->"Materials"
        "NEE","DUK","SO","AEP","EXC","SRE","D"->"Utilities"
        "PLD","AMT","EQIX","CCI","O","SPG","WELL","DLR"->"Real Estate"
        else->null
    }
}

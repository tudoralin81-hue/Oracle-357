package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** GROWTH progress phase, polled by the loader UI (Requirement #6 — B540). */
enum class OracleGrowthPhase { RUNNING, DONE, NO_DATA }

/** GROWTH progress snapshot, polled by the loader UI (Requirement #6 — B540). */
data class OracleGrowthProgress(
    val loaded: Int,
    val total: Int,
    val startedAtNanos: Long,
    val phase: OracleGrowthPhase
)

/** Canonical Android port of the PHP Growth V5.9.7 technical/ranking engine. */
object OracleGrowthEngine {
    // ---- B540: single global deadline for the whole calculation (Requirement #5). ----
    // Both budgets are measured from the same start instant, so a slow technical
    // scan can never push the enrichment phase past the overall target — there is
    // no cumulative/additive stacking of timeouts.
    private const val TOTAL_BUDGET_NANOS = 19_000_000_000L // 1s buffer under the 20s target
    private const val SCAN_BUDGET_NANOS = 13_000_000_000L
    private const val BATCH_SIZE = 40
    private const val SCAN_THREADS = 10
    private const val ENRICH_THREADS = 10
    private const val SECTOR_THREADS = 6
    private const val SECTOR_TASK_CAP_NANOS = 2_000_000_000L

    @Volatile private var progressState = OracleGrowthProgress(0, OracleSP500Universe.TARGET_SIZE, 0L, OracleGrowthPhase.RUNNING)

    /** Read-only progress used by the Growth loader UI. Never blocks. */
    fun growthProgress(): OracleGrowthProgress = progressState

    private data class C(val ticker:String,val price:Double,val score:Int,val rsi:Double?,val mom5:Double,val mom20:Double,val vr:Double,val macdHist:Double?,val ichi:Boolean,val sma200:Double?,val sma50:Double?,val adx:Double?,val atrPct:Double,val components:Map<String,Double>,val forecast:Map<String,Double>,val risk:String,val allocation:Double,val news:Int)

    // V6.0 raw profiles, normalized at score time.
    // Order: News, Breakout, Trend, Momentum, Volume, S/R, Fundamentals,
    // Bollinger, Ichimoku, Market/Sector, Risk/Reward, ADX,
    // then the V6.0 additions: Relative Strength, Volatility Regime,
    // 52-week Range Position, Volume Trend (OBV), Community Sentiment.
    //
    // The five new factors were chosen to add information the first twelve
    // do NOT already carry: relative strength measures the stock against the
    // index (market_sector only describes its sector); volatility regime
    // detects compression before expansion (ATR only ever fed risk sizing);
    // range position places price in its 52-week context (S/R is 20-day only);
    // volume trend is 20-day accumulation via OBV (volume is a single-day
    // ratio); community sentiment is retail chatter (news is press only).
    // Long horizons lean on relative strength and range; short horizons lean
    // on volume trend and compression.
    private val weights=mapOf(
        "SHORT" to intArrayOf(21,18,18,12,16,12,3,4,4,2,2,1, 8,6,5,7,6),
        "MEDIUM" to intArrayOf(12,12,12,16,12,9,9,5,5,6,5,4, 10,5,6,6,4),
        "LONG" to intArrayOf(6,6,6,19,7,9,18,4,4,9,7,2, 12,3,8,5,2))
    private val keys=listOf("news","breakout","trend","momentum","volume","support_resistance","fundamentals","bollinger","ichimoku","market_sector","risk_reward","adx",
        "relative_strength","volatility_regime","range_position","volume_trend","community")

    // ---------------------------------------------------------------------
    // FULL-UNIVERSE BACKGROUND SCAN
    //
    // The 20-second budget in run() exists because someone is watching the
    // screen. A scan running in the background has no such constraint, so the
    // whole universe (~1,400 names) can be covered properly there and cached
    // to disk. run() then only has to rank what is already computed, which is
    // both faster on screen and no longer dependent on connection speed at the
    // moment the user happens to open Growth.
    // ---------------------------------------------------------------------
    private const val SCAN_CACHE_FILE = "oracle_universe_scan.json"
    private const val FULL_SCAN_BUDGET_NANOS = 8L * 60L * 1_000_000_000L // 8 minutes, hard ceiling
    private const val FULL_SCAN_BATCH = 40
    private const val FULL_SCAN_THREADS = 8

    /** Progress of the background scan, for the UI to show honestly. */
    data class FullScanState(val scanned:Int, val total:Int, val anchor:Long, val running:Boolean, val finishedAt:Long)
    @Volatile var fullScanState = FullScanState(0,0,0L,false,0L); private set

    private fun scanCacheFile(context: Context) = java.io.File(context.applicationContext.filesDir, SCAN_CACHE_FILE)

    private fun cToJson(c:C):JSONObject = JSONObject().apply{
        put("t",c.ticker); put("p",c.price); put("s",c.score); c.rsi?.let{put("rsi",it)}
        put("m5",c.mom5); put("m20",c.mom20); put("vr",c.vr); c.macdHist?.let{put("mh",it)}
        put("ich",c.ichi); c.sma200?.let{put("s200",it)}; c.sma50?.let{put("s50",it)}; c.adx?.let{put("adx",it)}
        put("atr",c.atrPct); put("risk",c.risk); put("alloc",c.allocation)
        put("comp",JSONObject().apply{ c.components.forEach{(k,v)->put(k,v)} })
        put("fc",JSONObject().apply{ c.forecast.forEach{(k,v)->put(k,v)} })
    }

    private fun jsonToC(o:JSONObject):C?{
        val t=o.optString("t").takeIf{it.isNotBlank()} ?: return null
        fun map(name:String):Map<String,Double>{
            val j=o.optJSONObject(name) ?: return emptyMap()
            val m=LinkedHashMap<String,Double>(); for(k in j.keys()) m[k]=j.optDouble(k,50.0); return m
        }
        val comp=map("comp"); if(comp.size!=keys.size) return null   // produced by an older engine
        return C(t,o.optDouble("p",0.0),o.optInt("s",0),
            if(o.has("rsi")) o.optDouble("rsi") else null,o.optDouble("m5",0.0),o.optDouble("m20",0.0),o.optDouble("vr",1.0),
            if(o.has("mh")) o.optDouble("mh") else null,o.optBoolean("ich",false),
            if(o.has("s200")) o.optDouble("s200") else null,if(o.has("s50")) o.optDouble("s50") else null,
            if(o.has("adx")) o.optDouble("adx") else null,o.optDouble("atr",1.0),comp,map("fc"),
            o.optString("risk","MEDIUM"),o.optDouble("alloc",1.0),0)
    }

    private fun readScanCache(context: Context, anchor: Long):List<C>{
        return runCatching{
            val f=scanCacheFile(context); if(!f.exists()) return emptyList()
            val root=JSONObject(f.readText())
            if(root.optLong("anchor",0L)!=anchor) return emptyList()
            val arr=root.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull{ i-> arr.optJSONObject(i)?.let{ jsonToC(it) } }
        }.getOrDefault(emptyList())
    }

    private fun writeScanCache(context: Context, anchor: Long, items:List<C>){
        runCatching{
            val root=JSONObject().apply{
                put("anchor",anchor); put("savedAt",System.currentTimeMillis()); put("factors",keys.size)
                put("items",JSONArray().apply{ items.forEach{ put(cToJson(it)) } })
            }
            scanCacheFile(context).writeText(root.toString())
        }
    }

    /** True when a usable full-universe scan already exists for this trading day. */
    fun hasFreshFullScan(context: Context):Boolean =
        readScanCache(context, OracleMarketCalendar.growthAnchor(System.currentTimeMillis())).size >= 200

    /**
     * Scans the ENTIRE universe and caches every candidate. Background only —
     * takes minutes, must never be called on the UI thread. Results are written
     * incrementally so a scan cut short by the OS still leaves usable progress.
     */
    fun scanFullUniverse(context: Context):Int{
        val anchor=OracleMarketCalendar.growthAnchor(System.currentTimeMillis())
        val universe=runCatching{ OracleMarketUniverse.companies(context).map{it.ticker}.distinct() }
            .getOrDefault(emptyList())
            .ifEmpty{ runCatching{ OracleSP500Universe.tickers(context) }.getOrDefault(emptyList()) }
        if(universe.isEmpty()){ OracleGrowthLog.log(context,"SCAN","Background scan aborted: universe empty"); return 0 }
        val existing=readScanCache(context,anchor).associateBy{it.ticker}.toMutableMap()
        OracleGrowthLog.log(context,"SCAN","Background full-universe scan started: ${universe.size} tickers, ${existing.size} already cached for this trading day, 8-minute budget")
        val deadline=System.nanoTime()+FULL_SCAN_BUDGET_NANOS
        fullScanState=FullScanState(existing.size,universe.size,anchor,true,0L)
        val pool=Executors.newFixedThreadPool(FULL_SCAN_THREADS)
        try{
            // Skip what this anchor already has: a resumed scan continues
            // instead of redoing work, so repeated short wake-ups still converge.
            val todo=universe.filter{ it !in existing }
            for(chunk in todo.chunked(FULL_SCAN_BATCH*FULL_SCAN_THREADS)){
                if(System.nanoTime()>deadline) break
                val futures=chunk.chunked(FULL_SCAN_BATCH).map{ batch->
                    pool.submit<List<C>>{
                        val data=runCatching{ OracleMarketData.fetchDailyBatch(batch,"1y") }.getOrDefault(emptyMap())
                        data.mapNotNull{ (ticker,candles)-> if(candles.size>=60) runCatching{ evaluate(ticker,candles) }.getOrNull() else null }
                    }
                }
                futures.forEach{ f->
                    val remaining=deadline-System.nanoTime()
                    if(remaining>0) runCatching{ f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()?.forEach{ existing[it.ticker]=it }
                }
                writeScanCache(context,anchor,existing.values.toList())
                fullScanState=fullScanState.copy(scanned=existing.size)
            }
        } finally {
            pool.shutdownNow()
            writeScanCache(context,anchor,existing.values.toList())
            fullScanState=FullScanState(existing.size,universe.size,anchor,false,System.currentTimeMillis())
            OracleGrowthLog.log(context,"SCAN","Background scan finished: ${existing.size} of ${universe.size} tickers cached${if(existing.size<universe.size) " (will resume on the next wake-up)" else " \u2014 full coverage"}")
        }
        return existing.size
    }

    /** Number of scoring factors this engine version produces. A cached
     *  snapshot with a different count came from an older engine and must be
     *  regenerated rather than frozen (see OracleLocalProcessor). */
    fun factorCount():Int = keys.size
    val factorKeys:List<String> get() = keys

    /**
     * The full 17-factor component map for one ticker, computed by the very
     * same evaluate() Growth ranks with — so the Analysis screen shows the
     * identical numbers Growth would use, not a parallel re-implementation.
     * news / fundamentals / market_sector / community come back neutral (50)
     * here because they need enrichment the caller may already have done.
     * Loads the SPY benchmark on demand (relative_strength needs it) if no
     * Growth run has populated it yet in this process.
     */
    fun factorComponents(ticker:String, candles:List<OracleOhlcvPoint>):Map<String,Double>? {
        if(candles.size<60) return null
        if(benchmarkCloses.isEmpty()) benchmarkCloses=runCatching { OracleMarketData.fetchDaily("SPY","1y").sortedByDescending{it.timestamp}.map{it.close}.filter{it>0.0} }.getOrDefault(emptyList())
        return runCatching { evaluate(ticker,candles)?.components }.getOrNull()
    }

    fun communityScoreFor(ticker:String):Int? = communityScore(ticker)

    /**
     * Bump this whenever ANYTHING about what a recommendation carries changes
     * — a new factor, a new derived field (Fair Valuation, Financial
     * Health, ...), a formula change — even if factorCount() itself does
     * not move. This is what the freeze check compares, so a schema change
     * always forces exactly one fresh rank before freezing again, instead of
     * silently serving recommendations missing the new fields until the next
     * trading day. factorCount() alone was not enough: it missed the Fair
     * Valuation / Financial Health addition entirely (17 factors, unchanged).
     */
    const val ENGINE_TAG = "ORACLE_ENGINE_V6.1_FAIRVALUE_HEALTH"

    /** Benchmark (SPY) closes, newest-first, shared by every evaluate() call in
     *  a run so relative strength costs one fetch instead of one per candidate. */
    @Volatile private var benchmarkCloses:List<Double> = emptyList()

    /**
     * Hazard: a deliberate ±3-point nudge on the final score, as requested.
     * It is seeded by ticker + calendar day, so it behaves like a coin toss
     * across names and days but stays IDENTICAL for the same ticker within
     * the same day. That matters: a value redrawn on every refresh would make
     * the same stock score differently minute to minute, and would poison the
     * Performance module, which compares a recorded signal against what the
     * engine would say later. Change the seed line to Random.nextInt if a
     * true per-run redraw is ever wanted.
     */
    fun hazardFor(ticker:String, dayMillis:Long=System.currentTimeMillis()):Int{
        val day=dayMillis/86_400_000L
        val seed=(ticker.uppercase(Locale.US).hashCode().toLong()*31L+day)
        return (kotlin.random.Random(seed).nextInt(7))-3
    }

    /** SHORT technical base score (0..100) for any ticker from its daily candles —
     *  the same evaluate() Growth ranks with, so Portfolio can speak the same
     *  language. Null when there is not enough history (< 60 sessions). */
    fun technicalScore(candles:List<OracleOhlcvPoint>):Int? = if(candles.size<60) null else runCatching { evaluate("_", candles)?.score }.getOrNull()

    data class MarketRegime(val level:String, val note:String, val allocationFactor:Double)

    /** Absolute market gate. A relative ranking always yields a "best" stock —
     *  even in a crash. This says whether the tide is with it at all:
     *  DEFENSIVE = SPY below its 200-day average or VIX > 30 (no BUY labels,
     *  allocation halved); CAUTION = SPY below its 50-day average or VIX > 22
     *  (no STRONG BUY, allocation ×0.75); NORMAL otherwise. Fail-open to
     *  NORMAL if the index data can't be fetched, and says so. */
    fun marketRegime():MarketRegime = runCatching {
        val spy=OracleMarketData.fetchDaily("SPY","1y").sortedBy{it.timestamp}.map{it.close}.filter{it>0.0}
        val vix=OracleMarketData.fetchDaily("^VIX","3mo").sortedBy{it.timestamp}.map{it.close}.filter{it>0.0}.lastOrNull()
        if(spy.size<200) return@runCatching MarketRegime("NORMAL","Regime check unavailable (index history too short)",1.0)
        val p=spy.last(); val sma200=spy.takeLast(200).average(); val sma50=spy.takeLast(50).average()
        val f=java.util.Locale.US
        val vixText=vix?.let{" \u00b7 VIX %.1f".format(f,it)} ?: ""
        when{
            p<sma200 || (vix!=null&&vix>30.0) -> MarketRegime("DEFENSIVE","S&P 500 %.0f is %s its 200-day average (%.0f)%s \u2014 no BUY labels, allocations halved".format(f,p,if(p<sma200)"below" else "above",sma200,vixText),0.5)
            p<sma50 || (vix!=null&&vix>22.0) -> MarketRegime("CAUTION","S&P 500 %.0f is %s its 50-day average (%.0f)%s \u2014 no STRONG BUY, allocations reduced".format(f,p,if(p<sma50)"below" else "above",sma50,vixText),0.75)
            else -> MarketRegime("NORMAL","S&P 500 %.0f above its 50- and 200-day averages%s".format(f,p,vixText),1.0)
        }
    }.getOrElse { MarketRegime("NORMAL","Regime check unavailable (index data not reachable)",1.0) }

    private fun capSignal(signal:String, regime:MarketRegime):String = when(regime.level){
        "DEFENSIVE" -> when(signal){ "STRONG BUY","BUY" -> "HOLD"; else -> signal }
        "CAUTION" -> if(signal=="STRONG BUY") "BUY" else signal
        else -> signal
    }

    fun run(context: Context, seed:List<OracleGrowthRecommendation> = emptyList()):List<OracleGrowthRecommendation> = try {
        tryServerPicks(context) ?: runInternal(context, seed)
    } catch (_: Exception) {
        // Defensive: the universe/OHLCV/enrichment paths already catch their own
        // errors internally, but a genuinely unexpected failure must still leave
        // the loader in an explicit, non-infinite state (Requirement #6/#11)
        // rather than propagating past the single-flight snapshot lock.
        progressState = progressState.copy(phase = OracleGrowthPhase.NO_DATA)
        emptyList()
    }

    /**
     * Stage 3: alintudor.ro now runs this exact ranking server-side, once per
     * trading day, over its own full universe scan (no ~700-ticker on-device
     * budget). Tried first — a fast single GET — before ever falling back to
     * the slower on-device scan/rank path below. Returns null (falls back)
     * whenever there is nothing usable yet to switch to: no session, a
     * network failure, or fewer than 3 picks for today's anchor (the
     * server's own scan/rank may simply not have finished yet — it is not
     * an error, just "not ready", and the on-device path covers that day
     * exactly as it always has).
     *
     * Fair Valuation/Financial Health are not computed server-side yet, so
     * they're filled in here from a fresh fundamentals fetch — only 3
     * tickers, negligible cost — using the exact same OracleValuation calls
     * the on-device path uses, so the two paths render identically.
     */
    private fun tryServerPicks(context: Context): List<OracleGrowthRecommendation>? {
        val token = OracleAuthStore(context).token()
        if (token.isBlank()) return null
        val response = OracleApiClient.getGrowthPicks(token).getOrNull() ?: return null
        val items = response.optJSONArray("items") ?: return null
        if (items.length() < 3) return null
        val now = System.currentTimeMillis()
        val recs = mutableListOf<OracleGrowthRecommendation>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val ticker = o.optString("ticker").uppercase(Locale.US).takeIf { it.isNotBlank() } ?: continue
            val horizon = o.optString("horizon").uppercase(Locale.US).takeIf { weights.containsKey(it) } ?: continue
            val price = o.optDouble("price", 0.0).takeIf { it > 0.0 }
            val componentsJson = o.optJSONObject("components")
            val sector = o.optString("sector").takeIf { it.isNotBlank() } ?: "—"
            val fundamentals = runCatching { OracleRealData.fundamentals(ticker) }.getOrNull()
            val fairValue = OracleValuation.fairValue(fundamentals, sector)
            val health = OracleValuation.financialHealth(fundamentals)
            val company = OracleSP500Universe.nameFor(context, ticker)
                ?: OracleMarketUniverse.nameFor(context, ticker)
                ?: lookupCompanyName(ticker)
                ?: ticker
            recs += OracleGrowthRecommendation(
                horizon = horizon, ticker = ticker, company = company, sector = sector,
                score = o.optInt("score"), signal = o.optString("signal"), risk = o.optString("risk"),
                allocationMax = o.optDouble("allocation", 1.0), forecastPct = o.optDouble("forecastPct", 0.0),
                momentum5D = o.optDouble("momentum5D", 0.0), momentum20D = o.optDouble("momentum20D", 0.0),
                weights = weights[horizon]!!.toList(),
                referencePrice = price, currentPrice = price,
                adx = o.optDouble("adx", -1.0).takeIf { it >= 0.0 },
                factorValues = keys.map { componentsJson?.optDouble(it, 50.0) ?: 50.0 },
                generatedAt = now,
                source = ENGINE_TAG,
                marketRegime = o.optString("marketRegime", "NORMAL"),
                regimeNote = o.optString("regimeNote", ""),
                earningsInDays = if (o.isNull("earningsInDays")) null else o.optInt("earningsInDays").takeIf { it >= 0 },
                hazard = o.optInt("lo", 0),
                fairValueLabel = fairValue.label, fairValueScore = fairValue.score,
                financialHealthLabel = health.label, financialHealthScore = health.score
            )
        }
        if (recs.size < 3) return null
        // The on-device path drives the loader's progress readout as it
        // scans/enriches; the server path is one fast GET with nothing to
        // show progress on. Mark it DONE directly so a leftover "RUNNING"
        // state from a previous on-device run can't flash on screen.
        progressState = OracleGrowthProgress(recs.size, recs.size, System.nanoTime(), OracleGrowthPhase.DONE)
        OracleGrowthLog.log(context, "RUN", "Using server ranking (Stage 3): " + recs.joinToString(", ") { "${it.horizon}=${it.ticker} ${it.score}" })
        return recs
    }

    private fun runInternal(context: Context, seed:List<OracleGrowthRecommendation>):List<OracleGrowthRecommendation>{
        val t0=System.nanoTime()
        OracleGrowthLog.log(context,"RUN","Growth run started (engine V6.0, ${keys.size} factors, seed=${seed.size} previous recommendations)")

        // Universe: S&P 500 union every tradable Nasdaq listing (cap >= $2B,
        // volume >= 500k), ordered by market cap — resolved from memory/disk,
        // never a blocking network call. The engine scans the most liquid
        // prefix of it (ON_DEVICE_SCAN_LIMIT), because the scan has a hard
        // time budget and covering more than fits would just mean dropping a
        // connection-speed-dependent subset mid-run. Falls back to the S&P 500
        // alone whenever the extended feed is unavailable.
        val universe=runCatching { OracleMarketUniverse.scanTickers(context) }
            .getOrDefault(emptyList())
            .ifEmpty { runCatching { OracleSP500Universe.tickers(context) }.getOrDefault(emptyList()) }
            .distinct()
        val universeTotal=runCatching { OracleMarketUniverse.totalSize(context) }.getOrDefault(0)
        OracleGrowthLog.log(context,"UNIVERSE","Universe resolved: ${universeTotal} companies available (S&P 500 + tradable Nasdaq), scanning ${universe.size} most liquid on device")
        progressState=OracleGrowthProgress(0, universe.size.coerceAtLeast(1), t0, OracleGrowthPhase.RUNNING)
        if(universe.isEmpty()){
            progressState=progressState.copy(phase=OracleGrowthPhase.NO_DATA)
            return emptyList()
        }

        // B540: single global deadline for the whole run (Requirement #5). The
        // enrichment phase always targets the same absolute `totalDeadline`, so
        // a slow scan phase shrinks — never extends — the total wall time.
        val totalDeadline=t0+TOTAL_BUDGET_NANOS
        val scanDeadline=minOf(t0+SCAN_BUDGET_NANOS, totalDeadline)

        // B540: controlled parallel batch scan (Requirement #5/#6/#11). Batches of
        // BATCH_SIZE (25-50) symbols run on a bounded thread pool; the visible
        // "DATA LOADED" counter reflects OHLCV actually received, updated as
        // each batch completes (never an artificial/simulated counter).
        // One benchmark fetch per run powers relative strength for every candidate.
        benchmarkCloses=runCatching { OracleMarketData.fetchDaily("SPY","1y").sortedByDescending{it.timestamp}.map{it.close}.filter{it>0.0} }.getOrDefault(emptyList())
        // If the background scan already covered the whole universe for this
        // trading day, rank that instead of re-fetching a bounded subset live.
        val anchorNow=OracleMarketCalendar.growthAnchor(System.currentTimeMillis())
        val cachedCandidates=readScanCache(context,anchorNow)
        if(cachedCandidates.size>=200){
            OracleGrowthLog.log(context,"CACHE","Using background full-universe scan: ${cachedCandidates.size} candidates cached for anchor ${java.text.SimpleDateFormat("dd.MM HH:mm",Locale.US).format(java.util.Date(anchorNow))} \u2014 no live scan needed")
            progressState=OracleGrowthProgress(cachedCandidates.size, cachedCandidates.size, t0, OracleGrowthPhase.RUNNING)
            return rankCandidates(context,cachedCandidates,t0,totalDeadline,seed)
        }
        OracleGrowthLog.log(context,"CACHE","No usable background scan for this trading day (found ${cachedCandidates.size}) \u2014 falling back to bounded live scan")

        val loadedCounter=AtomicInteger(0)
        val candidateQueue=ConcurrentLinkedQueue<C>()
        val scanPool=Executors.newFixedThreadPool(SCAN_THREADS)
        try{
            val futures=universe.chunked(BATCH_SIZE).map{batch->
                scanPool.submit{
                    val data=runCatching { OracleMarketData.fetchDailyBatch(batch,"1y") }.getOrDefault(emptyMap())
                    loadedCounter.addAndGet(data.size)
                    progressState=progressState.copy(loaded=loadedCounter.get().coerceAtMost(universe.size))
                    for((ticker,candles) in data){
                        if(System.nanoTime()<scanDeadline && candles.size>=60){
                            runCatching { evaluate(ticker,candles) }.getOrNull()?.let{candidateQueue.add(it)}
                        }
                    }
                }
            }
            futures.forEach{f->
                val remaining=scanDeadline-System.nanoTime()
                if(remaining>0) runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }
            }
        } finally {
            scanPool.shutdownNow()
        }
        progressState=progressState.copy(loaded=loadedCounter.get().coerceAtMost(universe.size))
        OracleGrowthLog.log(context,"SCAN","Live scan finished: OHLCV received for ${loadedCounter.get()} of ${universe.size} requested, ${candidateQueue.size} passed the 60-session minimum, ${"%.1f".format((System.nanoTime()-t0)/1_000_000_000.0)}s elapsed")

        if(candidateQueue.isEmpty()){
            // Requirement #6/#11: a run that genuinely receives zero OHLCV must
            // report an explicit NO_DATA state, not stay in RUNNING forever.
            OracleGrowthLog.log(context,"ERROR","Run aborted: zero OHLCV received (no network, or every request failed)")
            progressState=progressState.copy(phase=OracleGrowthPhase.NO_DATA)
            return emptyList()
        }
        val candidates=candidateQueue.toList()
        return rankCandidates(context,candidates,t0,totalDeadline,seed)
    }

    private fun rankCandidates(context: Context, candidates:List<C>, t0:Long, totalDeadline:Long, seed:List<OracleGrowthRecommendation>):List<OracleGrowthRecommendation>{
        OracleGrowthLog.log(context,"RANK","Ranking ${candidates.size} candidates across SHORT / MEDIUM / LONG")
        // Previously computed in runInternal; it belongs here because this is
        // the only place it is read (carries forward the previous snapshot's
        // news headline / source / T0 for a ticker that is picked again).
        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}

        // Enrich the technical shortlist with real non-OHLC data before ranking.
        // The previous implementation silently used 50/100 for News, Fundamentals
        // and Market/Sector, which made those displayed values look calculated while
        // they were actually placeholders. Growth now derives those factors from
        // OracleRealData and only falls back to neutral 50 when the source genuinely
        // has no value.
        val technicalShortlist=candidates.sortedByDescending{it.score}.take(30)

        // B540: enrichment runs in parallel with its own deadline, bounded by the
        // same absolute totalDeadline used for the scan phase (Requirement #5).
        val enrichDeadline=totalDeadline
        val enrichPool=Executors.newFixedThreadPool(ENRICH_THREADS)
        val regimeFuture=enrichPool.submit<MarketRegime> { marketRegime() }
        val communityFutures=technicalShortlist.associate { c ->
            c.ticker to enrichPool.submit<Int?> { communityScore(c.ticker) }
        }
        val earningsFutures=technicalShortlist.associate { c ->
            c.ticker to enrichPool.submit<Long?> { OracleRealData.nextEarningsDate(c.ticker) }
        }
        val fundamentalFutures=technicalShortlist.associate { c ->
            c.ticker to enrichPool.submit<OracleFundamentals?> { runCatching { OracleRealData.fundamentals(c.ticker) }.getOrNull() }
        }
        val fundamentals=mutableMapOf<String,OracleFundamentals?>()
        fundamentalFutures.forEach{(ticker,f)->
            val remaining=enrichDeadline-System.nanoTime()
            if(remaining>0) runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()?.let{fundamentals[ticker]=it}
        }
        val newsCandidates=technicalShortlist.take(15)
        val newsFutures=newsCandidates.associate { c ->
            c.ticker to enrichPool.submit<OracleNewsContext> { runCatching { OracleRealData.newsContext(c.ticker) }.getOrDefault(OracleNewsContext(50,0,0,0,null)) }
        }
        val newsContexts=mutableMapOf<String,OracleNewsContext>()
        newsFutures.forEach{(ticker,f)->
            val remaining=enrichDeadline-System.nanoTime()
            if(remaining>0) runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()?.let{newsContexts[ticker]=it}
        }
        val communityScores=mutableMapOf<String,Int>()
        communityFutures.forEach{(ticker,f)->
            val remaining=enrichDeadline-System.nanoTime()
            if(remaining>0) runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()?.let{ communityScores[ticker]=it }
        }
        val regime=runCatching { regimeFuture.get(maxOf(0L,enrichDeadline-System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrNull()
            ?: MarketRegime("NORMAL","Regime check timed out",1.0)
        val nowMs=System.currentTimeMillis()
        val earningsInDays=mutableMapOf<String,Int>()
        earningsFutures.forEach{(ticker,f)->
            val remaining=enrichDeadline-System.nanoTime()
            if(remaining>0) runCatching { f.get(remaining, TimeUnit.NANOSECONDS) }.getOrNull()?.let{ ms-> val days=((ms-nowMs)/86_400_000L).toInt(); if(days>=-1) earningsInDays[ticker]=maxOf(0,days) }
        }
        enrichPool.shutdownNow()
        OracleGrowthLog.log(context,"ENRICH","Shortlist of ${technicalShortlist.size} enriched: news ${newsContexts.size}, fundamentals ${fundamentals.size}, community ${communityScores.size}, earnings dates ${earningsInDays.size}; market regime ${regime.level}")

        val sectorsNeeded=technicalShortlist.mapNotNull{resolveSector(context,it.ticker,fundamentals[it.ticker]?.sector)}.distinct()
        val sectorContexts=mutableMapOf<String,Double>()
        val sectorPool=Executors.newFixedThreadPool(SECTOR_THREADS)
        val sectorFutures=sectorsNeeded.associateWith{sector-> sectorPool.submit<Double> { runCatching { OracleRealData.sectorScore(OracleRealData.marketContext(sector)) }.getOrNull() ?: 50.0 } }
        sectorFutures.forEach{(sector,f)->
            val remaining=enrichDeadline-System.nanoTime()
            val bounded=if(remaining>0) minOf(remaining,SECTOR_TASK_CAP_NANOS) else 0L
            if(bounded>0) runCatching { f.get(bounded, TimeUnit.NANOSECONDS) }.getOrNull()?.let{sectorContexts[sector]=it}
        }
        sectorPool.shutdownNow()

        val enriched=candidates.map{c->
            val f=fundamentals[c.ticker]
            val sector=resolveSector(context,c.ticker,f?.sector)
            val comp=c.components.toMutableMap()
            comp["fundamentals"]=(OracleRealData.fundamentalScore(f) ?: 50.0).coerceIn(0.0,100.0)
            comp["market_sector"]=(sector?.let { sectorContexts[it] } ?: 50.0).coerceIn(0.0,100.0)
            val n=newsContexts[c.ticker]
            comp["news"]=(n?.score?.toDouble() ?: 50.0).coerceIn(0.0,100.0)
            comp["community"]=(communityScores[c.ticker]?.toDouble() ?: 50.0).coerceIn(0.0,100.0)
            c.copy(score=horizonScore(comp,"SHORT",sector),components=comp,news=n?.headlineCount ?: 0)
        }
        val out=mutableListOf<OracleGrowthRecommendation>();val used=mutableSetOf<String>()
        for(h in listOf("SHORT","MEDIUM","LONG")){
            val ranked=enriched.sortedWith(compareByDescending<C>{horizonScore(it.components,h,resolveSector(context,it.ticker,fundamentals[it.ticker]?.sector))}.thenByDescending{tie(it,h)}.thenByDescending{it.score})
            // An entry into an earnings report is a coin flip, not a setup:
            // SHORT and MEDIUM skip names reporting within 7 days.
            val pick=ranked.firstOrNull{ it.ticker !in used && !(h!="LONG" && (earningsInDays[it.ticker] ?: 99) <= 7) }?:continue
            used+=pick.ticker
            val baseScore=horizonScore(pick.components,h,resolveSector(context,pick.ticker,fundamentals[pick.ticker]?.sector))
            val hazard=hazardFor(pick.ticker)
            val score=(baseScore+hazard).coerceIn(0,100)
            val meta=byTicker[pick.ticker]
            val cachedTitle=meta?.newsTitle?.takeIf { it.isNotBlank() && !it.contains("Google News",true) && !it.contains(" when:",true) }
            val f=fundamentals[pick.ticker]
            val sector=resolveSector(context,pick.ticker,f?.sector ?: meta?.sector) ?: "—"
            val fairValue=OracleValuation.fairValue(f,sector)
            val health=OracleValuation.financialHealth(f)
            val correctedAllocation=(OracleSectorAllocation.apply(pick.allocation,sector)*regime.allocationFactor).let{ kotlin.math.round(it*10.0)/10.0 }.coerceAtLeast(0.5)
            val correctedWeights=weights[h]!!.copyOf()
            val news=newsContexts[pick.ticker]
            val company=meta?.company?.takeIf { it.isNotBlank() && !it.equals(pick.ticker,true) }
                ?: OracleSP500Universe.nameFor(context,pick.ticker)
                ?: OracleMarketUniverse.nameFor(context,pick.ticker)
                ?: lookupCompanyName(pick.ticker)
                ?: pick.ticker
            OracleGrowthLog.log(context,"RANK","$h pick: ${pick.ticker} \u2014 base score $baseScore, LO ${if(hazard>=0)"+" else ""}$hazard, final $score, signal ${capSignal(rating(score),regime)}, allocation ${correctedAllocation}%, sector $sector${earningsInDays[pick.ticker]?.let{" (earnings in $it days)"} ?: ""}")
            out+=OracleGrowthRecommendation(horizon=h,ticker=pick.ticker,company=company,sector=sector,score=score,signal=capSignal(rating(score),regime),risk=pick.risk,allocationMax=correctedAllocation,forecastPct=pick.forecast[h.lowercase(Locale.US)]?:0.0,momentum5D=pick.mom5,momentum20D=pick.mom20,weights=correctedWeights.toList(),newsTitle=cachedTitle ?: news?.topHeadline.orEmpty(),newsSource=meta?.newsSource.orEmpty(),referenceTimestamp=meta?.referenceTimestamp?:0L,currentPrice=pick.price,adx=pick.adx,factorValues=keys.map{pick.components[it]?:50.0},factorScore=score.toDouble(),generatedAt=System.currentTimeMillis(),source=ENGINE_TAG,marketRegime=regime.level,regimeNote=regime.note,earningsInDays=earningsInDays[pick.ticker],hazard=hazard,
                fairValueLabel=fairValue.label,fairValueScore=fairValue.score,financialHealthLabel=health.label,financialHealthScore=health.score)
        }
        progressState=progressState.copy(phase=if(out.isEmpty()) OracleGrowthPhase.NO_DATA else OracleGrowthPhase.DONE)
        return out
    }

    /** Sector resolution used by Growth only: live/known sector, then the S&P 500 universe (Requirement #8). */
    private fun resolveSector(context: Context, ticker:String, remoteSector:String?):String? =
        OracleRealData.resolvedSector(ticker,remoteSector)
            ?: OracleSP500Universe.sectorFor(context,ticker)
            ?: OracleMarketUniverse.sectorFor(context,ticker)

    private fun lookupCompanyName(ticker:String):String? = runCatching {
        val ua="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"
        val url=URL("https://query1.finance.yahoo.com/v7/finance/quote?symbols=${URLEncoder.encode(ticker,"UTF-8")}&formatted=false&lang=en-US&region=US")
        val c=url.openConnection() as HttpURLConnection
        c.connectTimeout=5000; c.readTimeout=7000; c.requestMethod="GET"; c.setRequestProperty("User-Agent",ua); c.setRequestProperty("Accept","application/json")
        val body=c.inputStream.bufferedReader().use{it.readText()}; c.disconnect()
        val root=org.json.JSONObject(body); val q=root.optJSONObject("quoteResponse")?.optJSONArray("result")?.optJSONObject(0) ?: return@runCatching null
        q.optString("longName").takeIf{it.isNotBlank()} ?: q.optString("shortName").takeIf{it.isNotBlank()}
    }.getOrNull()

    private fun evaluate(t:String,d:List<OracleOhlcvPoint>):C?{
        val r=d.sortedByDescending{it.timestamp}
        val close=r.map{it.close};val high=r.map{it.high};val low=r.map{it.low};val vol=r.map{it.volume};val p=close[0]
        fun avg(n:Int)=if(close.size>=n)close.take(n).average()else null
        fun std(n:Int):Double?{if(close.size<n)return null;val a=close.take(n);val m=a.average();return sqrt(a.sumOf{(it-m)*(it-m)}/n)}
        fun mom(n:Int)=if(close.size>n)(p/close[n]-1)*100 else 0.0
        val s20=avg(20);val s50=avg(50);val s200=avg(200);val m5=mom(5);val m20=mom(20)
        val gains=close.dropLast(1).take(14).mapIndexed{i,x->max(0.0,x-close[i+1])}.average();val losses=close.dropLast(1).take(14).mapIndexed{i,x->max(0.0,close[i+1]-x)}.average();val rsi=if(losses==0.0)100.0 else 100-100/(1+gains/losses)
        val v20=if(vol.size>=20)vol.take(20).average()else 0.0;val vr=if(v20>0)vol[0]/v20 else 1.0;val prior20=if(close.size>=21)close.drop(1).take(20).maxOrNull()?:p else p;val breakout=if(p>prior20&&vr>=1.25)100.0 else if(p>prior20)62.0 else if(p>=prior20*.97)48.0 else 25.0
        val lo=close.take(20).minOrNull()?:p;val hi=close.take(20).maxOrNull()?:p;val sr=if(hi>lo)(30+70*(p-lo)/(hi-lo)).coerceIn(0.0,100.0)else 50.0;val mid=avg(20);val sd=std(20);val bbPos=if(mid!=null&&sd!=null&&sd>0)(p-(mid-2*sd))/(4*sd)else .5;val bbWidth=if(mid!=null&&mid>0)100*(4*(sd?:0.0))/mid else 0.0
        val ema12=ema(close,12);val ema26=ema(close,26);val macd=if(ema12!=null&&ema26!=null)ema12-ema26 else null;val atr=atr(high,low,close,14)?:p*.01;val atrPct=100*atr/p;val adx=adx(high,low,close,14)
        val ichi=if(close.size>=52){val t9=(high.take(9).max()+low.take(9).min())/2;val k26=(high.take(26).max()+low.take(26).min())/2;val a=(t9+k26)/2;val b=(high.take(52).max()+low.take(52).min())/2;p>max(a,b)&&t9>k26}else false
        val trend=(50.0+(if(s20!=null&&p>s20)16 else -16)+(if(s50!=null&&p>s50)17 else -17)+(if(s200!=null&&p>s200)17 else -17)).coerceIn(0.0,100.0);val momentum=(50+m5*2+m20*.65).coerceIn(0.0,100.0);val volume=(50+(vr-1)*45).coerceIn(0.0,100.0);val boll=(50+(bbPos-.5)*80+(if(bbWidth>0&&bbWidth<8)10 else 0)).coerceIn(0.0,100.0);val ichScore=if(ichi)90.0 else 30.0;val adxc=(35+(adx?:0.0)*1.15).coerceIn(0.0,100.0);val rr=(70-atrPct*5+(if(breakout>=100)15 else 0)).coerceIn(0.0,100.0)
        val overextension=((rsi-65.0)/15.0).coerceIn(0.0,1.0);val volatility=(atrPct/8.0).coerceIn(0.0,1.0);val volumeShock=((vr-1.0)/2.0).coerceIn(0.0,1.0);val acceleration=(abs(m5)/20.0).coerceIn(0.0,1.0);val riskScore=(100.0*(overextension*.30+volatility*.35+volumeShock*.15+acceleration*.20)).coerceIn(0.0,100.0);val risk=when{riskScore>=65.0->"HIGH";riskScore>=35.0->"MEDIUM";else->"LOW"}
        // --- V6.0 factors -------------------------------------------------
        // Relative strength: 60-session return vs the benchmark's. Beating the
        // index is the point; rising with a rising tide is not the same thing.
        val relStrength=run{
            val bench=benchmarkCloses
            if(close.size<=60||bench.size<=60) 50.0 else {
                val mine=(p/close[60]-1.0)*100.0
                val theirs=(bench[0]/bench[60]-1.0)*100.0
                (50.0+(mine-theirs)*1.6).coerceIn(0.0,100.0)
            }
        }
        // Volatility regime: 20-day dispersion against 100-day. Compression
        // (ratio well under 1) is the classic pre-expansion setup; an already
        // exploded range scores low because the move is largely spent.
        val volRegime=run{
            val s20v=std(20); val s100v=std(100)
            if(s20v==null||s100v==null||s100v<=0.0) 50.0
            else (100.0-((s20v/s100v)-0.55)*95.0).coerceIn(0.0,100.0)
        }
        // 52-week range position: how far below the 52-week high price sits.
        // Right under the high is breakout territory; deep in the hole is not.
        val rangePos=run{
            val win=close.take(minOf(252,close.size))
            val hi52=win.maxOrNull(); val lo52=win.minOrNull()
            if(hi52==null||lo52==null||hi52<=lo52) 50.0
            else {
                val fromHigh=(hi52-p)/hi52*100.0
                when{ fromHigh<=3.0->92.0; fromHigh<=10.0->80.0; fromHigh<=20.0->62.0; fromHigh<=35.0->45.0; fromHigh<=50.0->30.0; else->18.0 }
            }
        }
        // Volume trend: 20-day OBV slope as a share of traded volume — is
        // money accumulating or distributing, rather than one loud session.
        val volTrend=run{
            if(close.size<21||vol.size<21) 50.0 else {
                var obv=0.0; var totalVol=0.0
                for(i in 19 downTo 0){ val dir=if(close[i]>close[i+1]) 1.0 else if(close[i]<close[i+1]) -1.0 else 0.0; obv+=dir*vol[i]; totalVol+=vol[i] }
                if(totalVol<=0.0) 50.0 else (50.0+(obv/totalVol)*110.0).coerceIn(0.0,100.0)
            }
        }
        val comps=mapOf("news" to 50.0,"breakout" to breakout,"trend" to trend,"momentum" to momentum,"volume" to volume,"support_resistance" to sr,"fundamentals" to 50.0,"bollinger" to boll,"ichimoku" to ichScore,"market_sector" to 50.0,"risk_reward" to rr,"adx" to adxc,
            "relative_strength" to relStrength,"volatility_regime" to volRegime,"range_position" to rangePos,"volume_trend" to volTrend,"community" to 50.0)
        val base=horizonScore(comps,"SHORT",null)
        val f=mapOf("short" to min(30.0,max(0.0,((p+2*atr)/p-1)*100)),"medium" to min(45.0,max(0.0,((p+4.5*atr)/p-1)*100)),"long" to min(70.0,max(0.0,((p+8*atr)/p-1)*100)))
        val alloc=when{risk=="HIGH"->max(1.0,base*.04);risk=="MEDIUM"->max(1.0,base*.06);else->max(1.0,base*.08)}.coerceAtMost(8.0).let{ kotlin.math.round(it*10.0)/10.0 }
        return C(t,p,base,rsi,m5,m20,vr,macd,ichi,s200,s50,adx,atrPct,comps,f,risk,alloc,0)
    }

    /** V5.9.7: sector correction is applied only to allocation, never to score. */
    private fun horizonScore(c:Map<String,Double>,h:String,sector:String?):Int{
        val w=weights[h]!!
        val total=w.sum().toDouble()
        val raw=(keys.indices.sumOf{(c[keys[it]]?:50.0)*w[it].toDouble()}/total).toInt().coerceIn(0,100)
        return when{raw in 97..100->raw-3;raw in 92..96->raw-1;else->raw}
    }

    private fun tie(c:C,h:String):Double=0.0
    private fun rating(s:Int)=ratingFor(s)
    fun ratingFor(s:Int)=when{s>=85->"STRONG BUY";s>=75->"BUY";s>=65->"HOLD";s>=55->"WATCH";else->"AVOID"}
    private fun ema(v:List<Double>,n:Int):Double?{if(v.size<n)return null;var e=v.takeLast(n).average();val k=2.0/(n+1);for(i in v.size-n until v.size)e=v[i]*k+e*(1-k);return e}
    private fun atr(h:List<Double>,l:List<Double>,c:List<Double>,n:Int):Double?{if(c.size<n+1)return null;val tr=(0 until c.size-1).map{i->maxOf(h[i]-l[i],abs(h[i]-c[i+1]),abs(l[i]-c[i+1]))};return tr.take(n).average()}
    private fun adx(h:List<Double>,l:List<Double>,c:List<Double>,n:Int):Double?{if(c.size<n*2+2)return null;val tr=mutableListOf<Double>();val pd=mutableListOf<Double>();val md=mutableListOf<Double>();for(i in 0 until c.size-1){val up=h[i]-h[i+1];val dn=l[i+1]-l[i];tr+=maxOf(h[i]-l[i],abs(h[i]-c[i+1]),abs(l[i]-c[i+1]));pd+=if(up>dn&&up>0)up else 0.0;md+=if(dn>up&&dn>0)dn else 0.0};var atrv=tr.take(n).average();var p=pd.take(n).average();var m=md.take(n).average();val dx=mutableListOf<Double>();for(i in n until tr.size){atrv=(atrv*(n-1)+tr[i])/n;p=(p*(n-1)+pd[i])/n;m=(m*(n-1)+md[i])/n;val pi=if(atrv>0)100*p/atrv else 0.0;val mi=if(atrv>0)100*m/atrv else 0.0;dx+=if(pi+mi>0)100*abs(pi-mi)/(pi+mi)else 0.0};return if(dx.size<n)dx.average()else dx.takeLast(n).average()}
    /**
     * Community sentiment (0..100, 50 = neutral/unknown): retail chatter about
     * the ticker, from Reddit's public search JSON, scored with the same phrase
     * lexicon the news feed uses. Chatter is opinion, not fact, so it is
     * deliberately pulled toward neutral when few posts mention the name, and
     * returns null (→ neutral 50) whenever the source is unreachable.
     */
    private fun communityScore(t:String):Int? = try {
        val q=URLEncoder.encode("\"$t\" stock OR shares","UTF-8")
        val u=URL("https://www.reddit.com/search.json?q=$q&sort=new&t=week&limit=40")
        val con=u.openConnection() as HttpURLConnection
        con.connectTimeout=5000; con.readTimeout=7000
        con.setRequestProperty("User-Agent","OracleApp/1.0 (community sentiment)")
        val body=if(con.responseCode in 200..299) con.inputStream.bufferedReader().use{it.readText()} else ""
        con.disconnect()
        if(body.isBlank()) null else {
            val children=org.json.JSONObject(body).optJSONObject("data")?.optJSONArray("children")
            val titles=ArrayList<String>()
            if(children!=null) for(i in 0 until children.length()){
                val d=children.optJSONObject(i)?.optJSONObject("data") ?: continue
                val title=d.optString("title","")
                if(title.isNotBlank()) titles+=title
            }
            if(titles.isEmpty()) null else {
                val raw=OracleSentiment.score(titles)
                // Few mentions = weak evidence: shrink toward 50.
                val confidence=(titles.size/12.0).coerceIn(0.3,1.0)
                (50.0+(raw-50.0)*confidence).toInt().coerceIn(0,100)
            }
        }
    } catch(_:Exception){ null }

}

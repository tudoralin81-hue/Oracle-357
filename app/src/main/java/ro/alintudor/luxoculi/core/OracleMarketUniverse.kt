package ro.alintudor.luxoculi.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The universe Growth ranks over: the S&P 500 plus every other Nasdaq-listed
 * company that is actually tradable, ordered by market capitalisation.
 *
 * Why not the raw Nasdaq Composite: it lists 3,000+ securities, the long tail
 * being micro-caps, shells and sub-dollar names with a few thousand shares of
 * daily volume. Scoring those produces confident-looking signals on things
 * nobody can enter or exit at the quoted price. The filter below
 * (MIN_MARKET_CAP / MIN_AVG_VOLUME) is what separates "more universe" from
 * "more noise". The Dow Jones 30 is deliberately not a third source: every
 * one of its members is already an S&P 500 constituent, so it adds nothing.
 *
 * Resolution mirrors OracleSP500Universe: instant from memory/disk, with a
 * bounded background refresh that only affects the *next* run. If the Nasdaq
 * feed is unreachable the universe silently degrades to the S&P 500 alone —
 * the app never blocks or empties out because a third party is down.
 */
object OracleMarketUniverse {
    data class Company(val ticker: String, val name: String, val sector: String, val marketCap: Double, val avgVolume: Double)

    // Tradability floor. Below these a technical score is not actionable.
    private const val MIN_MARKET_CAP = 2_000_000_000.0
    private const val MIN_AVG_VOLUME = 500_000.0

    /**
     * How many symbols the on-device engine actually scans, taken from the top
     * of the market-cap ordering. The scan has a hard ~13s budget; anything
     * beyond what fits is silently dropped mid-run, which would make results
     * depend on connection speed rather than on the market. So the phone
     * deliberately scans a bounded, deterministic prefix instead of pretending
     * to cover everything. The background full-universe scan
     * (OracleGrowthScanReceiver -> OracleGrowthEngine.scanFullUniverse) has no
     * such budget and covers companies() in full, ignoring this cap; this
     * limit only applies to the live fallback scan used before the first
     * background scan of the day has completed.
     */
    const val ON_DEVICE_SCAN_LIMIT = 700

    private const val PREFS = "oracle_market_universe"
    private const val KEY_JSON = "universe_json"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val NASDAQ_SCREENER_URL = "https://api.nasdaq.com/api/screener/stocks?tableonly=true&limit=25000&exchange=NASDAQ&download=true"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 9_000

    @Volatile private var memoryCache: List<Company>? = null
    @Volatile private var refreshInFlight = false
    private val loadLock = Any()

    /** Full universe, instant. Never performs a blocking network call. */
    fun companies(context: Context): List<Company> {
        memoryCache?.let { return it }
        synchronized(loadLock) {
            memoryCache?.let { return it }
            val cached = readDiskCache(context)
            val base = if (!cached.isNullOrEmpty()) cached else fromSp500Only(context)
            memoryCache = base
            scheduleBackgroundRefreshIfStale(context)
            return base
        }
    }

    /** What the on-device engine scans: the most liquid prefix of the universe. */
    fun scanTickers(context: Context): List<String> =
        companies(context).map { it.ticker }.distinct().take(ON_DEVICE_SCAN_LIMIT)

    fun sectorFor(context: Context, ticker: String): String? =
        companies(context).firstOrNull { it.ticker.equals(ticker, true) }?.sector?.takeIf { it.isNotBlank() }

    fun nameFor(context: Context, ticker: String): String? =
        companies(context).firstOrNull { it.ticker.equals(ticker, true) }?.name?.takeIf { it.isNotBlank() }

    /** Universe size actually available, for display ("scanning N of M"). */
    fun totalSize(context: Context): Int = companies(context).size

    private fun fromSp500Only(context: Context): List<Company> =
        OracleSP500Universe.companies(context).map { Company(it.ticker, it.name, it.sector, 0.0, 0.0) }

    private fun readDiskCache(context: Context): List<Company>? = runCatching {
        val raw = prefs(context).getString(KEY_JSON, null) ?: return null
        parseJsonArray(raw).takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun writeDiskCache(context: Context, list: List<Company>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("ticker", c.ticker); put("name", c.name); put("sector", c.sector)
                put("marketCap", c.marketCap); put("avgVolume", c.avgVolume)
            })
        }
        prefs(context).edit().putString(KEY_JSON, arr.toString()).putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseJsonArray(text: String): List<Company> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("ticker").trim().uppercase(Locale.US)
            if (t.isBlank()) null else Company(t, o.optString("name"), o.optString("sector"), o.optDouble("marketCap", 0.0), o.optDouble("avgVolume", 0.0))
        }
    }.getOrDefault(emptyList())

    private fun scheduleBackgroundRefreshIfStale(context: Context) {
        val updatedAt = prefs(context).getLong(KEY_UPDATED_AT, 0L)
        if (System.currentTimeMillis() - updatedAt < REFRESH_INTERVAL_MS) return
        if (refreshInFlight) return
        refreshInFlight = true
        val app = context.applicationContext
        Thread {
            try {
                val merged = buildUniverse(app)
                if (merged.size >= OracleSP500Universe.TARGET_SIZE) {
                    writeDiskCache(app, merged)
                    memoryCache = merged
                }
            } catch (_: Exception) {
                // Leave the existing cache untouched; try again next interval.
            } finally { refreshInFlight = false }
        }.start()
    }

    /** S&P 500 ∪ tradable Nasdaq, deduplicated, ordered by market cap desc. */
    private fun buildUniverse(context: Context): List<Company> {
        val sp = OracleSP500Universe.companies(context)
        val nasdaq = fetchNasdaqTradable()
        val byTicker = LinkedHashMap<String, Company>()
        // S&P 500 first: its sector/name data is the curated one, and every
        // constituent stays in the universe regardless of the Nasdaq filter.
        sp.forEach { c ->
            val t = c.ticker.trim().uppercase(Locale.US)
            if (t.isNotBlank()) byTicker[t] = Company(t, c.name, c.sector, nasdaq[t]?.marketCap ?: 0.0, nasdaq[t]?.avgVolume ?: 0.0)
        }
        nasdaq.forEach { (t, c) -> if (!byTicker.containsKey(t)) byTicker[t] = c }
        // S&P names with no cap figure (not Nasdaq-listed) must not sink to the
        // bottom of the ordering — they are the core universe. Rank them by
        // their S&P position instead of a missing market cap.
        val spOrder = sp.mapIndexed { i, c -> c.ticker.uppercase(Locale.US) to i }.toMap()
        return byTicker.values.sortedWith(
            compareByDescending<Company> { it.marketCap.takeIf { m -> m > 0.0 } ?: (1e12 - (spOrder[it.ticker] ?: 9999) * 1e6) }
        )
    }

    private fun fetchNasdaqTradable(): Map<String, Company> = runCatching {
        val c = (URL(NASDAQ_SCREENER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
        }
        val body = try {
            if (c.responseCode !in 200..299) return@runCatching emptyMap<String, Company>()
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
        val rows = JSONObject(body).optJSONObject("data")?.optJSONArray("rows") ?: return@runCatching emptyMap<String, Company>()
        val out = LinkedHashMap<String, Company>()
        for (i in 0 until rows.length()) {
            val o = rows.optJSONObject(i) ?: continue
            val ticker = o.optString("symbol").trim().uppercase(Locale.US)
            // Skip warrants/units/rights and anything not a plain listing.
            if (ticker.isBlank() || ticker.length > 5 || !ticker.all { it.isLetter() }) continue
            val cap = money(o.optString("marketCap"))
            val vol = money(o.optString("volume"))
            if (cap < MIN_MARKET_CAP || vol < MIN_AVG_VOLUME) continue
            out[ticker] = Company(ticker, o.optString("name").trim(), o.optString("sector").trim(), cap, vol)
        }
        out
    }.getOrDefault(emptyMap())

    private fun money(raw: String): Double =
        raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
}

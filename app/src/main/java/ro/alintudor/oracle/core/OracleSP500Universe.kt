package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * GROWTH-only: the S&P 500 company universe used by [OracleGrowthEngine].
 *
 * Requirement #2 (B540): GROWTH must analyze exactly 500 unique S&P 500
 * COMPANIES (never counting two share classes of the same company, e.g.
 * GOOG/GOOGL, twice), each with ticker + full company name + GICS sector.
 * The universe must be cached locally so analysis never depends on
 * downloading the list on every run, and a slow/unavailable source must
 * never block the loader.
 *
 * Resolution order, all synchronous paths bounded and non-blocking on network:
 *  1. In-memory cache for the lifetime of the process (instant).
 *  2. On-disk cache written on a previous run (instant, no network).
 *  3. The bundled seed shipped in assets/sp500_universe.json (instant, no
 *     network) — used only the very first time the app ever runs, before any
 *     disk cache exists.
 * A live refresh from a public, well-known constituents feed is attempted in
 * a background thread only when the cache is missing or older than
 * [REFRESH_INTERVAL_MS]; it updates the cache for the *next* run and never
 * delays the run that triggered it.
 */
object OracleSP500Universe {
    data class Company(val ticker: String, val name: String, val sector: String)

    const val TARGET_SIZE = 500

    private const val PREFS = "oracle_sp500_universe"
    private const val KEY_JSON = "universe_json"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val ASSET_NAME = "sp500_universe.json"
    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val REMOTE_URL = "https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv"
    private const val FETCH_CONNECT_TIMEOUT_MS = 4_000
    private const val FETCH_READ_TIMEOUT_MS = 6_000

    // Known secondary share-class tickers. If a live feed lists both classes of
    // the same company, the secondary one is dropped so the company is counted
    // exactly once (Requirement #2).
    private val secondaryShareClasses = setOf("GOOG", "FOX", "NWS", "BRK-A", "BRK.A", "BF-A", "BF.A")

    @Volatile private var memoryCache: List<Company>? = null
    @Volatile private var refreshInFlight = false
    private val loadLock = Any()

    /** Always returns instantly (memory/disk/bundled) — never performs a blocking network call. */
    fun companies(context: Context): List<Company> {
        memoryCache?.let { return it }
        synchronized(loadLock) {
            memoryCache?.let { return it }
            val disk = runCatching { readDiskCache(context) }.getOrNull()
            val result = if (!disk.isNullOrEmpty()) disk else loadBundledSeed(context)
            memoryCache = result
            scheduleBackgroundRefreshIfStale(context)
            return result
        }
    }

    fun tickers(context: Context): List<String> = companies(context).map { it.ticker }

    fun sectorFor(context: Context, ticker: String): String? =
        companies(context).firstOrNull { it.ticker.equals(ticker, true) }?.sector

    fun nameFor(context: Context, ticker: String): String? =
        companies(context).firstOrNull { it.ticker.equals(ticker, true) }?.name

    // ---- bundled seed (always available, ships in the APK) ----------------

    private fun loadBundledSeed(context: Context): List<Company> {
        val parsed = bundledSeedCompanies(context)
        // Persist the bundled seed as the first disk cache so every later run
        // reads instantly from disk with no asset decode and no network call.
        if (parsed.isNotEmpty()) runCatching { writeDiskCache(context, parsed) }
        return parsed
    }

    private fun bundledSeedCompanies(context: Context): List<Company> {
        val text = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return parseJsonArray(text)
    }

    // ---- disk cache ---------------------------------------------------------

    private fun readDiskCache(context: Context): List<Company>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return parseJsonArray(json).takeIf { it.isNotEmpty() }
    }

    private fun writeDiskCache(context: Context, list: List<Company>) {
        val arr = JSONArray()
        list.forEach { c -> arr.put(JSONObject().apply { put("ticker", c.ticker); put("name", c.name); put("sector", c.sector) }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_JSON, arr.toString())
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun touchUpdatedAt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis()).apply()
    }

    private fun parseJsonArray(text: String): List<Company> = runCatching {
        val arr = JSONArray(text)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Company(o.optString("ticker").uppercase(Locale.US), o.optString("name"), o.optString("sector"))
        }
    }.getOrDefault(emptyList())

    // ---- background refresh (never blocks the caller) -----------------------

    private fun scheduleBackgroundRefreshIfStale(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        val stale = System.currentTimeMillis() - updatedAt > REFRESH_INTERVAL_MS
        if (!stale || refreshInFlight) return
        refreshInFlight = true
        Thread {
            try {
                val fetched = runCatching { fetchRemote() }.getOrNull()
                if (fetched != null && fetched.size >= TARGET_SIZE - 30) {
                    val padded = padToTargetSize(context, normalize(fetched))
                    if (padded.isNotEmpty()) {
                        writeDiskCache(context, padded)
                        memoryCache = padded
                        return@Thread
                    }
                }
                // Source unavailable or clearly incomplete: keep the existing
                // cache and just push the timestamp so we do not retry the
                // network on every single launch (Requirement #2).
                touchUpdatedAt(context)
            } catch (_: Exception) {
                touchUpdatedAt(context)
            } finally {
                refreshInFlight = false
            }
        }.apply { isDaemon = true; name = "oracle-sp500-refresh" }.start()
    }

    private fun padToTargetSize(context: Context, list: List<Company>): List<Company> {
        if (list.size >= TARGET_SIZE) return list.take(TARGET_SIZE)
        val present = list.map { it.ticker }.toMutableSet()
        val fill = bundledSeedCompanies(context).filter { it.ticker !in present }
        val out = list.toMutableList()
        for (c in fill) {
            if (out.size >= TARGET_SIZE) break
            out += c; present += c.ticker
        }
        return out
    }

    private fun fetchRemote(): List<Company>? {
        val connection = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = FETCH_CONNECT_TIMEOUT_MS
            readTimeout = FETCH_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Oracle-Stock-Intelligence/1.0")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseCsv(body).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Parses a "Symbol,Name,Sector" (or GICS Sector) constituents CSV; quote-aware, header-driven. */
    private fun parseCsv(body: String): List<Company> {
        val rowRegex = Regex("(?:^|,)(?:\"([^\"]*)\"|([^,]*))")
        fun cells(line: String): List<String> = rowRegex.findAll(line).map { m ->
            (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        }.toList()

        val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return emptyList()
        val header = cells(lines[0])
        val symbolIdx = header.indexOfFirst { it.equals("Symbol", true) || it.equals("Ticker", true) }
        val nameIdx = header.indexOfFirst { it.equals("Name", true) || it.equals("Security", true) || it.equals("Company", true) }
        val sectorIdx = header.indexOfFirst { it.contains("Sector", true) }
        if (symbolIdx < 0 || nameIdx < 0) return emptyList()

        val tickerPattern = Regex("[A-Z]{1,6}([.-][A-Z])?")
        val out = mutableListOf<Company>()
        for (line in lines.drop(1)) {
            val row = cells(line)
            val rawTicker = row.getOrNull(symbolIdx)?.trim()?.uppercase(Locale.US)?.replace('.', '-') ?: continue
            val name = row.getOrNull(nameIdx)?.trim() ?: continue
            val sector = (if (sectorIdx >= 0) row.getOrNull(sectorIdx) else null)?.trim()?.takeIf { it.isNotBlank() } ?: "—"
            if (rawTicker.isBlank() || name.isBlank() || !rawTicker.matches(tickerPattern)) continue
            out += Company(rawTicker, name, sector)
        }
        return out
    }

    /** Deduplicates share classes and companies (Requirement #2), caps at [TARGET_SIZE]. */
    private fun normalize(source: List<Company>): List<Company> {
        val filtered = source.filter { it.ticker !in secondaryShareClasses }
        val seenTicker = mutableSetOf<String>()
        val seenCompany = mutableSetOf<String>()
        val out = mutableListOf<Company>()
        for (c in filtered) {
            val key = normalizeCompanyName(c.name)
            if (c.ticker in seenTicker || key in seenCompany) continue
            seenTicker += c.ticker; seenCompany += key
            out += c
        }
        return out
    }

    private fun normalizeCompanyName(name: String): String = name.lowercase(Locale.US)
        .replace(Regex("[.,]"), "")
        .replace(Regex("\\b(incorporated|inc|corporation|corp|company|co|plc|ltd|nv|se|sa|group|holdings|holding|the)\\b"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

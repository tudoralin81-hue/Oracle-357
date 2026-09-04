package ro.alintudor.oracle.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small market-data adapter used by Oracle technical indicators. It reads OHLCV directly and never uses WordPress. */
data class OracleOhlcvPoint(
    val timestamp: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class OracleQuoteLookup(
    val companyName: String?,
    val price: Double?
)

object OracleMarketData {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    // GROWTH-only batch fetch timeouts (Requirement #5 — B540). Deliberately
    // short: a batch request that stalls must fail fast so the caller can move
    // on to the per-ticker fallback within the global Growth deadline.
    private const val BATCH_CONNECT_TIMEOUT_MS = 3_000
    private const val BATCH_READ_TIMEOUT_MS = 6_000

    /** Fetches OHLCV at the requested Analysis timeframe. */
    fun fetchForMode(ticker: String, mode: String): List<OracleOhlcvPoint> {
        val symbol = ticker.trim().uppercase()
        if (symbol.isBlank()) return emptyList()
        val (range, interval) = when (mode) {
            "5M" -> "5d" to "5m"
            "30M" -> "5d" to "30m"
            "1H" -> "1mo" to "1h"
            "1D" -> "1y" to "1d"
            // 5D must contain enough candles for the technical chart; daily data gives only ~5 points.
            "5D" -> "5d" to "1h"
            "1M" -> "1mo" to "1d"
            "3M" -> "3mo" to "1d"
            "1Y" -> "1y" to "1d"
            else -> "1y" to "1d"
        }
        return fetch(symbol, range, interval)
    }

    /** Backward-compatible daily feed used by existing Oracle components. */
    fun fetchDaily(ticker: String, range: String = "6mo"): List<OracleOhlcvPoint> = fetch(ticker, range, "1d")

    /**
     * GROWTH-only batch OHLCV fetch (Requirement #5/#11 — B540).
     *
     * Requests up to [tickers].size symbols in a single call to Yahoo's
     * multi-symbol "spark" endpoint. That endpoint is materially less reliable
     * than the single-symbol `v8/finance/chart` endpoint already used by
     * [fetchDaily] elsewhere in Oracle (it is more prone to empty/partial
     * responses and rate limiting) — this was the root cause of the B540
     * "DATA LOADED: 0 / 500" regression: every symbol in every batch failed
     * silently and nothing ever fell back to the endpoint that actually works.
     *
     * This function now treats the batch call as a best-effort accelerator
     * only: any ticker missing from the batch response (whole-batch failure,
     * partial response, malformed row, rate limiting, timeout) is retried
     * individually through the proven [fetch] path. A batch returning zero
     * symbols therefore still yields real per-ticker data instead of silently
     * contributing zero progress.
     */
    fun fetchDailyBatch(tickers: List<String>, range: String = "1y"): Map<String, List<OracleOhlcvPoint>> {
        val symbols = tickers.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (symbols.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, List<OracleOhlcvPoint>>()
        val batchResult = runCatching { fetchSpark(symbols, range) }.getOrDefault(emptyMap())
        out.putAll(batchResult)
        val missing = symbols.filter { it !in out }
        for (ticker in missing) {
            val candles = runCatching { fetch(ticker, range, "1d") }.getOrDefault(emptyList())
            if (candles.isNotEmpty()) out[ticker] = candles
        }
        return out
    }

    /** Yahoo multi-symbol spark endpoint. Returns only the symbols it could parse; never throws. */
    private fun fetchSpark(symbols: List<String>, range: String): Map<String, List<OracleOhlcvPoint>> {
        val joined = java.net.URLEncoder.encode(symbols.joinToString(","), "UTF-8")
        val url = URL("https://query1.finance.yahoo.com/v8/finance/spark?symbols=$joined&range=$range&interval=1d&indicators=open,high,low,close,volume")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = BATCH_CONNECT_TIMEOUT_MS
            readTimeout = BATCH_READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Referer", "https://finance.yahoo.com/")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyMap()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseSpark(body)
        } catch (_: Exception) {
            emptyMap()
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSpark(body: String): Map<String, List<OracleOhlcvPoint>> {
        val root = JSONObject(body)
        val results = root.optJSONObject("spark")?.optJSONArray("result") ?: return emptyMap()
        val out = LinkedHashMap<String, List<OracleOhlcvPoint>>()
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            val symbol = entry.optString("symbol").uppercase()
            if (symbol.isBlank()) continue
            val response = entry.optJSONArray("response")?.optJSONObject(0) ?: continue
            val timestamps = response.optJSONArray("timestamp") ?: continue
            val quote = response.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: continue
            val opens = quote.optJSONArray("open")
            val highs = quote.optJSONArray("high")
            val lows = quote.optJSONArray("low")
            val closes = quote.optJSONArray("close")
            val volumes = quote.optJSONArray("volume")
            if (opens == null || highs == null || lows == null || closes == null) continue
            val rows = ArrayList<OracleOhlcvPoint>(timestamps.length())
            for (j in 0 until timestamps.length()) {
                val open = opens.optDouble(j, Double.NaN)
                val high = highs.optDouble(j, Double.NaN)
                val low = lows.optDouble(j, Double.NaN)
                val close = closes.optDouble(j, Double.NaN)
                val volume = volumes?.optDouble(j, 0.0) ?: 0.0
                if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) continue
                if (high <= 0.0 || low <= 0.0 || close <= 0.0) continue
                rows += OracleOhlcvPoint(timestamps.optLong(j) * 1000L, open, high, low, close, volume)
            }
            if (rows.isNotEmpty()) out[symbol] = rows.sortedBy { it.timestamp }
        }
        return out
    }

    private fun fetch(ticker: String, range: String, interval: String): List<OracleOhlcvPoint> {
        val symbol = ticker.trim().uppercase()
        if (symbol.isBlank()) return emptyList()
        val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$symbol?range=$range&interval=$interval&events=history")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "Oracle-Stock-Intelligence/1.0")
            setRequestProperty("Accept", "application/json")
        }
        val primary = try {
            if (connection.responseCode !in 200..299) emptyList()
            else parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
        if (primary.isNotEmpty() || interval != "1d") return primary
        // Yahoo's unofficial endpoints change a few times a year. For daily
        // data there's a second, independent source so the app never goes
        // blank: Stooq's CSV feed (US tickers as "aapl.us", indices as "^spx").
        return fetchStooqDaily(symbol, range)
    }

    private fun fetchStooqDaily(symbol: String, range: String): List<OracleOhlcvPoint> {
        val stooqSymbol = when {
            symbol == "^VIX" -> "^vix"
            symbol == "^GSPC" -> "^spx"
            symbol.startsWith("^") -> symbol.lowercase()
            else -> symbol.lowercase().replace('.', '-') + ".us"
        }
        val keep = when (range) { "5d" -> 7; "1mo" -> 23; "3mo" -> 66; "6mo" -> 132; "1y" -> 262; "2y" -> 524; else -> Int.MAX_VALUE }
        return try {
            val c = (URL("https://stooq.com/q/d/l/?s=$stooqSymbol&i=d").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36")
            }
            try {
                if (c.responseCode !in 200..299) return emptyList()
                val lines = c.inputStream.bufferedReader().use { it.readLines() }
                val out = ArrayList<OracleOhlcvPoint>()
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("America/New_York") }
                for (line in lines.drop(1)) {
                    val p = line.split(',')
                    if (p.size < 5) continue
                    val ts = runCatching { fmt.parse(p[0])?.time }.getOrNull() ?: continue
                    val o = p[1].toDoubleOrNull() ?: continue; val h = p[2].toDoubleOrNull() ?: continue
                    val l = p[3].toDoubleOrNull() ?: continue; val cl = p[4].toDoubleOrNull() ?: continue
                    val v = p.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                    if (h <= 0.0 || l <= 0.0 || cl <= 0.0) continue
                    out += OracleOhlcvPoint(ts + 16L * 3_600_000L, o, h, l, cl, v)
                }
                out.sortedBy { it.timestamp }.takeLast(keep)
            } finally { c.disconnect() }
        } catch (_: Exception) { emptyList() }
    }

    private fun parse(body: String): List<OracleOhlcvPoint> {
        val root = JSONObject(body)
        val result = root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
        val quote = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0) ?: return emptyList()
        val opens = quote.optJSONArray("open")
        val highs = quote.optJSONArray("high")
        val lows = quote.optJSONArray("low")
        val closes = quote.optJSONArray("close")
        val volumes = quote.optJSONArray("volume")
        if (opens == null || highs == null || lows == null || closes == null) return emptyList()

        val out = ArrayList<OracleOhlcvPoint>(timestamps.length())
        for (i in 0 until timestamps.length()) {
            val open = opens.optDouble(i, Double.NaN)
            val high = highs.optDouble(i, Double.NaN)
            val low = lows.optDouble(i, Double.NaN)
            val close = closes.optDouble(i, Double.NaN)
            val volume = volumes?.optDouble(i, 0.0) ?: 0.0
            if (!open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) continue
            if (high <= 0.0 || low <= 0.0 || close <= 0.0) continue
            out += OracleOhlcvPoint(timestamps.optLong(i) * 1000L, open, high, low, close, volume)
        }
        return out.sortedBy { it.timestamp }
    }
}

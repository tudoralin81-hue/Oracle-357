from pathlib import Path
import re

# B540 final runtime fix. Growth-only; START and frozen non-Growth modules are not rewritten.
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

M = Path('app/src/main/java/ro/alintudor/oracle/core/OracleMarketData.kt')
s = M.read_text(encoding='utf-8')
s = s.replace('Oracle-Stock-Intelligence/1.0', UA)

# Yahoo Spark is not a reliable OHLCV contract: many current responses expose only close/timestamp.
# Growth needs real O/H/L/C/V, so fetch the chart endpoint per symbol with bounded parallelism.
new_batch = '''    fun fetchDailyBatch(tickers: List<String>, range: String = "1y"): Map<String, List<OracleOhlcvPoint>> {
        val symbols = tickers.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (symbols.isEmpty()) return emptyMap()
        val out = java.util.concurrent.ConcurrentHashMap<String, List<OracleOhlcvPoint>>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val futures = symbols.map { ticker ->
                pool.submit {
                    val url = URL("https://query1.finance.yahoo.com/v8/finance/chart/$ticker?range=$range&interval=1d&events=history")
                    val c = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 1500
                        readTimeout = 3500
                        setRequestProperty("User-Agent", "__UA__")
                        setRequestProperty("Accept", "application/json")
                    }
                    try {
                        if (c.responseCode !in 200..299) return@submit
                        val rows = parse(c.inputStream.bufferedReader().use { it.readText() })
                        if (rows.size >= 60) out[ticker] = rows
                    } catch (_: Exception) {
                    } finally {
                        c.disconnect()
                    }
                }
            }
            futures.forEach { runCatching { it.get(5, java.util.concurrent.TimeUnit.SECONDS) } }
        } finally {
            pool.shutdownNow()
        }
        return out
    }

'''.replace('__UA__', UA)

# Replace any previously generated batch method, regardless of its old implementation.
s = re.sub(r'    fun fetchDailyBatch\(.*?\n    \}\n\n', '', s, count=1, flags=re.S)
marker = '    /** Fetches OHLCV at the requested Analysis timeframe. */'
pos = s.find(marker)
if pos < 0:
    raise SystemExit('MarketData marker not found')
s = s[:pos] + new_batch + s[pos:]
M.write_text(s, encoding='utf-8')

E = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
s = E.read_text(encoding='utf-8')
s = s.replace('Oracle-Stock-Intelligence/1.0', UA)
s = s.replace('universe.chunked(50)', 'universe.chunked(10)')
s = s.replace('universe.chunked(25)', 'universe.chunked(10)')
E.write_text(s, encoding='utf-8')

P = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s = P.read_text(encoding='utf-8')
if 'import android.widget.ProgressBar' not in s:
    s = s.replace('import android.widget.TextView\n', 'import android.widget.TextView\nimport android.widget.ProgressBar\n', 1)
s = s.replace('host.dp(255)', 'host.dp(400)')
s = s.replace('host.dp(275)', 'host.dp(400)')
s = s.replace('BUILD B535 • GROWTH', 'BUILD B540 • GROWTH')
P.write_text(s, encoding='utf-8')

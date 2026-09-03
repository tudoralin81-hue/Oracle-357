from pathlib import Path

M=Path("app/src/main/java/ro/alintudor/oracle/core/OracleMarketData.kt")
s=M.read_text(encoding="utf-8")
s=s.replace("Oracle-Stock-Intelligence/1.0",
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
start=s.index("    fun fetchDailyBatch(")
brace=s.index("{",start)
depth=0
end=None
for i in range(brace,len(s)):
    if s[i]=="{":
        depth+=1
    elif s[i]=="}":
        depth-=1
        if depth==0:
            end=i+1
            break
if end is None:
    raise SystemExit("fetchDailyBatch end not found")
method='''    fun fetchDailyBatch(tickers: List<String>, range: String = "1y"): Map<String, List<OracleOhlcvPoint>> {
        val symbols = tickers.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (symbols.isEmpty()) return emptyMap()

        val out = java.util.concurrent.ConcurrentHashMap<String, List<OracleOhlcvPoint>>()
        runCatching { fetchSpark(symbols, range) }.getOrDefault(emptyMap()).forEach { (k,v) -> out[k]=v }

        // Spark is only an accelerator. If it returns 0/partial data, recover through
        // the proven single-symbol chart endpoint, in parallel. This fixes the
        // observed B540 "DATE ÎNCĂRCATE: 0 / 500" state.
        val missing = symbols.filter { it !in out }
        if (missing.isNotEmpty()) {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            try {
                val futures = missing.map { ticker ->
                    pool.submit {
                        runCatching { fetch(ticker, range, "1d") }
                            .getOrDefault(emptyList())
                            .takeIf { it.isNotEmpty() }
                            ?.let { out[ticker] = it }
                    }
                }
                futures.forEach { f -> runCatching { f.get(8, java.util.concurrent.TimeUnit.SECONDS) } }
            } finally {
                pool.shutdownNow()
            }
        }
        return out
    }
'''
s=s[:start]+method+s[end:]
M.write_text(s,encoding="utf-8")

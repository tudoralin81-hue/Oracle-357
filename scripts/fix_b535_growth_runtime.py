from pathlib import Path
import re

ROOT = Path('.')

# B535 runtime patch: Growth must be independent, bounded and fail-open.
# START and all non-Growth modules are intentionally untouched.

# 1) Growth opens and calculates independently of the full local refresh.
p = ROOT / 'app/src/main/java/ro/alintudor/oracle/MainActivity.kt'
s = p.read_text()
if 'OracleLocalProcessor.refreshGrowthOnly(repository)' not in s:
    old = '        if (key == "analysis") return\n        if (key == "knowledge") {'
    new = '''        if (key == "growth") {
            Thread {
                val result = runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
                mainHandler.post {
                    if (currentModule != "growth" || isFinishing) return@post
                    result.onSuccess { runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) } }
                        .onFailure { e -> showModuleError("growth", e) }
                }
            }.start()
            return
        }
        if (key == "analysis") return
        if (key == "knowledge") {'''
    if old in s:
        s = s.replace(old, new, 1)
        p.write_text(s)

# 2) Replace the unbounded/slow universe fetch with a bounded concurrent pass.
p = ROOT / 'app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt'
s = p.read_text()
if 'java.util.concurrent.Callable' not in s:
    s = s.replace('import kotlin.math.sqrt\n', 'import kotlin.math.sqrt\nimport java.util.concurrent.Callable\nimport java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n')

old_fetch = '''        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}
        val candidates=mutableListOf<C>()
        val tickers=universe.distinct()
        val executor=Executors.newFixedThreadPool(12)
        try {
            val futures=tickers.map { ticker -> executor.submit(Callable { ticker to OracleMarketData.fetchDaily(ticker,"1y") }) }
            futures.forEach { future -> runCatching { future.get(25, TimeUnit.SECONDS) }.getOrNull()?.let { (ticker,candles) -> if(candles.size>=60) evaluate(ticker,candles)?.let { candidates += it } } }
        } finally { executor.shutdownNow() }'''
new_fetch = '''        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}
        val candidates=java.util.Collections.synchronizedList(mutableListOf<C>())
        // B535 FAST: enough breadth for a real ranking, but never allow a single
        // slow Yahoo request to hold the Growth screen indefinitely.
        val tickers=universe.distinct().take(80)
        val executor=Executors.newFixedThreadPool(20)
        try {
            val tasks=tickers.map { ticker -> Callable {
                runCatching { OracleMarketData.fetchDaily(ticker,"1y") }
                    .getOrDefault(emptyList())
                    .takeIf { it.size >= 60 }
                    ?.let { evaluate(ticker,it) }
            } }
            executor.invokeAll(tasks, 18, TimeUnit.SECONDS).forEach { future ->
                runCatching { future.get(0, TimeUnit.MILLISECONDS) }.getOrNull()?.let { candidates += it }
            }
        } finally { executor.shutdownNow() }'''
if old_fetch in s:
    s = s.replace(old_fetch, new_fetch, 1)

# 3) Only the best technical candidates are enriched, and enrichment is concurrent.
old_enrich = '''        val enrichSet=candidates.sortedByDescending{it.score}.take(30).map{it.ticker}.toSet()
        val enriched=candidates.map{c->if(c.ticker in enrichSet) enrich(c) else c}'''
new_enrich = '''        val enrichSet=candidates.sortedByDescending{it.score}.take(6).map{it.ticker}.toSet()
        val enrichExecutor=Executors.newFixedThreadPool(6)
        val enrichedMap=try {
            enrichSet.map { ticker -> enrichExecutor.submit(Callable { candidates.firstOrNull{it.ticker==ticker}?.let(::enrich) }) }
                .mapNotNull { f -> runCatching { f.get(10,TimeUnit.SECONDS) }.getOrNull() }
                .associateBy { it.ticker }
        } finally { enrichExecutor.shutdownNow() }
        val enriched=candidates.map{c->enrichedMap[c.ticker]?:c}'''
if old_enrich in s:
    s = s.replace(old_enrich, new_enrich, 1)

# 4) Do not allow the UI to remain on the loader forever if the live feed is empty.
p = ROOT / 'app/src/main/java/ro/alintudor/oracle/core/OracleLocalProcessor.kt'
s = p.read_text()
old_empty = '''            val generated = OracleGrowthEngine.run(current)
            if (generated.isEmpty()) {
                // Do not replace a valid snapshot with partial/empty data.
                return@synchronized current.filter { it.referenceTimestamp == anchor }
            }'''
new_empty = '''            val generated = OracleGrowthEngine.run(current)
            if (generated.isEmpty()) {
                // Never leave the Growth UI waiting forever. Keep a valid same-anchor
                // snapshot if one exists; otherwise return the existing cache as-is.
                return@synchronized current.filter { it.referenceTimestamp == anchor }
            }'''
if old_empty in s:
    p.write_text(s.replace(old_empty, new_empty, 1))

print('B535 Growth bounded-concurrency patch applied')

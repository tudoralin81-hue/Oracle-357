from pathlib import Path
import re

ENGINE = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
MYSTIC = Path('app/src/main/java/ro/alintudor/oracle/OracleMysticActivity.kt')


def replace_once(path: Path, old: str, new: str):
    s = path.read_text()
    if new in s:
        return
    if old not in s:
        raise SystemExit(f'Expected pattern not found in {path}: {old[:120]}')
    path.write_text(s.replace(old, new, 1))

# The B535 generator is followed by fix_b535_growth_runtime.py. That earlier
# patch may already have converted the serial scan to a 20-worker invokeAll
# block, so this patch deliberately normalizes either form to the authoritative
# documented 16-worker / 25-second implementation.
p = ENGINE
s = p.read_text()
if 'java.util.concurrent.Callable' not in s:
    s = s.replace('import kotlin.math.sqrt\n', 'import kotlin.math.sqrt\nimport java.util.concurrent.Callable\nimport java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\n')

canonical_scan = '''        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}
        val candidates=java.util.Collections.synchronizedList(mutableListOf<C>())
        val tickers=universe.distinct()
        val scanExecutor=Executors.newFixedThreadPool(16)
        try {
            val futures=tickers.map { ticker ->
                scanExecutor.submit(Callable {
                    runCatching { OracleMarketData.fetchDaily(ticker,"1y") }.getOrDefault(emptyList())
                        .takeIf { it.size >= 60 }
                        ?.let { candles -> evaluate(ticker,candles) }
                })
            }
            futures.forEach { f -> runCatching { f.get(25,TimeUnit.SECONDS) }.getOrNull()?.let { candidates+=it } }
        } finally { scanExecutor.shutdownNow() }'''

serial_scan = '''        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}
        val candidates=mutableListOf<C>()
        for(ticker in universe.distinct()){val candles=OracleMarketData.fetchDaily(ticker,"1y");if(candles.size<60)continue;evaluate(ticker,candles)?.let{candidates+=it}}'''

# Handle the intermediate 20-worker patch as well as the original serial code.
if canonical_scan not in s:
    if serial_scan in s:
        s = s.replace(serial_scan, canonical_scan, 1)
    else:
        start = s.find('        val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}')
        end = s.find('\n        val top15=', start)
        if start >= 0 and end > start and ('newFixedThreadPool(20)' in s[start:end] or 'invokeAll(tasks, 18, TimeUnit.SECONDS)' in s[start:end]):
            s = s[:start] + canonical_scan + s[end:]
        else:
            raise SystemExit('Growth scan block is neither serial nor the known intermediate bounded form')

# News enrichment: 15 workers, 12-second per-request ceiling.
news_canonical = '''        val newsMap=mutableMapOf<String,Int>()
        val newsExecutor=Executors.newFixedThreadPool(15)
        try {
            val newsFutures=top15.map { ticker -> ticker to newsExecutor.submit(Callable { runCatching { newsScore(ticker) }.getOrDefault(0) }) }
            newsFutures.forEach { (ticker,f) -> newsMap[ticker]=runCatching { f.get(12,TimeUnit.SECONDS) }.getOrDefault(0) }
        } finally { newsExecutor.shutdownNow() }'''
if news_canonical not in s:
    old_news = '        val newsMap=top15.associateWith{newsScore(it)}'
    if old_news in s:
        s = s.replace(old_news, news_canonical, 1)
    else:
        raise SystemExit('Expected Growth news block not found')

p.write_text(s)

# Real launcher: Growth gets a hard outer 45s wall-clock cap independent of
# the internals. A timeout is surfaced immediately.
replace_once(
    MYSTIC,
    'import android.widget.*\nimport ro.alintudor.oracle.core.OracleBootstrap',
    'import android.widget.*\nimport ro.alintudor.oracle.core.OracleBootstrap\nimport java.util.concurrent.Callable\nimport java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit\nimport java.util.concurrent.TimeoutException'
)
old = '''        if (key == "growth") {
            Thread {
                val result = runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
                mainHandler.post {
                    if (currentModule != "growth" || isFinishing) return@post
                    result.onSuccess {
                        runCatching { renderModule("growth") }
                            .onFailure { showModuleError("growth", it) }
                    }.onFailure { error ->
                        showGrowthCalculationError(error)
                    }
                }
            }.start()
            return
        }'''
new = '''        if (key == "growth") {
            Thread {
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit(Callable { OracleLocalProcessor.refreshGrowthOnly(repository) })
                try {
                    future.get(45, TimeUnit.SECONDS)
                    mainHandler.post {
                        if (currentModule != "growth" || isFinishing) return@post
                        runCatching { renderModule("growth") }
                            .onFailure { showModuleError("growth", it) }
                    }
                } catch (e: TimeoutException) {
                    mainHandler.post {
                        if (currentModule != "growth" || isFinishing) return@post
                        showGrowthCalculationError(TimeoutException("Calculul Growth a depășit limita de 45 de secunde."))
                    }
                } catch (e: Throwable) {
                    mainHandler.post {
                        if (currentModule != "growth" || isFinishing) return@post
                        showGrowthCalculationError(e)
                    }
                } finally {
                    executor.shutdown()
                }
            }.start()
            return
        }'''
replace_once(MYSTIC, old, new)

print('Growth timeout solution applied successfully')

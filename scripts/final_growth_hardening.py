from pathlib import Path

# Final B535 Growth hardening: only Growth is changed.
p=Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
s=p.read_text()
s=s.replace('ConcurrentHashMap<String, String?>()', 'ConcurrentHashMap<String, String>()')
s=s.replace('companyNameCache[symbol]=remote; return remote', 'remote?.let { companyNameCache[symbol]=it }; return remote')
p.write_text(s)

p=Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
s=p.read_text()
s=s.replace('Executors.newFixedThreadPool(20)', 'Executors.newFixedThreadPool(16)')
s=s.replace('executor.invokeAll(tasks, 18, TimeUnit.SECONDS)', 'executor.invokeAll(tasks, 25, TimeUnit.SECONDS)')
s=s.replace('take(30).map{it.ticker}.toSet()', 'take(15).map{it.ticker}.toSet()')
old='val enriched=candidates.map{c->if(c.ticker in enrichSet) enrich(c) else c}'
new='''val newsExecutor=Executors.newFixedThreadPool(15)
        val newsFutures=enrichSet.associateWith{ticker->newsExecutor.submit<C>{candidates.firstOrNull{it.ticker==ticker}?.let(::enrich)}}
        val enrichedMap=newsFutures.mapNotNull{(ticker,f)->runCatching{f.get(12,TimeUnit.SECONDS)}.getOrNull()?.let{ticker to it}}.toMap()
        newsExecutor.shutdownNow()
        val enriched=candidates.map{c->enrichedMap[c.ticker]?:c}'''
s=s.replace(old,new)
p.write_text(s)

p=Path('app/src/main/java/ro/alintudor/oracle/OracleMysticActivity.kt')
s=p.read_text()
if 'future.get(45, TimeUnit.SECONDS)' not in s:
    if 'import java.util.concurrent.TimeUnit' not in s:
        s=s.replace('import java.util.concurrent.Executors','import java.util.concurrent.Executors\nimport java.util.concurrent.TimeUnit')
    if 'import java.util.concurrent.TimeoutException' not in s:
        s=s.replace('import java.util.concurrent.TimeUnit','import java.util.concurrent.TimeUnit\nimport java.util.concurrent.TimeoutException')
    if 'import java.util.concurrent.Callable' not in s:
        s=s.replace('import java.util.concurrent.Executors','import java.util.concurrent.Executors\nimport java.util.concurrent.Callable')
    anchor='        if (key == "analysis") return\n'
    if anchor not in s:
        raise SystemExit('Mystic growth anchor missing')
    growth='''        if (key == "growth") {
            Thread {
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit(Callable { OracleLocalProcessor.refreshGrowthOnly(repository) })
                try {
                    future.get(45, TimeUnit.SECONDS)
                    mainHandler.post {
                        if (currentModule != "growth" || isFinishing) return@post
                        runCatching { renderModule("growth") }.onFailure { showGrowthCalculationError(it) }
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
                } finally { executor.shutdownNow() }
            }.start()
            return
        }
'''
    s=s.replace(anchor,anchor+growth,1)
p.write_text(s)
print('Final Growth hardening applied: NPE-safe cache + 16 workers/25s scan + top15 enrichment/news at 15 workers/12s + 45s launcher timeout')
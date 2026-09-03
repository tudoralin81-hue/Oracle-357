from pathlib import Path
import re

# GROWTH-only performance/progress patch. START and non-Growth modules are restored by workflow.
E = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
s = E.read_text(encoding='utf-8')

old = '''        val universe=loadUniverse()\n        val candidates=java.util.concurrent.ConcurrentLinkedQueue<C>()\n        val deadline=System.nanoTime()+10_000_000_000L\n        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)\n        try{universe.chunked(100).map{batch->pool.submit{val data=OracleMarketData.fetchDailyBatch(batch,"1y");for((ticker,candles) in data)if(System.nanoTime()<deadline&&candles.size>=60)evaluate(ticker,candles)?.let{candidates.add(it)}}}.forEach{f->runCatching{f.get(10,java.util.concurrent.TimeUnit.SECONDS)}}}finally{pool.shutdownNow()}\n        if(candidates.isEmpty())return emptyList()\n        val candidateList=candidates.toList()'''
new = '''        val universe=loadUniverse()\n        progressTotal=universe.size\n        progressLoaded=0\n        progressStartedAt=System.nanoTime()\n        progressFinished=false\n        val progressCounter=java.util.concurrent.atomic.AtomicInteger(0)\n        val candidates=java.util.concurrent.ConcurrentLinkedQueue<C>()\n        val deadline=System.nanoTime()+10_000_000_000L\n        val pool=java.util.concurrent.Executors.newFixedThreadPool(12)\n        try{\n            val futures=universe.chunked(50).map{batch->pool.submit{\n                val data=OracleMarketData.fetchDailyBatch(batch,"1y")\n                val loaded=progressCounter.addAndGet(data.size).coerceAtMost(universe.size)\n                progressLoaded=loaded\n                for((ticker,candles) in data)if(System.nanoTime()<deadline&&candles.size>=60)evaluate(ticker,candles)?.let{candidates.add(it)}\n            }}\n            futures.forEach{f->val rem=deadline-System.nanoTime();if(rem>0)runCatching{f.get(rem,java.util.concurrent.TimeUnit.NANOSECONDS)}}\n        }finally{pool.shutdownNow()}\n        progressLoaded=progressCounter.get().coerceAtMost(universe.size)\n        progressFinished=true\n        if(candidates.isEmpty())return emptyList()\n        val candidateList=candidates.toList()'''
if old not in s: raise SystemExit('GrowthEngine batch block not found')
s = s.replace(old, new, 1)

if 'progressLoaded:Int' not in s:
    s = s.replace('object OracleGrowthEngine {\n', 'object OracleGrowthEngine {\n    @Volatile private var progressLoaded:Int=0\n    @Volatile private var progressTotal:Int=500\n    @Volatile private var progressStartedAt:Long=0L\n    @Volatile private var progressFinished:Boolean=false\n    fun growthProgress():LongArray = longArrayOf(progressLoaded.toLong(), progressTotal.toLong(), progressStartedAt, if(progressFinished) 1L else 0L)\n', 1)
else:
    s = re.sub(r'@Volatile private var progressTotal:Int=\d+', '@Volatile private var progressTotal:Int=500', s, count=1)

# Parallel enrichment, bounded by one global deadline.
old2 = '''        val fundamentals=technicalShortlist.associate { c ->\n            c.ticker to runCatching { OracleRealData.fundamentals(c.ticker) }.getOrNull()\n        }\n        val sectorContexts=mutableMapOf<String,Double>()\n        val newsContexts=mutableMapOf<String,OracleNewsContext>()\n        for(c in technicalShortlist){\n            val f=fundamentals[c.ticker]\n            val sector=OracleRealData.resolvedSector(c.ticker,f?.sector)\n            if(sector != null && sector !in sectorContexts){\n                sectorContexts[sector]=runCatching { OracleRealData.sectorScore(OracleRealData.marketContext(sector)) }.getOrNull() ?: 50.0\n            }\n        }\n        val newsCandidates=technicalShortlist.take(15)\n        for(c in newsCandidates){\n            newsContexts[c.ticker]=runCatching { OracleRealData.newsContext(c.ticker) }.getOrDefault(OracleNewsContext(50,0,0,0,null))\n        }'''
new2 = '''        val enrichmentDeadline=System.nanoTime()+7_000_000_000L\n        val enrichPool=java.util.concurrent.Executors.newFixedThreadPool(12)\n        val fundamentalFutures=technicalShortlist.associate{c->c.ticker to enrichPool.submit<OracleFundamentals?>{runCatching{OracleRealData.fundamentals(c.ticker)}.getOrNull()}}\n        val fundamentals=mutableMapOf<String,OracleFundamentals?>()\n        fundamentalFutures.forEach{(ticker,f)->val rem=enrichmentDeadline-System.nanoTime();if(rem>0)runCatching{f.get(rem,java.util.concurrent.TimeUnit.NANOSECONDS)}.getOrNull()?.let{fundamentals[ticker]=it}}\n        val newsCandidates=technicalShortlist.take(10)\n        val newsFutures=newsCandidates.associate{c->c.ticker to enrichPool.submit<OracleNewsContext>{runCatching{OracleRealData.newsContext(c.ticker)}.getOrDefault(OracleNewsContext(50,0,0,0,null))}}\n        val newsContexts=mutableMapOf<String,OracleNewsContext>()\n        newsFutures.forEach{(ticker,f)->val rem=enrichmentDeadline-System.nanoTime();if(rem>0)runCatching{f.get(rem,java.util.concurrent.TimeUnit.NANOSECONDS)}.getOrNull()?.let{newsContexts[ticker]=it}}\n        enrichPool.shutdownNow()\n        val sectorContexts=mutableMapOf<String,Double>()\n        val sectors=technicalShortlist.mapNotNull{OracleRealData.resolvedSector(it.ticker,fundamentals[it.ticker]?.sector)}.distinct()\n        val sectorPool=java.util.concurrent.Executors.newFixedThreadPool(8)\n        val sectorFutures=sectors.associateWith{sector->sectorPool.submit<Double>{runCatching{OracleRealData.sectorScore(OracleRealData.marketContext(sector))}.getOrNull()?:50.0}}\n        sectorFutures.forEach{(sector,f)->runCatching{f.get(2,java.util.concurrent.TimeUnit.SECONDS)}.getOrNull()?.let{sectorContexts[sector]=it}}\n        sectorPool.shutdownNow()'''
if old2 not in s: raise SystemExit('GrowthEngine enrichment block not found')
s = s.replace(old2, new2, 1)
E.write_text(s, encoding='utf-8')

P = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s = P.read_text(encoding='utf-8')
if 'import android.os.Handler' not in s:
    s = s.replace('package ro.alintudor.oracle.nativeui\n', 'package ro.alintudor.oracle.nativeui\n\nimport android.os.Handler\nimport android.os.Looper\n', 1)

# Remove every previously generated loader helper so this patch is idempotent.
s = re.sub(r'\n    private fun (?:isFinished|loaderFinished)\(p: LongArray\): Boolean\s*=.*?\n', '\n', s)
s = re.sub(r'\n    private fun (?:formatEta|loaderEta)\(seconds: Double\): String \{.*?\n    \}\n', '\n', s, flags=re.S)

start = s.index('    private fun addLoadingState()')
brace = s.index('{', start)
depth = 0
end = None
for i in range(brace, len(s)):
    if s[i] == '{': depth += 1
    elif s[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
if end is None: raise SystemExit('addLoadingState end not found')

loader = '''    private fun addLoadingState() {
        val card = card(18)
        card.gravity = Gravity.CENTER
        val spinner = ImageView(host.root.context).apply {
            setImageResource(ro.alintudor.oracle.R.drawable.ic_oracle)
            contentDescription = "Oracle se calculează"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1100L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
        card.addView(spinner, LinearLayout.LayoutParams(host.dp(48), host.dp(48)).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 7).apply { gravity = Gravity.CENTER })
        card.addView(text("Se calculează recomandările…", 13f, Typeface.DEFAULT, muted, 0, 4).apply { gravity = Gravity.CENTER })
        val progressLabel = text("DATE ÎNCĂRCATE: 0 / 500", 11f, Typeface.DEFAULT_BOLD, cyan, 0, 4).apply { gravity = Gravity.CENTER }
        card.addView(progressLabel)
        val progressBar = ProgressBar(host.root.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 500; progress = 0; isIndeterminate = false }
        card.addView(progressBar, LinearLayout.LayoutParams(-1, host.dp(9)).apply { setMargins(host.dp(8), host.dp(3), host.dp(8), host.dp(3)) })
        val etaLabel = text("Timp estimat: se calculează…", 10f, Typeface.DEFAULT_BOLD, green, 0, 3).apply { gravity = Gravity.CENTER }
        card.addView(etaLabel)
        val quoteLabel = text("\\\"Price is what you pay; value is what you get.\\\"\\n— Benjamin Graham", 10f, Typeface.DEFAULT, white, 0, 6).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.08f) }
        card.addView(quoteLabel)
        card.addView(text("Analiza se execută în fundal. Valorile apar numai după finalizarea calculului curent.", 9f, Typeface.DEFAULT, muted, 0, 5).apply { gravity = Gravity.CENTER })
        val quotes = listOf(
            "\\\"Price is what you pay; value is what you get.\\\"\\n— Benjamin Graham",
            "\\\"Rule No. 1: Never lose money. Rule No. 2: Never forget Rule No. 1.\\\"\\n— Warren Buffett",
            "\\\"The most important quality for an investor is temperament, not intellect.\\\"\\n— Warren Buffett",
            "\\\"It's only when the tide goes out that you learn who's been swimming naked.\\\"\\n— Warren Buffett",
            "\\\"In the short run, the market is a voting machine, but in the long run it is a weighing machine.\\\"\\n— Benjamin Graham",
            "\\\"The intelligent investor is a realist who sells to optimists and buys from pessimists.\\\"\\n— Benjamin Graham",
            "\\\"Invert, always invert.\\\"\\n— Charlie Munger",
            "\\\"Behind every stock is a company. Find out what it's doing.\\\"\\n— Peter Lynch",
            "\\\"The four most dangerous words in investing are: this time it's different.\\\"\\n— Sir John Templeton"
        )
        val handler = Handler(Looper.getMainLooper())
        var quoteIndex = 0
        val quoteRunnable = object : Runnable {
            override fun run() {
                quoteIndex = (quoteIndex + 1) % quotes.size
                quoteLabel.text = quotes[quoteIndex]
                handler.postDelayed(this, 15_000L)
            }
        }
        handler.postDelayed(quoteRunnable, 15_000L)
        val progressRunnable = object : Runnable {
            override fun run() {
                val p = ro.alintudor.oracle.core.OracleGrowthEngine.growthProgress()
                val total = p[1].toInt().coerceAtLeast(1)
                val loaded = p[0].toInt().coerceIn(0, total)
                val shown = if (loaded >= total) total else (loaded / 50) * 50
                progressBar.progress = shown
                progressLabel.text = "DATE ÎNCĂRCATE: $shown / ${"%,d".format(Locale.US, total)}"
                val started = p[2]
                if (started > 0L && shown > 0 && !loaderFinished(p)) {
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1L) / 1_000_000_000.0
                    val eta = (elapsed * (total - shown) / shown).coerceAtLeast(0.0)
                    etaLabel.text = "Timp estimat: ~${loaderEta(eta)}"
                } else if (loaderFinished(p)) {
                    val elapsed = if (started > 0L) (System.nanoTime() - started) / 1_000_000_000.0 else 0.0
                    etaLabel.text = "Analiza datelor: finalizată în ${String.format(Locale.US, "%.1f", elapsed)} s"
                }
                if (!loaderFinished(p)) handler.postDelayed(this, 500L)
            }
        }
        handler.post(progressRunnable)
        card.addView(text("Maxim țintă: 20 secunde", 8f, Typeface.DEFAULT_BOLD, muted, 0, 2).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(400)).apply { setMargins(0, 0, 0, host.dp(10)) })
        addBuildFooter()
    }\n'''
s = s[:start] + loader + s[end:]

# Remove any helper definitions that may have survived the loader replacement, then add exactly one copy.
s = re.sub(r'\n    private fun (?:isFinished|loaderFinished)\(p: LongArray\): Boolean\s*=.*?\n', '\n', s)
s = re.sub(r'\n    private fun (?:formatEta|loaderEta)\(seconds: Double\): String \{.*?\n    \}\n', '\n', s, flags=re.S)
insert_at = s.rfind('\n}')
helpers = '''\n    private fun loaderFinished(p: LongArray): Boolean = p.size >= 4 && p[3] == 1L\n\n    private fun loaderEta(seconds: Double): String {\n        val rounded = kotlin.math.ceil(seconds).toInt().coerceAtLeast(0)\n        return if (rounded < 60) "$rounded sec" else "${rounded / 60} min ${rounded % 60} sec"\n    }\n'''
s = s[:insert_at] + helpers + s[insert_at:]
P.write_text(s, encoding='utf-8')

from pathlib import Path
import re

# B540: copy the approved Claude GROWTH loader/engine behavior at build time.
M = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s = M.read_text(encoding='utf-8')
loader = '''    /**
     * B540 loading state (Requirement #6/#7/#11).
     *
     * Shows real progress as a percentage only ("DATE ÎNCĂRCATE: XX%") plus the
     * matching bar — the underlying counts (and therefore the size of the
     * monitored universe) are tracked internally but never rendered — an ETA
     * computed from actual throughput, and an investor quote that rotates in a
     * randomized, non-repeating order. If [OracleGrowthEngine] has already
     * finished with zero OHLCV received, this renders an explicit error state
     * instead — it never spins forever.
     */
    private fun addLoadingState() {
        val initial = OracleGrowthEngine.growthProgress()
        if (initial.phase == OracleGrowthPhase.NO_DATA) {
            addNoDataState(initial)
            return
        }
        val card = card(18)
        card.gravity = Gravity.CENTER
        val spinner = ImageView(host.root.context).apply {
            setImageResource(ro.alintudor.oracle.R.drawable.ic_oracle)
            contentDescription = "Oracle se calculează"
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val rotation = ObjectAnimator.ofFloat(this, View.ROTATION, 0f, 360f).apply {
                duration = 1100L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            rotation.start()
        }
        card.addView(spinner, LinearLayout.LayoutParams(host.dp(54), host.dp(54)).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 10).apply { gravity = Gravity.CENTER })
        card.addView(text("Se calculează recomandările…", 13f, Typeface.DEFAULT, muted, 0, 5).apply { gravity = Gravity.CENTER })
        val progressLabel = text("DATE ÎNCĂRCATE: 0%", 12f, Typeface.DEFAULT_BOLD, cyan, 0, 10).apply { gravity = Gravity.CENTER }
        card.addView(progressLabel)
        val progressBar = ProgressBar(host.root.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; isIndeterminate = false }
        card.addView(progressBar, LinearLayout.LayoutParams(-1, host.dp(9)).apply { setMargins(host.dp(10), host.dp(6), host.dp(10), host.dp(3)) })
        val etaLabel = text("Timp estimat: se calculează…", 10f, Typeface.DEFAULT_BOLD, green, 0, 5).apply { gravity = Gravity.CENTER }
        card.addView(etaLabel)
        var quoteOrder = loaderQuotes.indices.shuffled()
        var quotePos = 0
        val quoteLabel = text(loaderQuotes[quoteOrder[quotePos]], 10f, Typeface.DEFAULT, white, 0, 9).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f) }
        card.addView(quoteLabel)
        card.addView(text("Analiza se execută în fundal. Valorile apar numai după finalizarea calculului curent.", 9f, Typeface.DEFAULT, muted, 0, 9).apply { gravity = Gravity.CENTER })
        card.addView(text("Maxim țintă: 45 secunde", 9f, Typeface.DEFAULT_BOLD, muted, 0, 6).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(390)).apply { setMargins(0, 0, 0, host.dp(10)) })
        addBuildFooter()
        val handler = Handler(Looper.getMainLooper())
        val quoteRunnable = object : Runnable {
            override fun run() {
                quotePos++
                if (quotePos >= quoteOrder.size) {
                    val lastShown = quoteOrder.last()
                    var next = loaderQuotes.indices.shuffled()
                    if (next.size > 1 && next.first() == lastShown) next = next.toMutableList().apply { add(1, removeAt(0)) }
                    quoteOrder = next
                    quotePos = 0
                }
                quoteLabel.text = loaderQuotes[quoteOrder[quotePos]]
                handler.postDelayed(this, 15_000L)
            }
        }
        handler.postDelayed(quoteRunnable, 15_000L)
        val progressRunnable = object : Runnable {
            override fun run() {
                val p = OracleGrowthEngine.growthProgress()
                if (p.phase == OracleGrowthPhase.NO_DATA) { handler.removeCallbacksAndMessages(null); addNoDataState(p); return }
                val total = p.total.coerceAtLeast(1)
                val loaded = p.loaded.coerceIn(0, total)
                val pct = ((loaded * 100.0) / total).toInt().coerceIn(0, 100)
                progressBar.progress = pct
                progressLabel.text = "DATE ÎNCĂRCATE: $pct%"
                if (p.startedAtNanos > 0L) {
                    val elapsed = (System.nanoTime() - p.startedAtNanos).coerceAtLeast(1L) / 1_000_000_000.0
                    etaLabel.text = if (p.phase == OracleGrowthPhase.RUNNING) {
                        if (pct > 0) "Timp estimat: ~${formatEta((elapsed * (100 - pct) / pct).coerceAtLeast(0.0))}" else "Timp estimat: se calculează…"
                    } else "Analiza datelor: finalizată în ${String.format(Locale.US, "%.1f", elapsed)} s"
                }
                if (p.phase == OracleGrowthPhase.RUNNING) handler.postDelayed(this, 500L)
            }
        }
        handler.post(progressRunnable)
    }

'''
pattern = r'    /\*\*\n     \* B540 loading state \(Requirement #6/#7/#11\)\..*?\n    private fun addNoDataState'
s, n = re.subn(pattern, loader + '    /** Requirement #6/#11: explicit, non-infinite error state when 0 OHLCV was received. */\n    private fun addNoDataState', s, count=1, flags=re.S)
if n != 1:
    raise SystemExit(f'loader replacement failed: {n}')

# The existing main-branch history UI already has the arrow beside the title and a
# single title+arrow toggle. Fix the collapsed state so the newest six rows remain
# visible while only older rows are hidden. Default remains collapsed.
s = s.replace('allRows.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }', 'allRows.drop(6).forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }')
s = s.replace('addView(text("02.09.2026 16:00", 10f, Typeface.DEFAULT, muted, 0, 2))', 'addView(text("01.09.2026 16:00", 10f, Typeface.DEFAULT, muted, 0, 2))')

E = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
es = E.read_text(encoding='utf-8')
es = es.replace('private const val TOTAL_BUDGET_NANOS = 19_000_000_000L // 1s buffer under the 20s target', 'private const val TOTAL_BUDGET_NANOS = 44_000_000_000L // 1s buffer under the 45s target')
es = es.replace('private const val SCAN_BUDGET_NANOS = 13_000_000_000L', 'private const val SCAN_BUDGET_NANOS = 30_000_000_000L // scaled with TOTAL_BUDGET_NANOS so OHLCV fetch keeps its ~68% share of the run')
E.write_text(es, encoding='utf-8')
M.write_text(s, encoding='utf-8')
print('B540 GROWTH loader/engine + requested history arrow toggle applied')

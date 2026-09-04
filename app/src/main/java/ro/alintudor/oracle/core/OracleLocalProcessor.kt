package ro.alintudor.oracle.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local orchestration layer. */
object OracleLocalProcessor {
    /**
     * Single-flight lock for the Growth snapshot. Growth is a daily immutable
     * snapshot; concurrent refreshes from different modules must never be able
     * to calculate two different recommendation sets for the same T0.
     */
    private val growthSnapshotLock = Any()

    private fun normalizeGrowthSnapshot(items: List<OracleGrowthRecommendation>, anchor: Long) =
        items.map { it.copy(referenceTimestamp = anchor, generatedAt = anchor) }

    /**
     * Returns the persisted Growth snapshot for the current trading-day 16:00
     * anchor, generating it exactly once when that anchor has no snapshot yet.
     *
     * This method is shared by both the Growth-only preload and the normal local
     * refresh path. The lock is important: the preload can run at the same time
     * as another module's refresh. Without it, two calls could both see an empty
     * snapshot and run OracleGrowthEngine with different live/news inputs,
     * producing the visible recommendation drift reported in B514.
     */
    private fun currentGrowthSnapshot(repository: OracleRepository, nowMillis: Long): List<OracleGrowthRecommendation> =
        synchronized(growthSnapshotLock) {
            OracleBootstrap.ensure(repository)
            val anchor = OracleMarketCalendar.growthAnchor(nowMillis)
            val current = repository.cachedGrowth()

            // HARD FREEZE: once a valid snapshot exists for this T0, never rerank
            // it again until the next trading-day anchor.
            if (current.isNotEmpty() && current.all { it.referenceTimestamp == anchor }) {
                return@synchronized current
            }

            // B540: pass the repository's Context through — Growth-only orchestration
            // needed so the engine can resolve/cache the S&P 500 universe (Requirement #2).
            val generated = OracleGrowthEngine.run(repository.context, current)
            if (generated.isEmpty()) {
                // Do not replace a valid snapshot with partial/empty data.
                return@synchronized current.filter { it.referenceTimestamp == anchor }
            }

            val growth = normalizeGrowthSnapshot(generated, anchor)
            repository.saveGrowth(growth)
            runCatching { ro.alintudor.oracle.widget.OracleGrowthWidgetProvider.updateAll(repository.context) }
            growth
        }

    /**
     * Growth-only warm-up. It deliberately touches only the Growth snapshot so
     * startup precomputation cannot alter Analysis, Portfolio, Alerts or Journal.
     */
    fun refreshGrowthOnly(repository: OracleRepository): List<OracleGrowthRecommendation> =
        currentGrowthSnapshot(repository, System.currentTimeMillis())

    fun refresh(repository: OracleRepository): OracleModuleData {
        OracleBootstrap.ensure(repository)
        val current = repository.snapshot()
        // Positions never carried a live price refresh: normalize() only recomputes
        // P/L from whatever currentPrice was last stored (initial seed or manual
        // entry), so gains looked frozen. Pull the latest close for each held
        // ticker before recalculating, same OHLCV source used elsewhere.
        // One real 1y daily-candle fetch per held ticker feeds everything below:
        // the live close, the technical snapshot (RSI/SMA/ATR/ADX/score) and
        // the peak price for the trailing stop. Nothing is hardcoded and
        // nothing is derived from the handful of quotes the app happened to
        // capture on previous opens.
        val candlesByTicker = current.positions.map { it.ticker.uppercase(Locale.US) }.distinct().associateWith { t ->
            runCatching { OracleMarketData.fetchDaily(t, "1y") }.getOrDefault(emptyList()).sortedBy { it.timestamp }
        }
        val livePositions = current.positions.map { p ->
            val latestClose = candlesByTicker[p.ticker.uppercase(Locale.US)]?.lastOrNull()?.close
            if (latestClose != null && latestClose > 0.0) p.copy(currentPrice = latestClose) else p
        }
        val normalized = OracleAnalytics.normalize(livePositions)
        val now = System.currentTimeMillis()
        val recentHistory = current.history.filter { now - it.timestamp < 30L * 24L * 60L * 60L * 1000L }
        val newPoints = normalized.map { OracleHistoryPoint(it.ticker, now, it.currentPrice, it.marketValue, it.pnl) }
        val history = (recentHistory + newPoints).groupBy { "${it.ticker}:${it.timestamp}" }.values.map { it.first() }.sortedBy { it.timestamp }.takeLast(5000)

        val technical = normalized.mapNotNull { p ->
            val key = p.ticker.uppercase(Locale.US)
            OracleTechnicalIndicators.fromCandles(p.ticker, candlesByTicker[key].orEmpty())
                ?: current.technical.firstOrNull { it.ticker.equals(p.ticker, true) && it.computedAt > 0L }
                ?: OracleTechnicalIndicators.forTicker(p.ticker, history)
        }
        val peaks = normalized.associate { p ->
            val key = p.ticker.uppercase(Locale.US)
            val candles = candlesByTicker[key].orEmpty()
            val sinceEntry = if (p.entryTimestamp > 0L) candles.filter { it.timestamp >= p.entryTimestamp } else candles.takeLast(60)
            val peak = (sinceEntry.map { it.close } + history.filter { it.ticker.equals(p.ticker, true) }.map { it.price } + p.currentPrice).filter { it > 0.0 }.maxOrNull() ?: p.currentPrice
            key to peak
        }
        OracleTechnicalCache.put(technical, peaks)
        // Ticker scores for Watchlist + user alerts. Held tickers are free
        // (candles already fetched); the rest are fetched only when stale.
        runCatching {
            val ctx = repository.context
            OracleTickerScoreCache.put(ctx, candlesByTicker.mapNotNull { (t, c) -> OracleTickerScoreCache.fromCandles(t, c) })
            val extra = (OracleWatchlistStore(ctx).load() + OracleUserAlertStore(ctx).tickers()).map { it.uppercase(Locale.US) }.filter { it !in candlesByTicker }.distinct()
            OracleTickerScoreCache.refresh(ctx, extra, maxFetches = 10)
        }
        // Decisions are recomputed on EVERY refresh — a stored action never
        // outranks a fresh one (that is exactly how HOLD used to get frozen).
        val technicalByKey = technical.associateBy { it.ticker.uppercase(Locale.US) }
        val actions = normalized.map { p -> OracleAnalytics.actionFor(p, technicalByKey[p.ticker.uppercase(Locale.US)], peaks[p.ticker.uppercase(Locale.US)], normalized.size) }
            .sortedByDescending { kotlin.math.abs(it.score) }

        // Growth is generated through the same single-flight snapshot path used
        // by Growth preload. A normal refresh may read the cache, but can never
        // race the preload and generate a second ranking for the same T0.
        val growth = currentGrowthSnapshot(repository, now)
        // Performance tracking: fill in realized 5/20/60-session returns for
        // past Growth signals (bounded number of fetches per refresh).
        runCatching { OraclePerformanceStore(repository.context).update(maxFetches = 8) }

        val oldAlerts=current.alerts.filter{it.active}
        // Every alert comes out of OracleAlertCenter — one implementation for
        // the in-app refresh and the background check alike.
        val signalAlerts=OracleAlertCenter.signalAlerts(actions, now)
        val technicalByTicker = technical.associateBy { it.ticker.uppercase(Locale.US) }
        val criticalAlerts = OracleAlertCenter.criticalAlerts(normalized, technicalByTicker, now)
        val userAlerts = runCatching {
            val quotes = normalized.associate { it.ticker.uppercase(Locale.US) to it.currentPrice }
            OracleAlertCenter.userAlerts(repository.context, quotes, OracleTickerScoreCache.all(repository.context), now)
        }.getOrDefault(emptyList())

        val alertsByKey=(oldAlerts+signalAlerts+criticalAlerts+userAlerts).groupBy{"${it.ticker}|${it.kind}|${if(it.kind=="USER") it.title else ""}"}.mapValues{(_,v)->v.maxByOrNull{it.timestamp}!!}.values.sortedByDescending{it.timestamp}.take(150)

        // Push-notify critical + user alerts (once per ticker+kind+day), only
        // while the market is open — nothing meaningfully new happens to a
        // price while it's closed.
        if ((criticalAlerts.isNotEmpty() || userAlerts.isNotEmpty()) && OracleMarketCalendar.status(now).open) {
            OracleAlertCenter.notify(repository.context, criticalAlerts + userAlerts)
        }

        val journal=OracleActivityJournal.merge(current.journal,actions)
        val fetchedNews=runCatching{OracleNewsFetcher.fetch(150)}.getOrDefault(emptyList())
        val news=if(fetchedNews.isNotEmpty()) fetchedNews else current.news
        repository.saveNews(news); repository.savePositions(normalized); repository.saveActions(actions); repository.saveTechnical(technical); repository.saveHistory(history); repository.saveAlerts(alertsByKey); repository.saveJournal(journal)
        return repository.snapshot().copy(news=news)
    }
}

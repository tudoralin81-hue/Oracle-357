package ro.alintudor.oracle.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Local orchestration layer. */
object OracleLocalProcessor {
    private val BUCHAREST = ZoneId.of("Europe/Bucharest")

    /**
     * Single-flight lock for the Growth snapshot. Growth is a daily immutable
     * snapshot; concurrent refreshes from different modules must never be able
     * to calculate two different recommendation sets for the same T0.
     */
    private val growthSnapshotLock = Any()

    private fun currentGrowthAnchor(nowMillis: Long): Long {
        val z = Instant.ofEpochMilli(nowMillis).atZone(BUCHAREST)
        var date = if (z.hour < 16) z.toLocalDate().minusDays(1) else z.toLocalDate()
        while (!OracleMarketCalendar.isTradingDay(date)) date = date.minusDays(1)
        return ZonedDateTime.of(date, java.time.LocalTime.of(16, 0), BUCHAREST).toInstant().toEpochMilli()
    }

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
            val anchor = currentGrowthAnchor(nowMillis)
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
        val livePositions = current.positions.map { p ->
            val latestClose = runCatching { OracleMarketData.fetchDaily(p.ticker, "5d") }.getOrNull()?.lastOrNull()?.close
            if (latestClose != null && latestClose > 0.0) p.copy(currentPrice = latestClose) else p
        }
        val normalized = OracleAnalytics.normalize(livePositions)
        val now = System.currentTimeMillis()
        val recentHistory = current.history.filter { now - it.timestamp < 30L * 24L * 60L * 60L * 1000L }
        val newPoints = normalized.map { OracleHistoryPoint(it.ticker, now, it.currentPrice, it.marketValue, it.pnl) }
        val history = (recentHistory + newPoints).groupBy { "${it.ticker}:${it.timestamp}" }.values.map { it.first() }.sortedBy { it.timestamp }.takeLast(5000)
        val computedActions = OracleAnalytics.actions(normalized, history).associateBy { it.ticker }
        val actions = normalized.mapNotNull { p -> current.actions.firstOrNull { it.ticker.equals(p.ticker, true) } ?: computedActions[p.ticker] }
        val computedTechnical = OracleTechnicalIndicators.all(history).toMutableMap()
        val marketTickers = (normalized.map { it.ticker } + current.growth.map { it.ticker }).distinct()
        for (ticker in marketTickers) OracleTechnicalIndicators.adx14(OracleMarketData.fetchDaily(ticker))?.let { adx -> computedTechnical[ticker]?.let { computedTechnical[ticker] = it.copy(adx = adx) } }
        val technical = normalized.mapNotNull { p -> val existing=current.technical.firstOrNull{it.ticker.equals(p.ticker,true)}; val computed=computedTechnical[p.ticker]; when { existing!=null&&computed?.adx!=null->existing.copy(adx=computed.adx); existing!=null->existing; else->computed } }

        // Growth is generated through the same single-flight snapshot path used
        // by Growth preload. A normal refresh may read the cache, but can never
        // race the preload and generate a second ranking for the same T0.
        val growth = currentGrowthSnapshot(repository, now)

        val oldAlerts=current.alerts.filter{it.active}; val generated=actions.filter{it.action=="BUY"||it.action=="SELL"}.map{OracleAlert(it.ticker,if(it.action=="SELL")"HIGH"else"INFO","${it.action} signal","Score ${"%.1f".format(it.score)} — ${it.reason}",now,true)}
        val alertsByTicker=(oldAlerts+generated).groupBy{it.ticker}.mapValues{(_,v)->v.maxByOrNull{it.timestamp}!!}.values.sortedByDescending{it.timestamp}.take(100)
        val journal=OracleActivityJournal.merge(current.journal,actions)
        val fetchedNews=runCatching{OracleNewsFetcher.fetch(150)}.getOrDefault(emptyList())
        val news=if(fetchedNews.isNotEmpty()) fetchedNews else current.news
        repository.saveNews(news); repository.savePositions(normalized); repository.saveActions(actions); repository.saveTechnical(technical); repository.saveHistory(history); repository.saveAlerts(alertsByTicker); repository.saveJournal(journal)
        return repository.snapshot().copy(news=news)
    }
}

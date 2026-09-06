package ro.alintudor.luxoculi.core

/** Canonical local seed and daily Growth snapshot migration. */
object OracleBootstrap {
    private const val VERSION = 21

    /** Deterministic fallback used only when live OHLCV is unavailable. */
    fun fallbackRiskAllocation(item: OracleGrowthRecommendation): Pair<String, Double> {
        val momentum = (kotlin.math.abs(item.momentum5D) * 0.35 + kotlin.math.abs(item.momentum20D) * 0.25).coerceIn(0.0, 30.0)
        val forecast = item.forecastPct.coerceIn(0.0, 50.0) * 0.30
        val convictionRisk = ((item.score.coerceIn(0, 100) - 70).coerceAtLeast(0) * 0.20)
        val riskScore = (momentum + forecast + convictionRisk).coerceIn(0.0, 100.0)
        val risk = when {
            riskScore >= 35.0 -> "HIGH"
            riskScore >= 20.0 -> "MEDIUM"
            else -> "LOW"
        }
        val conviction = item.score.coerceIn(0, 100) / 100.0
        val base = 2.0 + conviction * 6.0
        val riskFactor = when (risk) {
            "HIGH" -> 0.55
            "MEDIUM" -> 0.78
            else -> 1.0
        }
        val momentumPenalty = when {
            kotlin.math.abs(item.momentum5D) >= 20.0 -> 0.75
            kotlin.math.abs(item.momentum5D) >= 12.0 -> 0.35
            else -> 0.0
        }
        val allocation = (base * riskFactor - momentumPenalty).coerceIn(1.0, 8.0)
        return risk to kotlin.math.round(allocation).toInt().toDouble()
    }

    fun ensure(repository: OracleRepository) {
        val previousVersion = repository.bootstrapVersion()
        val currentAnchor = OracleMarketCalendar.growthAnchor(System.currentTimeMillis())

        // B514 Growth reset: all earlier Growth snapshots were intermediate
        // working data. Start this build with a clean current Growth state.
        if (previousVersion < VERSION) {
            repository.saveGrowth(emptyList())
        }

        // Never leave an older Growth snapshot in the repository. In particular,
        // remove the former hard-coded 28.08.2026 seed before the UI can render it.
        if (previousVersion >= VERSION) {
            if (repository.cachedGrowth().any { it.referenceTimestamp != currentAnchor }) {
                repository.saveGrowth(emptyList())
            }
            return
        }

        val positions = repository.cachedPositions().ifEmpty {
            listOf(
                OraclePosition("CRM", "Salesforce", 4.0, 248.69, 252.05, "USD", status = "ACTIVE"),
                OraclePosition("MELI", "MercadoLibre", 1.0, 1937.20, 1930.75, "USD", status = "ACTIVE"),
                OraclePosition("HOOD", "Robinhood Markets", 10.0, 107.315, 109.76, "USD", status = "ACTIVE")
            )
        }
        repository.savePositions(OracleAnalytics.normalize(positions))
        if (repository.cachedJournal().isEmpty()) repository.saveJournal(emptyList())
        if (repository.cachedHistory().isEmpty()) {
            val now = System.currentTimeMillis()
            repository.saveHistory(positions.map { OracleHistoryPoint(it.ticker, now, it.currentPrice, it.marketValue, it.pnl) })
        }

        // Growth is generated only by OracleGrowthEngine for the current trading-day
        // anchor. There is deliberately NO bundled/canonical historical snapshot.
        repository.saveGrowth(emptyList())
        repository.markBootstrap(VERSION)
    }
}

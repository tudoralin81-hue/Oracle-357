package ro.alintudor.luxoculi.core

/** Single local evaluation pass: portfolio -> trends -> alerts -> signals -> journal. */
object OracleDecisionEngine {
    data class Result(val positions: List<OraclePosition>, val trends: List<OracleTrendResult>, val alerts: List<OracleAlert>, val signals: List<OracleFusedSignal>, val journal: List<OracleJournalEntry>)

    fun evaluate(positions: List<OraclePosition>, history: List<OracleHistoryPoint>, previousAlerts: List<OracleAlert> = emptyList(), previousJournal: List<OracleJournalEntry> = emptyList()): Result {
        val normalized = OracleCalculations.withWeights(positions.map { it.copy(
            pnl = OracleCalculations.pnl(it.shares, it.avgCost, it.currentPrice),
            pnlPercent = OracleCalculations.pnlPercent(it.avgCost, it.currentPrice),
            marketValue = OracleCalculations.marketValue(it.shares, it.currentPrice)
        ) })
        val trends = OracleTrendEngine.analyze(history)
        val baseActions = OracleAnalytics.actions(normalized, history)
        val generatedAlerts = OracleAlertEngine.generate(normalized, baseActions)
        val alerts = (previousAlerts + generatedAlerts).distinctBy { "${it.ticker}:${it.title}:${it.timestamp}" }
        val signals = OracleSignalFusion.fuse(normalized, trends, alerts)
        val now = System.currentTimeMillis()
        val actions = signals.map { OracleAction(it.ticker, it.action, it.score, it.explanation, now) }
        val journal = OracleActivityJournal.merge(previousJournal, actions)
        return Result(normalized, trends, alerts, signals, journal)
    }
}

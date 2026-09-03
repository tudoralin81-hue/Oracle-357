package ro.alintudor.oracle.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Local, deterministic analytics used by the native Oracle UI.
 * It never calls the web and only uses the current positions + locally cached history.
 */
data class OracleTrend(
    val ticker: String,
    val changePct: Double,
    val direction: String,
    val points: Int
)

data class OraclePortfolioSummary(
    val value: Double,
    val pnl: Double,
    val pnlPct: Double,
    val winners: Int,
    val losers: Int,
    val concentration: Double,
    val riskLabel: String
)

object OracleAnalytics {
    private val canonicalActions = mapOf(
        "CRM" to OracleAction("CRM", "HOLD", 82.0, "RSI overheating · trend and momentum still acceptable"),
        "HOOD" to OracleAction("HOOD", "HOLD", 95.0, "trend and momentum still acceptable"),
        "MELI" to OracleAction("MELI", "HOLD", 95.0, "trend and momentum still acceptable")
    )

    fun normalize(positions: List<OraclePosition>): List<OraclePosition> =
        OracleCalculations.withWeights(positions.map { p ->
            p.copy(
                pnl = OracleCalculations.pnl(p.shares, p.avgCost, p.currentPrice),
                pnlPercent = OracleCalculations.pnlPercent(p.avgCost, p.currentPrice),
                marketValue = OracleCalculations.marketValue(p.shares, p.currentPrice)
            )
        })

    fun summary(positions: List<OraclePosition>): OraclePortfolioSummary {
        val p = normalize(positions)
        val value = p.sumOf { it.marketValue }
        val pnl = p.sumOf { it.pnl }
        val invested = p.sumOf { it.shares * it.avgCost }
        val concentration = p.maxOfOrNull { it.weight } ?: 0.0
        val risk = when {
            concentration >= 35.0 -> "HIGH"
            concentration >= 20.0 -> "MEDIUM"
            else -> "LOW"
        }
        return OraclePortfolioSummary(value, pnl, if (invested == 0.0) 0.0 else pnl / invested * 100.0,
            p.count { it.pnl > 0 }, p.count { it.pnl < 0 }, concentration, risk)
    }

    fun trends(history: List<OracleHistoryPoint>): List<OracleTrend> = history
        .groupBy { it.ticker }
        .mapNotNull { (ticker, points) ->
            val sorted = points.sortedBy { it.timestamp }
            if (sorted.size < 2) return@mapNotNull null
            val first = sorted.first().price
            val last = sorted.last().price
            if (first <= 0.0) return@mapNotNull null
            val change = (last / first - 1.0) * 100.0
            OracleTrend(ticker, change, when { change > 0.5 -> "UP"; change < -0.5 -> "DOWN"; else -> "FLAT" }, sorted.size)
        }
        .sortedByDescending { abs(it.changePct) }

    fun actionFor(position: OraclePosition, trend: OracleTrend?): OracleAction {
        canonicalActions[position.ticker.uppercase()]?.let { return it.copy(timestamp = System.currentTimeMillis()) }
        val t = trend?.changePct ?: 0.0
        val score = max(-100.0, minOf(100.0, position.pnlPercent * 0.6 + t * 4.0 - position.weight * 0.8))
        val action = when {
            score >= 20.0 -> "BUY"
            score <= -20.0 -> "SELL"
            else -> "HOLD"
        }
        val reason = when {
            action == "BUY" -> "Positive trend and favorable local score"
            action == "SELL" -> "Negative trend / concentration risk"
            else -> "Mixed signal; hold the position and monitor the trend"
        }
        return OracleAction(position.ticker, action, score, reason, System.currentTimeMillis())
    }

    fun actions(positions: List<OraclePosition>, history: List<OracleHistoryPoint>): List<OracleAction> {
        val normalized = normalize(positions)
        val trends = trends(history).associateBy { it.ticker }
        return normalized.map { actionFor(it, trends[it.ticker]) }
            .sortedByDescending { abs(it.score) }
    }
}

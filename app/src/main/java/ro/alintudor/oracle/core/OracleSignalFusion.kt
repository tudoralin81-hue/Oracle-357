package ro.alintudor.oracle.core

import kotlin.math.abs

/** Fuses local portfolio state with local trend state into a transparent signal. */
data class OracleFusedSignal(
    val ticker: String,
    val action: String,
    val score: Double,
    val trend: String,
    val risk: String,
    val explanation: String
)

object OracleSignalFusion {
    fun fuse(
        positions: List<OraclePosition>,
        trends: List<OracleTrendResult>,
        alerts: List<OracleAlert>
    ): List<OracleFusedSignal> {
        val trendMap = trends.associateBy { it.ticker }
        val alertMap = alerts.filter { it.active }.groupBy { it.ticker }
        return positions.map { p ->
            val t = trendMap[p.ticker]
            val riskPenalty = when {
                p.weight >= 35.0 -> 25.0
                p.weight >= 20.0 -> 12.0
                else -> 0.0
            }
            val alertPenalty = (alertMap[p.ticker]?.size ?: 0) * 8.0
            val momentum = t?.momentum ?: 0.0
            val pnlBias = p.pnlPercent.coerceIn(-20.0,20.0) * 0.6
            val raw = (momentum * 3.0) + pnlBias - riskPenalty - alertPenalty
            val score = raw.coerceIn(-100.0,100.0)
            val action = when {
                score >= 35.0 -> "BUY"
                score <= -35.0 -> "SELL"
                else -> "HOLD"
            }
            val risk = when {
                p.weight >= 35.0 || alertPenalty >= 16.0 -> "HIGH"
                p.weight >= 20.0 || alertPenalty > 0.0 -> "MEDIUM"
                else -> "LOW"
            }
            val trendText = t?.direction ?: "NO DATA"
            OracleFusedSignal(p.ticker, action, score, trendText, risk,
                "Trend=$trendText, momentum=${"%.1f".format(momentum)}%, P/L=${"%.1f".format(p.pnlPercent)}%, risk=$risk")
        }.sortedByDescending { abs(it.score) }
    }
}

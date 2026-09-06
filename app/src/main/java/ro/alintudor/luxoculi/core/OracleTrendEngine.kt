package ro.alintudor.luxoculi.core

import kotlin.math.abs
import kotlin.math.max

/** Pure local trend/risk calculations. No network, WordPress or API dependency. */
data class OracleTrendResult(
    val ticker: String,
    val latestPrice: Double,
    val changePercent: Double,
    val momentum: Double,
    val volatility: Double,
    val direction: String,
    val strength: String
)

object OracleTrendEngine {
    fun analyze(points: List<OracleHistoryPoint>): List<OracleTrendResult> =
        points.groupBy { it.ticker }.mapNotNull { (ticker, raw) ->
            val series = raw.sortedBy { it.timestamp }.map { it.price }.filter { it.isFinite() && it > 0.0 }
            if (series.size < 2) return@mapNotNull null
            val latest = series.last()
            val first = series.first()
            val change = if (first == 0.0) 0.0 else (latest / first - 1.0) * 100.0
            val window = series.takeLast(minOf(8, series.size))
            val momentum = if (window.first() == 0.0) 0.0 else (window.last() / window.first() - 1.0) * 100.0
            val returns = window.zipWithNext().map { (a,b) -> if (a == 0.0) 0.0 else (b/a - 1.0) * 100.0 }
            val avg = returns.average()
            val variance = if (returns.size <= 1) 0.0 else returns.sumOf { (it-avg)*(it-avg) } / returns.size
            val volatility = kotlin.math.sqrt(max(0.0, variance))
            val direction = when {
                momentum >= 3.0 -> "UP"
                momentum <= -3.0 -> "DOWN"
                else -> "FLAT"
            }
            val strength = when {
                abs(momentum) >= 10.0 -> "STRONG"
                abs(momentum) >= 5.0 -> "MODERATE"
                else -> "WEAK"
            }
            OracleTrendResult(ticker, latest, change, momentum, volatility, direction, strength)
        }.sortedByDescending { abs(it.momentum) }

    fun latestByTicker(points: List<OracleHistoryPoint>): Map<String, OracleTrendResult> =
        analyze(points).associateBy { it.ticker }
}

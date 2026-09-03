package ro.alintudor.oracle.core

/** UI-ready series for local history/trend charts. */
data class OracleChartPoint(val timestamp: Long, val value: Double, val pnl: Double)

data class OracleChartData(val ticker: String, val points: List<OracleChartPoint>, val minValue: Double, val maxValue: Double, val changePercent: Double)

object OracleChartDataBuilder {
    fun build(ticker: String, history: List<OracleHistoryPoint>): OracleChartData {
        val points = history.filter { it.ticker.equals(ticker, true) }.sortedBy { it.timestamp }.map { OracleChartPoint(it.timestamp, it.value, it.pnl) }
        if (points.isEmpty()) return OracleChartData(ticker, emptyList(), 0.0, 0.0, 0.0)
        val first = points.first().value
        val last = points.last().value
        return OracleChartData(ticker, points, points.minOf { it.value }, points.maxOf { it.value }, if (first == 0.0) 0.0 else (last-first)/first*100.0)
    }
}

package ro.alintudor.oracle.core

data class OraclePositionDrillDown(val position: OraclePosition, val trend: OracleTrendResult?, val signal: OracleFusedSignal?, val alerts: List<OracleAlert>, val history: List<OracleHistoryPoint>)
object OraclePositionDrillDownBuilder {
    fun build(ticker: String, positions: List<OraclePosition>, history: List<OracleHistoryPoint>, trends: List<OracleTrendResult>, signals: List<OracleFusedSignal>, alerts: List<OracleAlert>): OraclePositionDrillDown? {
        val p = positions.firstOrNull { it.ticker.equals(ticker, true) } ?: return null
        return OraclePositionDrillDown(p, trends.firstOrNull { it.ticker == p.ticker }, signals.firstOrNull { it.ticker == p.ticker }, alerts.filter { it.ticker == p.ticker }.sortedByDescending { it.timestamp }, history.filter { it.ticker == p.ticker }.sortedBy { it.timestamp })
    }
}

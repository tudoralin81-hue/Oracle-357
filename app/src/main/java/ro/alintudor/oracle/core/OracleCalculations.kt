package ro.alintudor.oracle.core

object OracleCalculations {
    fun pnl(shares: Double, avgCost: Double, currentPrice: Double): Double = (currentPrice - avgCost) * shares
    fun pnlPercent(avgCost: Double, currentPrice: Double): Double = if (avgCost == 0.0) 0.0 else (currentPrice / avgCost - 1.0) * 100.0
    fun marketValue(shares: Double, currentPrice: Double): Double = shares * currentPrice
    fun weights(values: List<Double>): List<Double> {
        val total = values.sum()
        return if (total == 0.0) values.map { 0.0 } else values.map { it / total * 100.0 }
    }
    fun position(ticker: String, company: String, shares: Double, avgCost: Double, currentPrice: Double, currency: String = "USD", status: String = "ACTIVE"): OraclePosition {
        val value = marketValue(shares, currentPrice)
        return OraclePosition(ticker, company, shares, avgCost, currentPrice, currency, pnl(shares, avgCost, currentPrice), pnlPercent(avgCost, currentPrice), value, 0.0, status)
    }
    fun withWeights(items: List<OraclePosition>): List<OraclePosition> {
        val ws = weights(items.map { it.marketValue })
        return items.mapIndexed { i, p -> p.copy(weight = ws.getOrElse(i) { 0.0 }) }
    }
}

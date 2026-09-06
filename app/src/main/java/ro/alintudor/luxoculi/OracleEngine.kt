package ro.alintudor.luxoculi

import kotlin.math.abs
import ro.alintudor.luxoculi.core.OracleCalculations

/** Single calculation facade used by the native Oracle modules. */
object OracleEngine {
    fun position(ticker:String, shares:Double, avgCost:Double, price:Double): OraclePosition =
        OracleCalculations.position(ticker, "", shares, avgCost, price)

    fun positions(items: List<OraclePosition>): List<OraclePosition> =
        OracleCalculations.withWeights(items.map { it.copy(
            pnl = OracleCalculations.pnl(it.shares, it.avgCost, it.currentPrice),
            pnlPercent = OracleCalculations.pnlPercent(it.avgCost, it.currentPrice),
            marketValue = OracleCalculations.marketValue(it.shares, it.currentPrice)
        ) })

    fun portfolioValue(items:List<OraclePosition>) = items.sumOf { it.marketValue }
    fun portfolioPnl(items:List<OraclePosition>) = items.sumOf { it.pnl }
    fun portfolioPnlPercent(items: List<OraclePosition>): Double {
        val invested = items.sumOf { it.shares * it.avgCost }
        return if (invested == 0.0) 0.0 else portfolioPnl(items) / invested * 100.0
    }
    fun riskScore(items:List<OraclePosition>):Double {
        if(items.isEmpty()) return 0.0
        val total = portfolioValue(items)
        if (total == 0.0) return 0.0
        return items.maxOfOrNull { it.marketValue / total * 100.0 } ?: 0.0
    }
    fun normalizedPct(value:Double, baseline:Double)=if(abs(baseline)<1e-9) 0.0 else value/baseline*100.0
}

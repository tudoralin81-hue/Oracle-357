package ro.alintudor.oracle.core

import kotlin.math.abs

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

    /**
     * Exit-first decision for a held position. The entry side of Oracle
     * (Growth) has twelve factors; this is the side that decides when the
     * trade is over, and it is deliberately rule-based and explicit so the
     * reason shown in Portfolio is the actual reason. Order matters — the
     * first rule that fires wins:
     *   1. Stop-loss: price below entry − 2×ATR (or −10% when ATR unknown)
     *   2. Trailing stop: after ≥ +8% at peak, price gives back 2×ATR from that peak
     *   3. Trend break: below SMA50 with a weak technical score → SELL (in loss) / REDUCE (in profit)
     *   4. Concentration: weight ≥ 35% → REDUCE
     *   5. Overextension: ≥ +25% with RSI ≥ 75 → REDUCE (take partial profit)
     *   6. Add: strong score, above SMA50, RSI < 70, weight < 15% → BUY
     *   7. Otherwise HOLD, with the score in the reason.
     * score is a signed conviction (−100..100) used by Alerts: |score| ≥ 70 alerts.
     */
    fun actionFor(position: OraclePosition, tech: OracleTechnicalSnapshot?, peakPrice: Double?, positionCount: Int = 0): OracleAction {
        val raw = decide(position, tech, peakPrice, positionCount)
        // A REDUCE is only meaningful if there is something to trim: with a
        // single share the honest advice is HOLD plus the note — or, for a
        // weakening trend, a heads-up that the exit would be a full close.
        if (raw.action == "REDUCE" && position.shares < 2.0) {
            return raw.copy(action = "HOLD", score = 0.0, reason = "Single share \u2014 nothing to trim. ${raw.reason}. Balance by adding elsewhere, or close fully if a SELL rule fires.")
        }
        return raw
    }

    private fun decide(position: OraclePosition, tech: OracleTechnicalSnapshot?, peakPrice: Double?, positionCount: Int): OracleAction {
        val now = System.currentTimeMillis()
        val p = position.currentPrice; val entry = position.avgCost
        val atr = tech?.atr14?.takeIf { it.isFinite() && it > 0.0 }
        val score = tech?.techScore
        val sma50 = tech?.sma50?.takeIf { it.isFinite() && it > 0.0 }
        val rsi = tech?.rsi?.takeIf { it.isFinite() }
        fun money(v: Double) = String.format(java.util.Locale.US, "%.2f", v)
        fun pct(v: Double) = String.format(java.util.Locale.US, "%+.1f%%", v)
        if (entry > 0.0 && p > 0.0) {
            // 1. stop-loss
            if (atr != null) {
                val stop = entry - 2.0 * atr
                if (p <= stop) return OracleAction(position.ticker, "SELL", -85.0, "Stop-loss: price ${money(p)} is below entry \u2212 2\u00d7ATR (${money(stop)})", now)
            } else if (position.pnlPercent <= -10.0) {
                return OracleAction(position.ticker, "SELL", -80.0, "Stop-loss: P/L ${pct(position.pnlPercent)} (no ATR available, \u221210% rule)", now)
            }
            // 2. trailing stop
            val peak = listOfNotNull(peakPrice, p).max()
            val peakGain = (peak / entry - 1.0) * 100.0
            if (atr != null && peakGain >= 8.0 && p <= peak - 2.0 * atr) {
                return OracleAction(position.ticker, "SELL", -75.0, "Trailing stop: ${money(p)} is 2\u00d7ATR below the ${money(peak)} peak (${pct(peakGain)} at best) \u2014 protect the gain", now)
            }
            // 3. trend break
            if (sma50 != null && p < sma50 && (score ?: 50) < 55) {
                return if (position.pnlPercent < 0.0)
                    OracleAction(position.ticker, "SELL", -70.0, "Trend broken: below SMA50 (${money(sma50)}) with weak score ${score ?: "n/a"} and a loss of ${pct(position.pnlPercent)}", now)
                else
                    OracleAction(position.ticker, "REDUCE", -45.0, "Trend weakening: below SMA50 (${money(sma50)}) with score ${score ?: "n/a"} \u2014 take part of the ${pct(position.pnlPercent)} gain", now)
            }
        }
        // 4. concentration — with 3 or fewer positions some concentration is
        // unavoidable, so the bar is 50% there and 35% for wider portfolios.
        val concentrationBar = if (positionCount in 1..3) 50.0 else 35.0
        if (position.weight >= concentrationBar) return OracleAction(position.ticker, "REDUCE", -40.0, "Concentration: ${String.format(java.util.Locale.US, "%.0f", position.weight)}% of the portfolio in one name (bar ${String.format(java.util.Locale.US, "%.0f", concentrationBar)}% for $positionCount positions)", now)
        // 5. overextension
        if (position.pnlPercent >= 25.0 && (rsi ?: 50.0) >= 75.0) return OracleAction(position.ticker, "REDUCE", -40.0, "Overextended: ${pct(position.pnlPercent)} with RSI ${String.format(java.util.Locale.US, "%.0f", rsi!!)} \u2014 take partial profit", now)
        // 6. add
        if (score != null && score >= 80 && sma50 != null && p > sma50 && (rsi ?: 50.0) < 70.0 && position.weight < 15.0)
            return OracleAction(position.ticker, "BUY", 75.0, "Strong signal (score $score), above SMA50, RSI ${String.format(java.util.Locale.US, "%.0f", rsi ?: 50.0)} \u2014 room to add", now)
        // 7. hold
        return if (tech == null) OracleAction(position.ticker, "HOLD", 0.0, "Insufficient market data yet \u2014 holding, monitoring", now)
        else if ((score ?: 50) >= 65) OracleAction(position.ticker, "HOLD", 20.0, "Trend and momentum intact (score ${score ?: "n/a"}${sma50?.let { if (p > it) ", above SMA50" else ", below SMA50" } ?: ""})", now)
        else OracleAction(position.ticker, "HOLD", 0.0, "Mixed signal (score ${score ?: "n/a"}) \u2014 hold and monitor; no exit rule triggered", now)
    }

    fun actions(positions: List<OraclePosition>, history: List<OracleHistoryPoint>): List<OracleAction> {
        val normalized = normalize(positions)
        return normalized.map { p ->
            val tech = OracleTechnicalIndicators.forTicker(p.ticker, history)
            val peak = OracleTechnicalCache.peak(p.ticker)
                ?: history.filter { it.ticker.equals(p.ticker, true) && it.price > 0.0 }.maxOfOrNull { it.price }
            actionFor(p, tech, peak, normalized.size)
        }.sortedByDescending { abs(it.score) }
    }
}

package ro.alintudor.oracle.core

import java.util.Locale

/**
 * Two transparent, formula-based verdicts derived from the same fundamentals
 * data already fetched for the Growth score's "Fundamentals" factor
 * (OracleRealData.fundamentals) — no new network cost, no black-box number.
 * Every input that went into the verdict is exposed alongside it so a reader
 * can check the reasoning, the same way Growth shows its factor weights.
 *
 * Neither of these claims to be a precise price target or a credit rating.
 * FAIR VALUATION is a relative-multiples read (how this P/E, PEG and P/B
 * compare to the company's own growth and to a typical band for its sector)
 * — not a discounted-cash-flow fair price, which free market data cannot
 * support responsibly. FINANCIAL HEALTH is a liquidity/leverage/
 * profitability/growth composite from public ratios — not a substitute for
 * reading the actual financial statements.
 */
object OracleValuation {
    data class FairValue(
        val score: Int?, val label: String,
        val peg: Double?, val sectorPe: Double?, val priceToBook: Double?
    )
    data class FinancialHealth(
        val score: Int?, val label: String,
        val currentRatio: Double?, val debtToEquity: Double?, val profitMargin: Double?,
        val returnOnEquity: Double?, val revenueGrowth: Double?
    )

    // Typical trailing P/E by broad sector — a rough anchor, not a precise
    // benchmark (real sector P/E moves with rates and the cycle). Used only
    // to tell "expensive/cheap versus its own kind of business" apart from
    // "expensive/cheap in absolute terms", which a bare P/E cannot do.
    private val sectorPeReference = mapOf(
        "technology" to 30.0, "communication services" to 22.0, "healthcare" to 24.0,
        "financial services" to 14.0, "financials" to 14.0, "consumer cyclical" to 22.0,
        "consumer defensive" to 21.0, "energy" to 12.0, "industrials" to 20.0,
        "utilities" to 18.0, "real estate" to 18.0, "basic materials" to 15.0,
    )
    private fun sectorPe(sector: String?): Double =
        sector?.trim()?.lowercase(Locale.US)?.let { sectorPeReference[it] } ?: 20.0

    fun fairValue(f: OracleFundamentals?, sector: String?): FairValue {
        if (f == null) return FairValue(null, "INSUFFICIENT DATA", null, null, null)
        val pe = f.trailingPe
        val growthPct = (f.earningsGrowth ?: f.revenueGrowth)?.let { it * 100.0 }
        val peg = if (pe != null && pe > 0.0 && growthPct != null && growthPct > 0.0) pe / growthPct else null
        val refPe = sectorPe(sector)

        val components = mutableListOf<Pair<Double, Double>>() // score to weight
        peg?.let { p ->
            val s = when { p <= 1.0 -> 90.0; p <= 1.5 -> 70.0; p <= 2.5 -> 45.0; else -> 20.0 }
            components += s to 0.40
        }
        pe?.takeIf { it > 0.0 }?.let { p ->
            val ratio = p / refPe
            val s = when { ratio <= 0.7 -> 88.0; ratio <= 0.9 -> 70.0; ratio <= 1.15 -> 55.0; ratio <= 1.5 -> 33.0; else -> 15.0 }
            components += s to 0.35
        }
        f.priceToBook?.takeIf { it > 0.0 }?.let { pb ->
            val s = when { pb <= 1.0 -> 85.0; pb <= 3.0 -> 60.0; pb <= 6.0 -> 35.0; else -> 15.0 }
            components += s to 0.25
        }
        if (components.isEmpty()) return FairValue(null, "INSUFFICIENT DATA", peg, refPe, f.priceToBook)

        var score = components.sumOf { it.first * it.second } / components.sumOf { it.second }
        // Forward P/E cheaper than trailing = the market expects earnings to
        // grow into the multiple — a small, capped nudge, not a component
        // that could dominate the read on its own.
        if (pe != null && pe > 0.0 && f.forwardPe != null && f.forwardPe > 0.0) {
            val delta = (pe - f.forwardPe) / pe
            score += (delta * 20.0).coerceIn(-8.0, 8.0)
        }
        val rounded = score.roundToIntClamped()
        val label = when { rounded >= 70 -> "UNDERVALUED"; rounded >= 45 -> "FAIRLY VALUED"; else -> "OVERVALUED" }
        return FairValue(rounded, label, peg, refPe, f.priceToBook)
    }

    fun financialHealth(f: OracleFundamentals?): FinancialHealth {
        if (f == null) return FinancialHealth(null, "INSUFFICIENT DATA", null, null, null, null, null)
        val components = mutableListOf<Double>()
        f.currentRatio?.let { cr -> components += when { cr >= 2.0 -> 90.0; cr >= 1.5 -> 75.0; cr >= 1.0 -> 55.0; cr >= 0.75 -> 35.0; else -> 15.0 } }
        f.debtToEquity?.let { de -> components += when { de <= 50.0 -> 90.0; de <= 100.0 -> 70.0; de <= 150.0 -> 50.0; de <= 250.0 -> 30.0; else -> 15.0 } }
        f.profitMargin?.let { pm -> val p = pm * 100.0; components += when { p >= 20.0 -> 90.0; p >= 10.0 -> 75.0; p >= 5.0 -> 55.0; p >= 0.0 -> 35.0; else -> 10.0 } }
        f.returnOnEquity?.let { roe -> val r = roe * 100.0; components += when { r >= 20.0 -> 85.0; r >= 10.0 -> 70.0; r >= 5.0 -> 50.0; r >= 0.0 -> 30.0; else -> 10.0 } }
        f.revenueGrowth?.let { rg -> val g = rg * 100.0; components += when { g >= 15.0 -> 85.0; g >= 5.0 -> 65.0; g >= 0.0 -> 50.0; g >= -5.0 -> 30.0; else -> 15.0 } }
        if (components.isEmpty()) return FinancialHealth(null, "INSUFFICIENT DATA", f.currentRatio, f.debtToEquity, f.profitMargin, f.returnOnEquity, f.revenueGrowth)
        val score = components.average().roundToIntClamped()
        val label = when { score >= 70 -> "STRONG"; score >= 50 -> "STABLE"; score >= 30 -> "WEAK"; else -> "DISTRESSED" }
        return FinancialHealth(score, label, f.currentRatio, f.debtToEquity, f.profitMargin, f.returnOnEquity, f.revenueGrowth)
    }

    private fun Double.roundToIntClamped(): Int = this.coerceIn(0.0, 100.0).let { Math.round(it).toInt() }
}

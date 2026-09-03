package ro.alintudor.oracle.core

/**
 * Detects the three "critical" position conditions that must actively notify
 * the person (push + email), on top of the regular BUY/SELL signal alerts
 * already shown in the list:
 *
 *  - URGENT_SELL: a large loss with no medium-term recovery in sight
 *    (still trending down over the last 20 trading days, RSI not yet
 *    turning up from oversold).
 *  - GROWTH_FADING: a large, fast gain that shows signs of running out of
 *    steam (overbought RSI after a sharp 5-day run), i.e. a good moment to
 *    consider taking profit rather than assuming it continues.
 *  - HIGH_VOLATILITY: a big, fast swing either way over the last 5 trading
 *    days, regardless of direction.
 *
 * Thresholds are simple, documented judgement calls — not a guarantee, and
 * clearly labelled everywhere as informational only (same disclaimer as the
 * rest of Oracle).
 */
object OracleAlertRules {
    private const val URGENT_SELL_LOSS_PCT = -12.0
    private const val URGENT_SELL_MOMENTUM20D = -2.0
    private const val URGENT_SELL_RSI_MAX = 42.0

    private const val GROWTH_FADING_GAIN_PCT = 15.0
    private const val GROWTH_FADING_MOMENTUM5D = 8.0
    private const val GROWTH_FADING_RSI_MIN = 75.0

    private const val HIGH_VOLATILITY_MOMENTUM5D = 15.0

    fun evaluate(position: OraclePosition, technical: OracleTechnicalSnapshot?, nowMillis: Long): List<OracleAlert> {
        if (technical == null) return emptyList()
        val out = mutableListOf<OracleAlert>()
        val ticker = position.ticker.uppercase()

        if (position.pnlPercent <= URGENT_SELL_LOSS_PCT && technical.momentum20D <= URGENT_SELL_MOMENTUM20D && technical.rsi <= URGENT_SELL_RSI_MAX) {
            out += OracleAlert(
                ticker = ticker,
                level = "HIGH",
                title = "URGENT: sustained loss, no recovery in sight",
                message = "${"%.1f".format(position.pnlPercent)}% and still trending down over 20 days (RSI ${"%.0f".format(technical.rsi)}). Informational only — not investment advice.",
                timestamp = nowMillis,
                active = true,
                kind = "URGENT_SELL"
            )
        }

        if (position.pnlPercent >= GROWTH_FADING_GAIN_PCT && technical.momentum5D >= GROWTH_FADING_MOMENTUM5D && technical.rsi >= GROWTH_FADING_RSI_MIN) {
            out += OracleAlert(
                ticker = ticker,
                level = "MEDIUM",
                title = "Sharp rally, momentum may not hold",
                message = "+${"%.1f".format(position.pnlPercent)}% with a fast run-up (RSI ${"%.0f".format(technical.rsi)}, overbought). Informational only — not investment advice.",
                timestamp = nowMillis,
                active = true,
                kind = "GROWTH_FADING"
            )
        }

        if (kotlin.math.abs(technical.momentum5D) >= HIGH_VOLATILITY_MOMENTUM5D) {
            out += OracleAlert(
                ticker = ticker,
                level = "MEDIUM",
                title = "High volatility",
                message = "${"%.1f".format(technical.momentum5D)}% move over 5 days — a fast swing either way. Informational only — not investment advice.",
                timestamp = nowMillis,
                active = true,
                kind = "HIGH_VOLATILITY"
            )
        }

        return out
    }
}

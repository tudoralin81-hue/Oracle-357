package ro.alintudor.luxoculi.core

import android.content.Context

/**
 * Visitor mode: the whole app, real market data, but the numbers that ARE
 * the product (Growth score / allocation / expected range, Portfolio
 * decisions, personal alerts, Knowledge beyond chapter 1) are locked behind
 * a padlock. No account, no server session, no sync, no push. A sample
 * portfolio is seeded so every screen has something to show.
 */
object OracleDemo {
    const val LOCK = "\uD83D\uDD12"
    /** The only tickers the demo may analyze, compare, or hold — the same two it seeds into the sample portfolio. */
    val TICKERS = setOf("AAPL", "NVDA")
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("oracle_demo", Context.MODE_PRIVATE)
    fun active(c: Context): Boolean = prefs(c).getBoolean("active", false)

    fun enter(c: Context) {
        prefs(c).edit().putBoolean("active", true).apply()
        val repo = OracleRepository(c)
        if (repo.cachedPositions().isEmpty()) {
            repo.savePositions(OracleCalculations.withWeights(listOf(
                OracleCalculations.position("AAPL", "Apple Inc.", 5.0, 0.0, 0.0),
                OracleCalculations.position("NVDA", "NVIDIA Corporation", 5.0, 0.0, 0.0)
            )))
            prefs(c).edit().putBoolean("seeded", true).apply()
        }
    }

    /** Sample entries need a real price: the first refresh fills currentPrice,
     *  and any position still at avgCost 0 gets an entry ~4% below it. */
    fun fixSampleEntries(c: Context) {
        if (!active(c)) return
        val repo = OracleRepository(c)
        val fixed = repo.cachedPositions().map { p -> if (p.avgCost <= 0.0 && p.currentPrice > 0.0) p.copy(avgCost = p.currentPrice * 0.96) else p }
        if (fixed != repo.cachedPositions()) repo.savePositions(OracleCalculations.withWeights(fixed))
    }

    fun exit(c: Context) {
        val seeded = prefs(c).getBoolean("seeded", false)
        prefs(c).edit().clear().apply()
        if (seeded) OracleRepository(c).savePositions(emptyList())
    }
}

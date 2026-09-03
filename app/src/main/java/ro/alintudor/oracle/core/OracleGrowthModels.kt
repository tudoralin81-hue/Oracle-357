package ro.alintudor.oracle.core

/**
 * Growth recommendation produced by the Oracle engine.
 * Android renders this result; it does not invent Score, Forecast or Signal.
 */
data class OracleGrowthRecommendation(
    val horizon: String,
    val ticker: String,
    val company: String,
    val sector: String,
    val score: Int,
    val signal: String,
    val risk: String,
    val allocationMax: Double,
    val forecastPct: Double,
    val momentum5D: Double,
    val momentum20D: Double,
    val weights: List<Int> = emptyList(),
    val newsTitle: String = "",
    val newsSource: String = "",
    val referenceTimestamp: Long = 0L,
    val currentActualPct: Double? = null,
    val referencePrice: Double? = null,
    val currentPrice: Double? = null,
    val adx: Double? = null,
    val factorValues: List<Double> = emptyList(),
    val factorScore: Double? = null,
    val generatedAt: Long = 0L,
    val source: String = "ORACLE_ENGINE"
)

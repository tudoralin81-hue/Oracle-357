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
    val source: String = "ORACLE_ENGINE",
    // Market regime the ranking was produced under (NORMAL / CAUTION /
    // DEFENSIVE) and the plain-language reason. In CAUTION/DEFENSIVE the
    // signal label is capped and allocation reduced — the ranking still
    // exists, but the app says out loud that the tide is against it.
    val marketRegime: String = "NORMAL",
    val regimeNote: String = "",
    // Days until the next earnings report, when known. SHORT/MEDIUM picks
    // skip names reporting within 7 days — an entry into earnings is a coin
    // flip, not a technical setup.
    val earningsInDays: Int? = null,
    // Hazard: the deliberate random ±3 nudge applied to this score
    // (see OracleGrowthEngine.hazardFor). Stored so the card can show it and
    // so a recorded signal stays explainable after the fact.
    val hazard: Int = 0,
    // Composite verdicts (OracleValuation), computed from the same fundamentals
    // already fetched for the "Fundamentals" scoring factor — no extra network cost.
    val fairValueLabel: String = "",
    val fairValueScore: Int? = null,
    val financialHealthLabel: String = "",
    val financialHealthScore: Int? = null,
    // true only for picks computed on-device by runInternal()/rankCandidates()
    // — false (the default) covers both a genuine server-sourced pick AND
    // any other caller that doesn't set this explicitly. Used purely for
    // the LOCAL MODE badge on the Growth screen; never affects scoring.
    val computedLocally: Boolean = false
)

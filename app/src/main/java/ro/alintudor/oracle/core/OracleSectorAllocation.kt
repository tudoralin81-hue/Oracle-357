package ro.alintudor.oracle.core

/**
 * V5.9.7 sector correction layer.
 * Sector is NOT a 13th scoring component. It changes allocation only.
 * Confirmed factors are the values recorded in the Oracle Growth reference document.
 */
object OracleSectorAllocation {
    private const val MIN_FACTOR = 0.50
    private const val MAX_FACTOR = 1.25

    // Pushed by OracleGrowthEmergency when an owner-loaded file is active —
    // same pattern as OracleSentiment's override. Null = use the built-in
    // rules below, which is the only path for every build until this is wired up.
    private var overrideRules: List<Pair<List<String>, Double>>? = null
    private var overrideMin: Double = MIN_FACTOR
    private var overrideMax: Double = MAX_FACTOR
    private var overrideDefault: Double = 1.0
    fun applyOverride(rules: List<Pair<List<String>, Double>>, min: Double, max: Double, default: Double) {
        overrideRules = rules; overrideMin = min; overrideMax = max; overrideDefault = default
    }
    fun clearOverride() { overrideRules = null; overrideMin = MIN_FACTOR; overrideMax = MAX_FACTOR; overrideDefault = 1.0 }

    /** Allocation correction is separate from the 12-component Growth score. */
    fun factorFor(sector: String?): Double {
        val s = sector?.trim()?.lowercase() ?: return overrideDefault
        val rules = overrideRules
        val raw = if (rules != null) {
            rules.firstOrNull { (keywords, _) -> keywords.any { s.contains(it) } }?.second ?: overrideDefault
        } else when {
            s.contains("biotech") || s.contains("biotechnology") -> 0.750
            s.contains("semiconductor") || s.contains("eda") -> 0.900
            s.contains("fintech") || s.contains("financial technology") -> 0.900
            s.contains("cybersecurity") || s.contains("cyber") -> 0.900
            s.contains("artificial intelligence") || s == "ai" || s.contains("ai /") || s.contains("/ ai") -> 0.850
            s.contains("healthcare defensive") || s.contains("defensive healthcare") -> 1.050
            s.contains("healthcare") || s.contains("health care") -> 1.050
            s.contains("industr") -> 1.000
            s.contains("utilities") -> 1.100
            else -> 1.000
        }
        return raw.coerceIn(if (rules != null) overrideMin else MIN_FACTOR, if (rules != null) overrideMax else MAX_FACTOR)
    }

    /** Allocation final = continuous base allocation * sector factor, rounded to exactly 1 decimal %. */
    fun apply(baseAllocation: Double, sector: String?): Double =
        kotlin.math.round((baseAllocation * factorFor(sector)).coerceIn(0.0, 8.0) * 10.0) / 10.0

    /** Retained for API compatibility: sector does not modify score weights in V5.9.7. */
    fun correctedWeights(base: IntArray, sector: String?): IntArray = base.copyOf()
}

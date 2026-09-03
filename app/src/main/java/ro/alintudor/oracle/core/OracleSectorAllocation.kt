package ro.alintudor.oracle.core

/**
 * V5.9.7 sector correction layer.
 * Sector is NOT a 13th scoring component. It changes allocation only.
 * Confirmed factors are the values recorded in the Oracle Growth reference document.
 */
object OracleSectorAllocation {
    private const val MIN_FACTOR = 0.50
    private const val MAX_FACTOR = 1.25

    /** Allocation correction is separate from the 12-component Growth score. */
    fun factorFor(sector: String?): Double {
        val s = sector?.trim()?.lowercase() ?: return 1.0
        return when {
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
        }.coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** Allocation final = continuous base allocation * sector factor, rounded to exactly 1 decimal %. */
    fun apply(baseAllocation: Double, sector: String?): Double =
        kotlin.math.round((baseAllocation * factorFor(sector)).coerceIn(0.0, 8.0) * 10.0) / 10.0

    /** Retained for API compatibility: sector does not modify score weights in V5.9.7. */
    fun correctedWeights(base: IntArray, sector: String?): IntArray = base.copyOf()
}

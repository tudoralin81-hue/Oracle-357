package ro.alintudor.luxoculi.core

/**
 * V5.9.7 sector correction layer.
 * Sector is NOT a 13th scoring component. It changes allocation only.
 *
 * The real sector-multiplier table used to live here as literal numbers.
 * It's been deliberately removed from the compiled app — the only place
 * these values exist now is the owner's own GrowthLocal-emergency.json,
 * loaded via TOOLS > Admin Only. Without it loaded, every sector gets a
 * neutral 1.0 factor: Growth's allocation simply skips this correction
 * rather than crashing or guessing.
 */
object OracleSectorAllocation {
    private const val MIN_FACTOR = 0.50
    private const val MAX_FACTOR = 1.25

    // Pushed by OracleGrowthEmergency when an owner-loaded file is active.
    // Null = no file loaded, so factorFor() below returns the neutral default.
    private var overrideRules: List<Pair<List<String>, Double>>? = null
    private var overrideMin: Double = MIN_FACTOR
    private var overrideMax: Double = MAX_FACTOR
    private var overrideDefault: Double = 1.0
    fun applyOverride(rules: List<Pair<List<String>, Double>>, min: Double, max: Double, default: Double) {
        overrideRules = rules; overrideMin = min; overrideMax = max; overrideDefault = default
    }
    fun clearOverride() { overrideRules = null; overrideMin = MIN_FACTOR; overrideMax = MAX_FACTOR; overrideDefault = 1.0 }

    /** Allocation correction is separate from the Growth score itself. */
    fun factorFor(sector: String?): Double {
        val s = sector?.trim()?.lowercase() ?: return overrideDefault
        val rules = overrideRules ?: return overrideDefault
        val raw = rules.firstOrNull { (keywords, _) -> keywords.any { s.contains(it) } }?.second ?: overrideDefault
        return raw.coerceIn(overrideMin, overrideMax)
    }

    /** Allocation final = continuous base allocation * sector factor, rounded to exactly 1 decimal %. */
    fun apply(baseAllocation: Double, sector: String?): Double =
        kotlin.math.round((baseAllocation * factorFor(sector)).coerceIn(0.0, 8.0) * 10.0) / 10.0

    /** Retained for API compatibility: sector does not modify score weights in V5.9.7. */
    fun correctedWeights(base: IntArray, sector: String?): IntArray = base.copyOf()
}

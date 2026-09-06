package ro.alintudor.oracle.core

/**
 * Single source of truth for the per-module build version shown at the
 * bottom of every Oracle module (e.g. "BUILD OR-GR-357.1.0" on Growth).
 *
 * Format: OR-<module code>-357.<MAJOR>.<MINOR>, tracked SEPARATELY per
 * module in VERSIONS below:
 *   - MINOR bumps by 1 for a module on every change made to that module.
 *   - MAJOR bumps by 1 (MINOR resets to 0) once per calendar month for that
 *     module, the first time it's touched that month.
 *
 * When making a change: bump ONLY the VERSIONS entry for the module(s) you
 * actually touched before packaging the build. Every other module's number
 * must be left exactly as it was — that's what makes the number mean
 * anything (when THIS module last changed, not the whole app).
 */
object OracleBuildInfo {
    // Each module tracks its own MAJOR.MINOR independently — the number on
    // screen means "when was THIS module last changed", not "when was
    // anything in the app last changed". When making a change: bump ONLY
    // the entry for the module(s) actually touched; every other module's
    // number must stay exactly as it was. MAJOR bumps (MINOR resets to 0)
    // once per calendar month for a module, the first time that module is
    // touched that month.
    private data class Version(val major: Int, val minor: Int)

    private val VERSIONS = mapOf(
        "PORTFOLIO" to Version(1, 29),
        "ALERTS" to Version(1, 30),
        "NEWS" to Version(1, 23),
        "GROWTH" to Version(1, 56),
        "KNOWLEDGE" to Version(1, 26),
        "ANALYSIS" to Version(1, 43),
        "WATCHLIST" to Version(1, 28),
        "ACTIVITY JOURNAL" to Version(1, 22)
    )

    private val MODULE_CODES = mapOf(
        "PORTFOLIO" to "PO",
        "ALERTS" to "AL",
        "NEWS" to "NE",
        "GROWTH" to "GR",
        "KNOWLEDGE" to "KN",
        "ANALYSIS" to "AN",
        "WATCHLIST" to "WA",
        "ACTIVITY JOURNAL" to "JO"
    )

    fun label(moduleTitle: String): String {
        val key = moduleTitle.uppercase()
        val code = MODULE_CODES[key] ?: moduleTitle.take(2).uppercase()
        val v = VERSIONS[key] ?: Version(1, 0)
        return "BUILD OR-$code-357.${v.major}.${v.minor}"
    }
}

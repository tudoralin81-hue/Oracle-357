package ro.alintudor.oracle.core

/**
 * Single source of truth for the per-module build version shown at the
 * bottom of every Oracle module (e.g. "BUILD OR-GR-357.1.0" on Growth).
 *
 * Format: OR-<module code>-357.<MAJOR>.<MINOR>
 *   - MINOR bumps by 1 on every change made to the app going forward.
 *   - MAJOR bumps by 1 (and MINOR resets to 0) once per calendar month —
 *     tracked in memory (not in this file) as the month this was last
 *     bumped, so it only rolls over once even across many sessions in
 *     the same month.
 *
 * When making ANY change to the app: bump MINOR by 1 here (or MAJOR+reset
 * if the calendar month has changed since the last bump) before packaging
 * the build for the user.
 */
object OracleBuildInfo {
    const val MAJOR = 1
    const val MINOR = 17

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
        val code = MODULE_CODES[moduleTitle.uppercase()] ?: moduleTitle.take(2).uppercase()
        return "BUILD OR-$code-357.$MAJOR.$MINOR"
    }
}

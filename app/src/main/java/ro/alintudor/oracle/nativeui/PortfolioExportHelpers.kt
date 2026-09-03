package ro.alintudor.oracle.nativeui

/**
 * PDF export helper. Kept separate so the portfolio renderer stays focused on UI.
 * The export table contains the portfolio rows; a neutral N/A is returned when
 * the available table data cannot establish a historical success rate.
 */
internal fun successRate(rows: List<List<String>>): String {
    if (rows.size <= 1) return "N/A"
    var valid = 0
    var positive = 0
    for (i in 1 until rows.size) {
        val row = rows[i]
        if (row.size < 7) continue
        val pnl = row[6].replace(".", "").replace(",", ".").toDoubleOrNull()
        if (pnl != null) {
            valid++
            if (pnl > 0.0) positive++
        }
    }
    return if (valid == 0) "N/A" else String.format(java.util.Locale.US, "%.1f%%", positive * 100.0 / valid)
}

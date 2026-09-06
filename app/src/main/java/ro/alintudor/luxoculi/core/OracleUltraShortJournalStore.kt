package ro.alintudor.luxoculi.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-only. One entry per day OracleGrowthEngine.computeUltraShort() finds
 * a candidate that beats the real SHORT pick. Each entry carries its own
 * argumentation snapshot (score, the 17 components, the SHORT score it
 * beat) so History still makes sense long after the day's scan cache is
 * gone, plus a quiet day-1/day-3 price check against the target — this is
 * the "did it actually work" record, not just a list of picks made.
 */
data class OracleUltraShortEntry(
    val ticker: String,
    val entryPrice: Double,
    val score: Int,
    val shortScoreBeaten: Int,
    val components: Map<String, Double>,
    val patterns: List<String>,
    val recommendedAt: Long,
    val day1Price: Double? = null,
    val day1CheckedAt: Long? = null,
    val day3Price: Double? = null,
    val day3CheckedAt: Long? = null,
    val targetHit: Boolean? = null,
)

class OracleUltraShortJournalStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("oracle_ultrashort_journal", Context.MODE_PRIVATE)
    companion object { const val TARGET_PCT = 10.0; const val WINDOW_DAYS = 3L }

    /** Newest-first, same rule as every other journal in the app. */
    fun load(): List<OracleUltraShortEntry> =
        parse(prefs.getString("entries", "[]") ?: "[]").sortedByDescending { it.recommendedAt }

    @Synchronized
    fun record(entry: OracleUltraShortEntry) {
        val current = load().toMutableList()
        // At most one entry per ticker per day — a re-run before midnight
        // updates the same day's row instead of duplicating it.
        val sameDay = current.filter { it.ticker == entry.ticker && isSameDay(it.recommendedAt, entry.recommendedAt) }
        current.removeAll(sameDay)
        current.add(0, entry)
        save(current.take(500))
    }

    /** Fills in the day-1/day-3 price checks for entries whose window has
     *  arrived, and settles targetHit once the 3-day window closes (or the
     *  moment +10% is actually reached, whichever comes first). Call this
     *  once a day, piggybacked on the same tick the daily scan already
     *  runs on — never blocks, never throws. */
    @Synchronized
    fun updateMonitoring(priceLookup: (String) -> Double?) {
        val now = System.currentTimeMillis()
        val current = load().toMutableList()
        var changed = false
        for (i in current.indices) {
            val e = current[i]
            if (e.targetHit != null) continue
            val ageDays = (now - e.recommendedAt) / 86_400_000L
            var updated = e
            if (ageDays >= 1 && e.day1Price == null) {
                priceLookup(e.ticker)?.let { updated = updated.copy(day1Price = it, day1CheckedAt = now); changed = true }
            }
            if (ageDays >= WINDOW_DAYS && e.day3Price == null) {
                priceLookup(e.ticker)?.let { updated = updated.copy(day3Price = it, day3CheckedAt = now); changed = true }
            }
            // Early success: settle the moment the target is actually hit,
            // don't make a good call wait out the rest of the window.
            val bestSoFar = maxOf(updated.day1Price ?: e.entryPrice, updated.day3Price ?: e.entryPrice)
            val returnPct = (bestSoFar / e.entryPrice - 1.0) * 100.0
            if (returnPct >= TARGET_PCT) { updated = updated.copy(targetHit = true); changed = true }
            else if (ageDays >= WINDOW_DAYS && updated.day3Price != null) { updated = updated.copy(targetHit = false); changed = true }
            current[i] = updated
        }
        if (changed) save(current)
    }

    /** Hit rate over settled entries only — an entry still inside its
     *  window isn't a miss yet, so it's excluded rather than counted
     *  against the average. */
    fun stats(): Triple<Int, Int, Double> {
        val settled = load().filter { it.targetHit != null }
        val hits = settled.count { it.targetHit == true }
        val rate = if (settled.isEmpty()) 0.0 else 100.0 * hits / settled.size
        return Triple(hits, settled.size, rate)
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
            cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun save(items: List<OracleUltraShortEntry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(JSONObject().apply {
                put("ticker", e.ticker); put("entryPrice", e.entryPrice); put("score", e.score)
                put("shortScoreBeaten", e.shortScoreBeaten)
                put("components", JSONObject().apply { e.components.forEach { (k, v) -> put(k, v) } })
                put("patterns", JSONArray(e.patterns))
                put("recommendedAt", e.recommendedAt)
                e.day1Price?.let { put("day1Price", it) }; e.day1CheckedAt?.let { put("day1CheckedAt", it) }
                e.day3Price?.let { put("day3Price", it) }; e.day3CheckedAt?.let { put("day3CheckedAt", it) }
                e.targetHit?.let { put("targetHit", it) }
            })
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun parse(text: String): List<OracleUltraShortEntry> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("ticker").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val compObj = o.optJSONObject("components")
            val components = LinkedHashMap<String, Double>()
            compObj?.keys()?.forEach { k -> components[k] = compObj.optDouble(k, 50.0) }
            val patternsArr = o.optJSONArray("patterns")
            val patterns = patternsArr?.let { p -> (0 until p.length()).map { p.optString(it) } } ?: emptyList()
            OracleUltraShortEntry(
                ticker = ticker, entryPrice = o.optDouble("entryPrice", 0.0), score = o.optInt("score", 0),
                shortScoreBeaten = o.optInt("shortScoreBeaten", 0), components = components, patterns = patterns,
                recommendedAt = o.optLong("recommendedAt", 0L),
                day1Price = if (o.has("day1Price")) o.optDouble("day1Price") else null,
                day1CheckedAt = if (o.has("day1CheckedAt")) o.optLong("day1CheckedAt") else null,
                day3Price = if (o.has("day3Price")) o.optDouble("day3Price") else null,
                day3CheckedAt = if (o.has("day3CheckedAt")) o.optLong("day3CheckedAt") else null,
                targetHit = if (o.has("targetHit")) o.optBoolean("targetHit") else null,
            )
        }
    }.getOrDefault(emptyList())
}

package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** One past Growth signal with what actually happened after it. */
data class OracleSignalOutcome(
    val key: String, val ticker: String, val horizon: String, val score: Int, val signal: String,
    val referenceTimestamp: Long, val entryPrice: Double,
    val r5: Double?, val r20: Double?, val r60: Double?
) {
    /** The return at the horizon the signal was issued for. */
    val horizonReturn: Double? get() = when (horizon.uppercase(Locale.US)) { "SHORT" -> r5; "MEDIUM" -> r20; else -> r60 }
}

data class OracleBandStats(val label: String, val count: Int, val hitRate: Double, val avgReturn: Double)

data class OraclePerformanceSummary(
    val tracked: Int, val settled: Int,
    val hit5: Double?, val avg5: Double?, val hit20: Double?, val avg20: Double?, val hit60: Double?, val avg60: Double?,
    val byScore: List<OracleBandStats>, val byHorizon: List<OracleBandStats>,
    val equityCurve: List<Double>, val best: OracleSignalOutcome?, val worst: OracleSignalOutcome?
)

/**
 * The honesty module. Every Growth signal ever shown is in the journal; this
 * store looks back and records the real close 5, 20 and 60 sessions later,
 * so the app can answer the only question that matters: does it work?
 * Outcomes are cached per signal; only signals missing a value get fetched,
 * a bounded number per refresh.
 */
class OraclePerformanceStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("oracle_performance", Context.MODE_PRIVATE)
    private fun key(r: OracleGrowthRecommendation) = "${r.referenceTimestamp}|${r.horizon.uppercase(Locale.US)}|${r.ticker.uppercase(Locale.US)}"

    private fun load(): MutableMap<String, JSONObject> = runCatching {
        val o = JSONObject(prefs.getString("outcomes", "{}") ?: "{}")
        val m = LinkedHashMap<String, JSONObject>()
        for (k in o.keys()) m[k] = o.getJSONObject(k)
        m
    }.getOrDefault(LinkedHashMap())

    private fun save(m: Map<String, JSONObject>) {
        prefs.edit().putString("outcomes", JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()).apply()
    }

    /** Fetches missing outcomes for settled-enough signals. Safe to call from any background thread. */
    fun update(maxFetches: Int = 8) {
        val entries = OracleGrowthJournalStore(context).load().filter { it.referenceTimestamp > 0L }
        if (entries.isEmpty()) return
        val outcomes = load()
        val now = System.currentTimeMillis()
        val candlesCache = HashMap<String, List<OracleOhlcvPoint>>()
        var fetches = 0
        var changed = false
        for (r in entries.sortedBy { it.referenceTimestamp }) {
            val k = key(r)
            val existing = outcomes[k]
            val ageDays = (now - r.referenceTimestamp) / 86_400_000L
            if (ageDays < 7) continue
            val complete = existing != null && !existing.isNull("r60")
            if (complete) continue
            // r60 needs ~90 calendar days; don't refetch daily for something that can't be settled yet
            if (existing != null && !existing.isNull("r20") && ageDays < 88) continue
            if (existing != null && !existing.isNull("r5") && existing.isNull("r20") && ageDays < 30) continue
            val t = r.ticker.uppercase(Locale.US)
            val candles = candlesCache.getOrPut(t) {
                if (fetches >= maxFetches) emptyList() else { fetches++; runCatching { OracleMarketData.fetchDaily(t, "1y") }.getOrDefault(emptyList()).sortedBy { it.timestamp } }
            }
            if (candles.isEmpty()) continue
            val entryIdx = candles.indexOfLast { it.timestamp <= r.referenceTimestamp + 12 * 3_600_000L }
            if (entryIdx < 0) continue
            val entryPrice = r.referencePrice?.takeIf { it > 0.0 } ?: candles[entryIdx].close
            fun ret(n: Int): Double? = candles.getOrNull(entryIdx + n)?.close?.let { (it / entryPrice - 1.0) * 100.0 }
            val o = JSONObject().apply {
                put("ticker", t); put("horizon", r.horizon.uppercase(Locale.US)); put("score", r.score); put("signal", r.signal)
                put("ts", r.referenceTimestamp); put("entry", entryPrice)
                put("r5", ret(5) ?: JSONObject.NULL); put("r20", ret(20) ?: JSONObject.NULL); put("r60", ret(60) ?: JSONObject.NULL)
            }
            outcomes[k] = o; changed = true
        }
        if (changed) save(outcomes)
    }

    fun outcomes(): List<OracleSignalOutcome> = load().map { (k, o) ->
        fun d(name: String) = if (o.isNull(name)) null else o.optDouble(name).takeIf { it.isFinite() }
        OracleSignalOutcome(k, o.optString("ticker"), o.optString("horizon"), o.optInt("score"), o.optString("signal"), o.optLong("ts"), o.optDouble("entry"), d("r5"), d("r20"), d("r60"))
    }.sortedBy { it.referenceTimestamp }

    fun summary(): OraclePerformanceSummary {
        val all = outcomes()
        fun stats(vals: List<Double>): Pair<Double?, Double?> = if (vals.isEmpty()) null to null else (vals.count { it > 0.0 } * 100.0 / vals.size) to vals.average()
        val (h5, a5) = stats(all.mapNotNull { it.r5 }); val (h20, a20) = stats(all.mapNotNull { it.r20 }); val (h60, a60) = stats(all.mapNotNull { it.r60 })
        val settled = all.filter { it.horizonReturn != null }
        fun band(label: String, items: List<OracleSignalOutcome>): OracleBandStats? {
            val v = items.mapNotNull { it.horizonReturn }; if (v.isEmpty()) return null
            return OracleBandStats(label, v.size, v.count { it > 0.0 } * 100.0 / v.size, v.average())
        }
        val byScore = listOfNotNull(
            band("Score 85\u2013100", settled.filter { it.score >= 85 }),
            band("Score 75\u201384", settled.filter { it.score in 75..84 }),
            band("Score < 75", settled.filter { it.score < 75 })
        )
        val byHorizon = listOfNotNull(
            band("SHORT (5 sessions)", settled.filter { it.horizon == "SHORT" }),
            band("MEDIUM (20 sessions)", settled.filter { it.horizon == "MEDIUM" }),
            band("LONG (60 sessions)", settled.filter { it.horizon == "LONG" })
        )
        var acc = 0.0
        val curve = settled.map { acc += it.horizonReturn!!; acc }
        return OraclePerformanceSummary(all.size, settled.size, h5, a5, h20, a20, h60, a60, byScore, byHorizon, curve,
            settled.maxByOrNull { it.horizonReturn!! }, settled.minByOrNull { it.horizonReturn!! })
    }
}

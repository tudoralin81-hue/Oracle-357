package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray

/**
 * Top 50 tickers by score from the server's full universe scan (all ~954
 * names it scanned today) — deliberately NOT derived from anything this
 * account has personally looked at (Watchlist, Portfolio, Alerts). Only
 * the ticker+score pairs are kept, not the full per-ticker payload
 * (components etc.) the server sends, since nothing else here needs it.
 */
object OracleTopTickersCache {
    private const val MAX_AGE_MS = 30L * 60L * 1000L
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("oracle_top_tickers", Context.MODE_PRIVATE)

    fun cached(c: Context): List<Pair<String, Int>> = runCatching {
        val arr = JSONArray(prefs(c).getString("items", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("t").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ticker to o.optInt("s")
        }
    }.getOrDefault(emptyList())

    fun isStale(c: Context): Boolean = (prefs(c).getLong("updatedAt", 0L)) < System.currentTimeMillis() - MAX_AGE_MS

    fun save(c: Context, items: List<Pair<String, Int>>) {
        val arr = JSONArray()
        items.forEach { (ticker, score) -> arr.put(org.json.JSONObject().apply { put("t", ticker); put("s", score) }) }
        prefs(c).edit().putString("items", arr.toString()).putLong("updatedAt", System.currentTimeMillis()).apply()
    }

    /** Background thread only. Fetches the server's full scan, keeps just
     *  the top 30 by score, saves them. No-op (not an error) if there's no
     *  session or the call fails — the caller just keeps showing whatever
     *  was cached before. */
    fun refresh(context: Context) {
        val token = runCatching { OracleAuthStore(context).token() }.getOrNull()?.takeIf { it.isNotBlank() && !OracleDemo.active(context) } ?: return
        val result = OracleApiClient.getFullUniverseScan(token).getOrNull() ?: return
        val items = result.optJSONArray("items") ?: return
        val parsed = (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("ticker").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ticker to o.optInt("baseScore")
        }
        if (parsed.isEmpty()) return
        save(context, parsed.sortedByDescending { it.second }.take(50))
    }
}

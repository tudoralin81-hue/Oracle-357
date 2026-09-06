package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray

/**
 * Top 50 tickers by score from the server's full universe scan (all ~954
 * names it scanned today) — deliberately NOT derived from anything this
 * account has personally looked at (Watchlist, Portfolio, Alerts). Only
 * ticker/price/momentum5D are kept, not the full per-ticker payload
 * (components etc.) the server sends, since nothing else here needs it.
 */
object OracleTopTickersCache {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("oracle_top_tickers", Context.MODE_PRIVATE)

    data class Item(val ticker: String, val price: Double, val momentum5D: Double)

    fun cached(c: Context): List<Item> = runCatching {
        val arr = JSONArray(prefs(c).getString("items", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("t").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Item(ticker, o.optDouble("p", 0.0), o.optDouble("m", 0.0))
        }
    }.getOrDefault(emptyList())

    fun updatedAt(c: Context): Long = prefs(c).getLong("updatedAt", 0L)

    /** Caller picks the threshold — shorter while the market's open (this
     *  is meant to track a live session), longer once it's closed (the
     *  server's own scan is only from the last close either way, so
     *  hammering it after-hours buys nothing). */
    fun isStale(c: Context, maxAgeMs: Long): Boolean = updatedAt(c) < System.currentTimeMillis() - maxAgeMs

    fun save(c: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { item -> arr.put(org.json.JSONObject().apply { put("t", item.ticker); put("p", item.price); put("m", item.momentum5D) }) }
        prefs(c).edit().putString("items", arr.toString()).putLong("updatedAt", System.currentTimeMillis()).apply()
    }

    /** Background thread only. Fetches the server's full scan, keeps just
     *  the top 50 by score (score decides ranking; price/momentum5D are
     *  what's actually displayed), saves them. No-op (not an error) if
     *  there's no session or the call fails — the caller just keeps
     *  showing whatever was cached before. */
    fun refresh(context: Context) {
        val token = runCatching { OracleAuthStore(context).token() }.getOrNull()?.takeIf { it.isNotBlank() && !OracleDemo.active(context) } ?: return
        val result = OracleApiClient.getFullUniverseScan(token).getOrNull() ?: return
        val items = result.optJSONArray("items") ?: return
        val parsed = (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("ticker").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(ticker, o.optInt("baseScore"), Item(ticker, o.optDouble("price", 0.0), o.optDouble("momentum5D", 0.0)))
        }
        if (parsed.isEmpty()) return
        save(context, parsed.sortedByDescending { it.second }.take(50).map { it.third })
    }
}

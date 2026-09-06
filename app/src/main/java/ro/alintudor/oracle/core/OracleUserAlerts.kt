package ro.alintudor.oracle.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** An alert the person defined themselves. Price/score alerts are one-shot
 *  (they disarm after firing, like a broker alert); SIGNAL_CHANGE stays armed
 *  and fires every time the Growth signal label for the ticker changes. */
data class OracleUserAlert(
    val id: String,
    val ticker: String,
    val type: String,          // PRICE_ABOVE, PRICE_BELOW, SCORE_ABOVE, SCORE_BELOW, SIGNAL_CHANGE
    val threshold: Double,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long = 0L,
    val lastSignal: String = ""
) {
    fun describe(): String = when (type) {
        "PRICE_ABOVE" -> "Price above ${money(threshold)}"
        "PRICE_BELOW" -> "Price below ${money(threshold)}"
        "SCORE_ABOVE" -> "Growth score \u2265 ${threshold.toInt()}"
        "SCORE_BELOW" -> "Growth score \u2264 ${threshold.toInt()}"
        else -> "Signal changes"
    }
    private fun money(v: Double) = String.format(Locale.US, if (v >= 100) "%.0f" else "%.2f", v)
}

class OracleUserAlertStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_user_alerts", Context.MODE_PRIVATE)
    fun load(): List<OracleUserAlert> = runCatching {
        val a = JSONArray(prefs.getString("items", "[]") ?: "[]")
        List(a.length()) { i -> val o = a.getJSONObject(i)
            OracleUserAlert(o.optString("id"), o.optString("ticker"), o.optString("type"), o.optDouble("threshold"), o.optBoolean("enabled", true), o.optLong("createdAt"), o.optLong("lastFiredAt"), o.optString("lastSignal")) }
    }.getOrDefault(emptyList())
    fun save(items: List<OracleUserAlert>) {
        prefs.edit().putString("items", JSONArray().apply { items.forEach { a -> put(JSONObject().apply {
            put("id", a.id); put("ticker", a.ticker); put("type", a.type); put("threshold", a.threshold); put("enabled", a.enabled)
            put("createdAt", a.createdAt); put("lastFiredAt", a.lastFiredAt); put("lastSignal", a.lastSignal) }) } }.toString()).apply()
    }
    fun add(ticker: String, type: String, threshold: Double): OracleUserAlert {
        val a = OracleUserAlert("ua_${System.currentTimeMillis()}", ticker.trim().uppercase(Locale.US), type, threshold)
        save(load() + a); return a
    }
    fun remove(id: String) = save(load().filterNot { it.id == id })
    fun update(a: OracleUserAlert) = save(load().map { if (it.id == a.id) a else it })
    fun tickers(): List<String> = load().filter { it.enabled }.map { it.ticker }.distinct()
}

/** Per-ticker Growth-style technical score, cached with a timestamp so the
 *  Watchlist and user alerts can show/use it without a full Growth run. */
data class OracleTickerScore(val ticker: String, val score: Int, val signal: String, val price: Double, val updatedAt: Long)

object OracleTickerScoreCache {
    private const val MAX_AGE_MS = 60L * 60L * 1000L
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("oracle_ticker_scores", Context.MODE_PRIVATE)
    fun all(c: Context): Map<String, OracleTickerScore> = runCatching {
        val o = JSONObject(prefs(c).getString("scores", "{}") ?: "{}")
        val m = LinkedHashMap<String, OracleTickerScore>()
        for (k in o.keys()) { val v = o.getJSONObject(k); m[k] = OracleTickerScore(k, v.optInt("score"), v.optString("signal"), v.optDouble("price"), v.optLong("updatedAt")) }
        m
    }.getOrDefault(emptyMap())
    fun get(c: Context, ticker: String): OracleTickerScore? = all(c)[ticker.trim().uppercase(Locale.US)]
    fun put(c: Context, items: Collection<OracleTickerScore>) {
        if (items.isEmpty()) return
        val m = LinkedHashMap(all(c)); items.forEach { m[it.ticker] = it }
        prefs(c).edit().putString("scores", JSONObject().apply { m.forEach { (k, v) -> put(k, JSONObject().apply { put("score", v.score); put("signal", v.signal); put("price", v.price); put("updatedAt", v.updatedAt) }) } }.toString()).apply()
    }
    fun isStale(c: Context, ticker: String): Boolean = (get(c, ticker)?.updatedAt ?: 0L) < System.currentTimeMillis() - MAX_AGE_MS
    /** Computes from candles already in hand (free — no network). */
    fun fromCandles(ticker: String, candles: List<OracleOhlcvPoint>): OracleTickerScore? {
        val score = OracleGrowthEngine.technicalScore(candles) ?: return null
        val price = candles.maxByOrNull { it.timestamp }?.close ?: return null
        return OracleTickerScore(ticker.uppercase(Locale.US), score, OracleGrowthEngine.ratingFor(score), price, System.currentTimeMillis())
    }
    /** Fetches and scores the stale tickers, at most maxFetches of them. Background thread only. */
    fun refresh(c: Context, tickers: Collection<String>, maxFetches: Int = 10) {
        var fetches = 0
        val fresh = ArrayList<OracleTickerScore>()
        val token = runCatching { OracleAuthStore(c).token() }.getOrNull()?.takeIf { it.isNotBlank() && !OracleDemo.active(c) }
        for (t in tickers.map { it.trim().uppercase(Locale.US) }.filter { it.isNotBlank() }.distinct()) {
            if (!isStale(c, t)) continue
            if (fetches >= maxFetches) break
            fetches++
            val fromServer = token?.let { tok ->
                runCatching {
                    val response = OracleApiClient.getUniverseScan(tok, t).getOrNull() ?: return@runCatching null
                    val item = response.optJSONArray("items")?.let { arr -> (0 until arr.length()).map { arr.optJSONObject(it) }.firstOrNull() } ?: return@runCatching null
                    val score = item.optInt("baseScore", -1).takeIf { it in 0..100 } ?: return@runCatching null
                    val price = item.optDouble("price", 0.0).takeIf { it > 0.0 } ?: return@runCatching null
                    OracleTickerScore(t, score, OracleGrowthEngine.ratingFor(score), price, System.currentTimeMillis())
                }.getOrNull()
            }
            val scored = fromServer ?: run {
                val candles = runCatching { OracleMarketData.fetchDaily(t, "1y") }.getOrDefault(emptyList())
                fromCandles(t, candles)
            }
            scored?.let { fresh += it }
        }
        put(c, fresh)
    }
}

/**
 * The one place alerts come from. Three sources, one list:
 *   SIGNAL   — the BUY / SELL / REDUCE decisions on held positions
 *   CRITICAL — urgent sell, fading growth, high volatility (OracleAlertRules)
 *   USER     — the person's own price / score / signal-change alerts
 * Both the in-app refresh and the background check call this.
 */
object OracleAlertCenter {
    fun signalAlerts(actions: List<OracleAction>, now: Long): List<OracleAlert> =
        actions.filter { it.action == "BUY" || it.action == "SELL" || it.action == "REDUCE" }
            .map { OracleAlert(it.ticker, when (it.action) { "SELL" -> "HIGH"; "REDUCE" -> "MEDIUM"; else -> "INFO" }, "${it.action} signal", it.reason, now, true, "SIGNAL") }

    fun criticalAlerts(positions: List<OraclePosition>, technicalByTicker: Map<String, OracleTechnicalSnapshot>, now: Long): List<OracleAlert> =
        positions.flatMap { p -> OracleAlertRules.evaluate(p, technicalByTicker[p.ticker.uppercase(Locale.US)], now) }

    /** Evaluates the person's alerts against the given quotes/scores. Fired
     *  one-shot alerts are disarmed in the store; SIGNAL_CHANGE remembers the
     *  last label so it only fires on a real change. */
    fun userAlerts(context: Context, quotes: Map<String, Double>, scores: Map<String, OracleTickerScore>, now: Long): List<OracleAlert> {
        val store = OracleUserAlertStore(context)
        val out = ArrayList<OracleAlert>()
        for (a in store.load()) {
            if (!a.enabled) continue
            val t = a.ticker.uppercase(Locale.US)
            val price = quotes[t] ?: scores[t]?.price
            val sc = scores[t]
            val f = Locale.US
            when (a.type) {
                "PRICE_ABOVE" -> if (price != null && price >= a.threshold) { out += OracleAlert(t, "MEDIUM", "$t above ${"%.2f".format(f, a.threshold)}", "Price ${"%.2f".format(f, price)} crossed your level", now, true, "USER"); store.update(a.copy(enabled = false, lastFiredAt = now)) }
                "PRICE_BELOW" -> if (price != null && price <= a.threshold) { out += OracleAlert(t, "HIGH", "$t below ${"%.2f".format(f, a.threshold)}", "Price ${"%.2f".format(f, price)} crossed your level", now, true, "USER"); store.update(a.copy(enabled = false, lastFiredAt = now)) }
                "SCORE_ABOVE" -> if (sc != null && sc.score >= a.threshold.toInt()) { out += OracleAlert(t, "MEDIUM", "$t score ${sc.score}", "Growth score reached ${sc.score} (\u2265 ${a.threshold.toInt()}) \u2014 ${sc.signal}", now, true, "USER"); store.update(a.copy(enabled = false, lastFiredAt = now)) }
                "SCORE_BELOW" -> if (sc != null && sc.score <= a.threshold.toInt()) { out += OracleAlert(t, "HIGH", "$t score ${sc.score}", "Growth score fell to ${sc.score} (\u2264 ${a.threshold.toInt()}) \u2014 ${sc.signal}", now, true, "USER"); store.update(a.copy(enabled = false, lastFiredAt = now)) }
                "SIGNAL_CHANGE" -> if (sc != null) {
                    if (a.lastSignal.isNotBlank() && a.lastSignal != sc.signal) out += OracleAlert(t, if (sc.signal == "AVOID" || sc.signal == "WATCH") "HIGH" else "MEDIUM", "$t: ${a.lastSignal} \u2192 ${sc.signal}", "Growth signal changed (score ${sc.score})", now, true, "USER")
                    if (a.lastSignal != sc.signal) store.update(a.copy(lastSignal = sc.signal, lastFiredAt = if (a.lastSignal.isNotBlank()) now else a.lastFiredAt))
                }
            }
        }
        return out
    }

    /** Push-notify each alert at most once per ticker+kind+day. Server path
     *  (email + FCM) when there is a session, local notification otherwise. */
    fun notify(context: Context, alerts: List<OracleAlert>) {
        if (alerts.isEmpty()) return
        val settings = OracleAlertSettingsStore(context)
        val auth = OracleAuthStore(context)
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        for (alert in alerts) {
            val notifyKey = "${alert.ticker}|${alert.kind}|${alert.title}|$dayKey"
            if (settings.alreadyNotified(notifyKey)) continue
            if (auth.hasSession()) {
                OracleApiClient.notify(auth.token(), "Oracle alert \u2014 ${alert.ticker}: ${alert.title}",
                    "Dear investor,\n\nOracle has an alert for you.\n\n${alert.ticker} \u2014 ${alert.title}\n${alert.message}\n\n\u2014 Oracle")
            } else {
                OracleNotifier.notify(context, alert, settings.email())
            }
            settings.markNotified(notifyKey)
        }
    }
}

/** Background check for the person's own alerts while the app is closed:
 *  every 15 minutes during market hours, hourly otherwise (cheap wake-ups —
 *  it does nothing when the market is closed). */
class OracleAlertCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) { schedule(app); return }
        val pending = goAsync()
        Thread {
            try {
                val now = System.currentTimeMillis()
                if (OracleMarketCalendar.status(now).open) {
                    val tickers = OracleUserAlertStore(app).tickers()
                    if (tickers.isNotEmpty()) {
                        val quotes = HashMap<String, Double>()
                        for (t in tickers.take(15)) runCatching { OracleMarketData.fetchDaily(t, "5d") }.getOrNull()?.lastOrNull()?.close?.let { quotes[t] = it }
                        OracleTickerScoreCache.refresh(app, tickers, maxFetches = 6)
                        val fired = OracleAlertCenter.userAlerts(app, quotes, OracleTickerScoreCache.all(app), now)
                        if (fired.isNotEmpty()) {
                            val repo = OracleRepository(app)
                            repo.saveAlerts((repo.cachedAlerts() + fired).sortedByDescending { it.timestamp }.take(150))
                            OracleAlertCenter.notify(app, fired)
                        }
                    }
                }
            } finally { schedule(app); pending.finish() }
        }.start()
    }

    companion object {
        fun schedule(context: Context) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(app, OracleAlertCheckReceiver::class.java)
            val pending = android.app.PendingIntent.getBroadcast(app, 7108, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            val gap = if (OracleMarketCalendar.status().open) 15L * 60L * 1000L else 60L * 60L * 1000L
            val next = System.currentTimeMillis() + gap
            if (android.os.Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, pending)
            else alarm.set(android.app.AlarmManager.RTC_WAKEUP, next, pending)
        }
    }
}

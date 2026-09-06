package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray

/** Persistent watchlist kept separate from Portfolio positions. */
class OracleWatchlistStore(context: Context) {
    private val prefs=context.getSharedPreferences("oracle_watchlist",Context.MODE_PRIVATE)
    fun load():List<String> = runCatching {
        val a=JSONArray(prefs.getString("tickers","[]")?:"[]")
        List(a.length()){i->a.optString(i).uppercase()}.filter{it.isNotBlank()}.distinct()
    }.getOrDefault(emptyList())
    fun save(items:List<String>){prefs.edit().putString("tickers",JSONArray().apply{items.map{it.trim().uppercase()}.filter{it.isNotBlank()}.distinct().forEach{put(it)}}.toString()).apply()}

    /** Off by default — a Watchlist ticker turning bullish generating an
     *  alert is a real behavior change the person should choose, not
     *  something that just starts happening silently the moment they save
     *  a ticker. Surfaced as a visible toggle right on the Watchlist screen. */
    fun alertsEnabled():Boolean = prefs.getBoolean("alerts_enabled", false)
    fun setAlertsEnabled(enabled:Boolean){prefs.edit().putBoolean("alerts_enabled", enabled).apply()}
}

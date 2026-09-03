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
}

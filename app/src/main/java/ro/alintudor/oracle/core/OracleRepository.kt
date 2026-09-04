package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Fully local native data layer. No WordPress/API dependency. */
// B540: `context` is exposed (was private) so the Growth-only single-flight
// snapshot orchestration in OracleLocalProcessor can thread it into
// OracleGrowthEngine.run() for the S&P 500 universe cache (Requirement #2).
// No existing method, field, or behavior below this line is changed.
class OracleRepository(val context: Context) {
    private val prefs = context.getSharedPreferences("oracle_data", Context.MODE_PRIVATE)

    fun cachedPositions(): List<OraclePosition> = parsePositions(prefs.getString("positions", "[]") ?: "[]")
    fun cachedAlerts(): List<OracleAlert> = parseAlerts(prefs.getString("alerts", "[]") ?: "[]")
    fun cachedNews(): List<OracleNews> = parseNews(prefs.getString("news", "[]") ?: "[]")
    fun cachedHistory(): List<OracleHistoryPoint> = parseHistory(prefs.getString("history", "[]") ?: "[]")
    fun cachedActions(): List<OracleAction> = parseActions(prefs.getString("actions", "[]") ?: "[]")
    fun cachedTechnical(): List<OracleTechnicalSnapshot> = parseTechnical(prefs.getString("technical", "[]") ?: "[]")
    fun cachedKnowledge(): List<OracleKnowledgeItem> = parseKnowledge(prefs.getString("knowledge", "[]") ?: "[]")
    fun cachedJournal(): List<OracleJournalEntry> = parseJournal(prefs.getString("journal", "[]") ?: "[]")
    fun cachedGrowth(): List<OracleGrowthRecommendation> = parseGrowth(prefs.getString("growth", "[]") ?: "[]")

    fun bootstrapVersion(): Int = prefs.getInt("bootstrap_version", 0)
    fun markBootstrap(version: Int) { prefs.edit().putInt("bootstrap_version", version).apply() }

    fun savePositions(items: List<OraclePosition>) = saveAndSync("positions", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveAlerts(items: List<OracleAlert>) = saveAndSync("alerts", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveNews(items: List<OracleNews>) = saveAndSync("news", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveHistory(items: List<OracleHistoryPoint>) = saveAndSync("history", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveActions(items: List<OracleAction>) = saveAndSync("actions", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveTechnical(items: List<OracleTechnicalSnapshot>) = saveAndSync("technical", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveKnowledge(items: List<OracleKnowledgeItem>) = saveAndSync("knowledge", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveJournal(items: List<OracleJournalEntry>) = saveAndSync("journal", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())
    fun saveGrowth(items: List<OracleGrowthRecommendation>) = saveAndSync("growth", JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())

    private fun saveAndSync(key: String, json: String) {
        prefs.edit().putString(key, json).apply()
        OracleSyncManager.pushDataType(context, key, json)
    }

    private fun parsePositions(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->positionFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseAlerts(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->alertFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseNews(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->newsFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseHistory(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->historyFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseActions(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->actionFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseTechnical(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->technicalFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseKnowledge(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->knowledgeFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseJournal(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->journalFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())
    private fun parseGrowth(s: String) = runCatching { val a=JSONArray(s); List(a.length()){i->growthFromJson(a.getJSONObject(i))} }.getOrDefault(emptyList())

    private fun positionFromJson(o: JSONObject) = OraclePosition(o.optString("ticker"),o.optString("company"),o.optDouble("shares"),o.optDouble("avgCost"),o.optDouble("currentPrice"),o.optString("currency","USD"),o.optDouble("pnl"),o.optDouble("pnlPercent"),o.optDouble("marketValue"),o.optDouble("weight"),o.optString("status","ACTIVE"),o.optLong("entryTimestamp",0L))
    private fun alertFromJson(o: JSONObject) = OracleAlert(o.optString("ticker"),o.optString("level"),o.optString("title"),o.optString("message"),o.optLong("timestamp"),o.optBoolean("active",true),o.optString("kind","SIGNAL"))
    private fun newsFromJson(o: JSONObject) = OracleNews(o.optString("ticker"),o.optString("title"),o.optString("source"),o.optString("url"),o.optLong("publishedAt"),o.optBoolean("breaking",false))
    private fun historyFromJson(o: JSONObject) = OracleHistoryPoint(o.optString("ticker"),o.optLong("timestamp"),o.optDouble("price"),o.optDouble("value"),o.optDouble("pnl"))
    private fun actionFromJson(o: JSONObject) = OracleAction(o.optString("ticker"),o.optString("action"),o.optDouble("score"),o.optString("reason"),o.optLong("timestamp"))
    private fun technicalFromJson(o: JSONObject) = OracleTechnicalSnapshot(o.optString("ticker"),o.optDouble("rsi"),o.optDouble("sma50"),o.optDouble("momentum5D"),o.optDouble("momentum20D"),o.optDouble("support20D"),o.optDouble("resistance20D"), if(o.has("adx") && !o.isNull("adx")) o.optDouble("adx") else null,
        if(o.has("atr14") && !o.isNull("atr14")) o.optDouble("atr14") else null, if(o.has("sma200") && !o.isNull("sma200")) o.optDouble("sma200") else null,
        if(o.has("techScore") && !o.isNull("techScore")) o.optInt("techScore") else null, o.optLong("computedAt",0L))
    private fun knowledgeFromJson(o: JSONObject) = OracleKnowledgeItem(o.optString("title"),o.optString("category"),o.optString("content"),o.optLong("publishedAt"))
    private fun journalFromJson(o: JSONObject) = OracleJournalEntry(o.optLong("timestamp"),o.optString("ticker"),o.optString("action"),o.optDouble("score"),o.optString("reason"),o.optString("status","ACTIVE"),o.optDouble("shares"),o.optDouble("entryPrice"),o.optDouble("salePrice"),o.optDouble("salePercent"),o.optDouble("entryValue"),o.optDouble("saleValue"),o.optDouble("realizedPnl"),o.optString("positionId"))
    private fun growthFromJson(o: JSONObject): OracleGrowthRecommendation {
        val weightsJson=o.optJSONArray("weights") ?: JSONArray()
        val weights=List(weightsJson.length()){i->weightsJson.optInt(i)}
        val actual=if(o.has("currentActualPct") && !o.isNull("currentActualPct"))o.optDouble("currentActualPct") else null
        val ref=if(o.has("referencePrice") && !o.isNull("referencePrice"))o.optDouble("referencePrice") else null
        val cur=if(o.has("currentPrice") && !o.isNull("currentPrice"))o.optDouble("currentPrice") else null
        val adx=if(o.has("adx") && !o.isNull("adx"))o.optDouble("adx") else null
        return OracleGrowthRecommendation(o.optString("horizon"),o.optString("ticker"),o.optString("company"),o.optString("sector"),o.optInt("score"),o.optString("signal"),o.optString("risk"),o.optDouble("allocationMax"),o.optDouble("forecastPct"),o.optDouble("momentum5D"),o.optDouble("momentum20D"),weights,o.optString("newsTitle"),o.optString("newsSource"),o.optLong("referenceTimestamp"),actual,ref,cur,adx,
            marketRegime=o.optString("marketRegime","NORMAL"),regimeNote=o.optString("regimeNote",""),
            earningsInDays=if(o.has("earningsInDays") && !o.isNull("earningsInDays")) o.optInt("earningsInDays") else null)
    }
}

private fun OraclePosition.toJson() = JSONObject().apply { put("ticker",ticker); put("company",company); put("shares",shares); put("avgCost",avgCost); put("currentPrice",currentPrice); put("currency",currency); put("pnl",pnl); put("pnlPercent",pnlPercent); put("marketValue",marketValue); put("weight",weight); put("status",status); put("entryTimestamp",entryTimestamp) }
private fun OracleAlert.toJson() = JSONObject().apply { put("ticker",ticker); put("level",level); put("title",title); put("message",message); put("timestamp",timestamp); put("active",active); put("kind",kind) }
private fun OracleNews.toJson() = JSONObject().apply { put("ticker",ticker); put("title",title); put("source",source); put("url",url); put("publishedAt",publishedAt); put("breaking",breaking) }
private fun OracleHistoryPoint.toJson() = JSONObject().apply { put("ticker",ticker); put("timestamp",timestamp); put("price",price); put("value",value); put("pnl",pnl) }
private fun OracleAction.toJson() = JSONObject().apply { put("ticker",ticker); put("action",action); put("score",score); put("reason",reason); put("timestamp",timestamp) }
private fun OracleTechnicalSnapshot.toJson() = JSONObject().apply { put("ticker",ticker); put("rsi",rsi); put("sma50",sma50); put("momentum5D",momentum5D); put("momentum20D",momentum20D); put("support20D",support20D); put("resistance20D",resistance20D); if(adx!=null)put("adx",adx) else put("adx",JSONObject.NULL)
    if(atr14!=null)put("atr14",atr14) else put("atr14",JSONObject.NULL); if(sma200!=null)put("sma200",sma200) else put("sma200",JSONObject.NULL)
    if(techScore!=null)put("techScore",techScore) else put("techScore",JSONObject.NULL); put("computedAt",computedAt) }
private fun OracleKnowledgeItem.toJson() = JSONObject().apply { put("title",title); put("category",category); put("content",content); put("publishedAt",publishedAt) }
private fun OracleJournalEntry.toJson() = JSONObject().apply { put("timestamp",timestamp); put("ticker",ticker); put("action",action); put("score",score); put("reason",reason); put("status",status); put("shares",shares); put("entryPrice",entryPrice); put("salePrice",salePrice); put("salePercent",salePercent); put("entryValue",entryValue); put("saleValue",saleValue); put("realizedPnl",realizedPnl); put("positionId",positionId) }
private fun OracleGrowthRecommendation.toJson() = JSONObject().apply {
    put("horizon",horizon); put("ticker",ticker); put("company",company); put("sector",sector); put("score",score); put("signal",signal); put("risk",risk)
    put("allocationMax",allocationMax); put("forecastPct",forecastPct); put("momentum5D",momentum5D); put("momentum20D",momentum20D)
    put("weights",JSONArray().apply{weights.forEach{put(it)}}); put("newsTitle",newsTitle); put("newsSource",newsSource); put("referenceTimestamp",referenceTimestamp)
    if(currentActualPct!=null)put("currentActualPct",currentActualPct) else put("currentActualPct",JSONObject.NULL)
    if(referencePrice!=null)put("referencePrice",referencePrice) else put("referencePrice",JSONObject.NULL)
    if(currentPrice!=null)put("currentPrice",currentPrice) else put("currentPrice",JSONObject.NULL)
    if(adx!=null)put("adx",adx) else put("adx",JSONObject.NULL)
    put("marketRegime",marketRegime); put("regimeNote",regimeNote); if(earningsInDays!=null)put("earningsInDays",earningsInDays) else put("earningsInDays",JSONObject.NULL)
}

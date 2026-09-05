package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GrowthLocal-emergency: an optional, owner-loaded override for the
 * weights/sentiment-lexicon/sector-allocation data that otherwise lives
 * hardcoded in OracleGrowthEngine.kt / OracleSentiment.kt /
 * OracleSectorAllocation.kt. Loaded via a file picker in TOOLS, kept ONLY in
 * this device's private storage (filesDir — never backed up to the cloud by
 * default, never bundled in the APK, never synced to the server).
 *
 * IMPORTANT — sequencing: as of this build, the hardcoded values in those
 * three files are UNTOUCHED. This object only parses, stores, and exposes
 * what was loaded, so the whole import→persist→reload path can be tested
 * end-to-end first. Wiring OracleGrowthEngine's actual ranking to prefer
 * this data over the hardcoded constants — and then removing the hardcoded
 * constants from what ships to testers — is the deliberate next step, not
 * this one.
 */
object OracleGrowthEmergency {
    data class Loaded(
        val factorKeys: List<String>,
        val horizonWeights: Map<String, IntArray>,
        val sentimentPhrases: List<Pair<String, Double>>,
        val sentimentNegations: List<String>,
        val sectorRules: List<Pair<List<String>, Double>>,
        val sectorMin: Double,
        val sectorMax: Double,
        val sectorDefault: Double,
        val loadedAt: Long
    )

    private var cached: Loaded? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "oracle_growth_emergency.json")

    fun isLoaded(context: Context): Boolean = current(context) != null

    fun loadedAt(context: Context): Long? = current(context)?.loadedAt

    /** The active horizon weights for the engine to use: the loaded override
     *  if one is cached, otherwise the caller's own built-in fallback. Does
     *  NOT take a Context — relies on `current(context)` having already run
     *  at least once this process (OracleGrowthEngine.runInternal calls it
     *  at the top of every run, which keeps this cheap and Context-free at
     *  the actual per-horizon call sites deep in the ranking code). */
    fun activeWeights(horizon: String, builtIn: IntArray): IntArray = cached?.horizonWeights?.get(horizon) ?: builtIn

    private fun applyToConsumers(l: Loaded?) {
        if (l == null) { OracleSentiment.clearOverride(); OracleSectorAllocation.clearOverride() }
        else {
            OracleSentiment.applyOverride(l.sentimentPhrases, l.sentimentNegations)
            OracleSectorAllocation.applyOverride(l.sectorRules, l.sectorMin, l.sectorMax, l.sectorDefault)
        }
    }

    fun current(context: Context): Loaded? {
        cached?.let { return it }
        val f = file(context)
        if (!f.exists()) return null
        val loaded = runCatching { parse(f.readText()) }.getOrNull()
        cached = loaded
        applyToConsumers(loaded)
        return loaded
    }

    /** Parses a freshly-picked file and, only if it parses as the expected
     *  shape, persists it and makes it the active override. Returns a short
     *  human-readable summary on success, or null on any failure (bad JSON,
     *  wrong shape, missing fields) — the caller shows that as an error and
     *  nothing already loaded is disturbed. */
    fun importFrom(context: Context, text: String): String? {
        val parsed = runCatching { parse(text) }.getOrNull() ?: return null
        if (parsed.horizonWeights.keys != setOf("SHORT", "MEDIUM", "LONG")) return null
        if (parsed.horizonWeights.values.any { it.size != parsed.factorKeys.size }) return null
        val stamped = parsed.copy(loadedAt = System.currentTimeMillis())
        cached = stamped
        runCatching { file(context).writeText(toStorageJson(stamped)) }.getOrElse { cached = null; return null }
        applyToConsumers(stamped)
        return "Loaded ${stamped.horizonWeights.size} horizons \u00d7 ${stamped.factorKeys.size} factors, " +
            "${stamped.sentimentPhrases.size} sentiment phrases, ${stamped.sectorRules.size} sector rules."
    }

    fun clear(context: Context) {
        cached = null
        runCatching { file(context).delete() }
        applyToConsumers(null)
    }

    private fun parse(text: String): Loaded {
        val root = JSONObject(text)
        val factorKeys = root.getJSONArray("factorKeys").toStringList()
        val weightsObj = root.getJSONObject("horizonWeights")
        val horizonWeights = mutableMapOf<String, IntArray>()
        for (h in listOf("SHORT", "MEDIUM", "LONG")) {
            val arr = weightsObj.getJSONArray(h)
            horizonWeights[h] = IntArray(arr.length()) { arr.getInt(it) }
        }
        val sentimentObj = root.getJSONObject("sentiment")
        val phrases = sentimentObj.getJSONArray("phrases").let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let { it.getString("phrase") to it.getDouble("weight") } }
        }
        val negations = sentimentObj.getJSONArray("negations").toStringList()
        val sectorObj = root.getJSONObject("sectorAllocation")
        val rules = sectorObj.getJSONArray("rules").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getJSONArray("contains").toStringList() to o.getDouble("factor")
            }
        }
        return Loaded(
            factorKeys = factorKeys, horizonWeights = horizonWeights,
            sentimentPhrases = phrases, sentimentNegations = negations,
            sectorRules = rules, sectorMin = sectorObj.getDouble("minFactor"), sectorMax = sectorObj.getDouble("maxFactor"),
            sectorDefault = sectorObj.optDouble("defaultFactor", 1.0),
            loadedAt = root.optLong("_loadedAt", System.currentTimeMillis())
        )
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun toStorageJson(l: Loaded): String {
        val root = JSONObject()
        root.put("factorKeys", JSONArray(l.factorKeys))
        val weightsObj = JSONObject()
        l.horizonWeights.forEach { (h, arr) -> weightsObj.put(h, JSONArray(arr.toList())) }
        root.put("horizonWeights", weightsObj)
        val sentimentObj = JSONObject()
        sentimentObj.put("phrases", JSONArray(l.sentimentPhrases.map { JSONObject().apply { put("phrase", it.first); put("weight", it.second) } }))
        sentimentObj.put("negations", JSONArray(l.sentimentNegations))
        root.put("sentiment", sentimentObj)
        val sectorObj = JSONObject()
        sectorObj.put("rules", JSONArray(l.sectorRules.map { JSONObject().apply { put("contains", JSONArray(it.first)); put("factor", it.second) } }))
        sectorObj.put("minFactor", l.sectorMin); sectorObj.put("maxFactor", l.sectorMax); sectorObj.put("defaultFactor", l.sectorDefault)
        root.put("sectorAllocation", sectorObj)
        root.put("_loadedAt", l.loadedAt)
        return root.toString()
    }
}

package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GrowthLocal-emergency: an optional, owner-loaded override for the
 * weights/sentiment-lexicon/sector-allocation data. Loaded via a file
 * picker in TOOLS > Admin Only, kept ONLY in this device's private storage
 * (filesDir — never backed up to the cloud by default, never bundled in
 * the APK, never synced to the server).
 *
 * As of build 357.1.81, the real horizon-weight arrays (OracleGrowthEngine)
 * and sector-multiplier table (OracleSectorAllocation) have been REMOVED
 * from the compiled app — this file (loaded here) is now the ONLY place
 * those values exist at all. The sentiment lexicon (OracleSentiment) was
 * deliberately NOT removed — News and Analysis need it unconditionally,
 * with no server fallback, so it still ships hardcoded; this object can
 * still *override* it if a file is loaded, but nothing breaks if it isn't.
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
    @Volatile private var forceLocalFlag: Boolean? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, "oracle_growth_emergency.json")
    private fun forceLocalFile(context: Context) = File(context.applicationContext.filesDir, "oracle_growth_force_local.flag")

    /** Testing aid — see the TOOLS "FORCE LOCAL MODE" toggle. When on,
     *  OracleGrowthEngine.run() skips tryServerPicks() entirely and always
     *  computes on-device, so a loaded (or edited) weights/sentiment/sector
     *  file can be tested end-to-end without touching the real server or
     *  waiting for an actual outage. Persisted to a small flag file so a
     *  test session survives an app restart without being silently lost —
     *  and so it's just as deliberately visible to turn back off. */
    fun isForcingLocal(context: Context): Boolean {
        forceLocalFlag?.let { return it }
        val v = forceLocalFile(context).exists()
        forceLocalFlag = v
        return v
    }

    fun setForceLocal(context: Context, on: Boolean) {
        forceLocalFlag = on
        val f = forceLocalFile(context)
        if (on) runCatching { f.writeText("1") } else runCatching { f.delete() }
        // The HARD FREEZE (OracleLocalProcessor) reuses today's snapshot
        // regardless of source — without clearing it, toggling this on would
        // silently keep showing whatever was already frozen (very possibly
        // server-sourced) instead of triggering a genuine local recompute.
        if (on) runCatching { OracleRepository(context).saveGrowth(emptyList()) }
    }

    fun isLoaded(context: Context): Boolean = current(context) != null

    fun loadedAt(context: Context): Long? = current(context)?.loadedAt

    /** The active horizon weights for the engine to use: the loaded override
     *  if one is cached, otherwise the caller's own built-in fallback. Does
     *  NOT take a Context — relies on `current(context)` having already run
     *  at least once this process (OracleGrowthEngine.runInternal calls it
     *  at the top of every run, which keeps this cheap and Context-free at
     *  the actual per-horizon call sites deep in the ranking code). */
    /** @param builtIn the caller's own fallback array, or null once that
     *  horizon's built-in weights have been deliberately removed from the
     *  compiled app (see the formula-protection work) — in which case this
     *  returns null only when no emergency file is loaded either, meaning
     *  the caller genuinely has nothing to rank with right now. */
    fun activeWeights(horizon: String, builtIn: IntArray?): IntArray? = cached?.horizonWeights?.get(horizon) ?: builtIn

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
        // ULTRA_SHORT is optional — a file with just the required three is
        // still valid, exactly as before. Only reject a truly wrong shape:
        // missing one of the three required horizons, or an unrecognized key.
        val requiredKeys = setOf("SHORT", "MEDIUM", "LONG")
        if (!parsed.horizonWeights.keys.containsAll(requiredKeys)) return null
        if (!(parsed.horizonWeights.keys - requiredKeys).all { it == "ULTRA_SHORT" }) return null
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
        // Same reasoning as setForceLocal(true): while actively testing
        // (force-local on), a cleared file should be reflected right away,
        // not hidden behind today's already-frozen snapshot. Outside a test
        // session this is skipped — a normal day's recommendations correctly
        // keep standing until tomorrow regardless of what happens to this file.
        if (isForcingLocal(context)) runCatching { OracleRepository(context).saveGrowth(emptyList()) }
    }

    private fun parse(text: String): Loaded {
        val root = JSONObject(text)
        val factorKeys = root.getJSONArray("factorKeys").toStringList()
        val weightsObj = root.getJSONObject("horizonWeights")
        val horizonWeights = mutableMapOf<String, IntArray>()
        for (h in listOf("SHORT", "MEDIUM", "LONG", "ULTRA_SHORT")) {
            if (!weightsObj.has(h)) continue
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

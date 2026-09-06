package ro.alintudor.luxoculi.core

import java.util.Locale

/** Phrase-level headline sentiment. Phrases (not single words), weighted,
 *  with negation handling — "cut costs" is not "cut guidance", and "no
 *  downgrade" is not a downgrade. Returns -1..+1 per headline. */
object OracleSentiment {
    private val phrases: List<Pair<String, Double>> = listOf(
        // strongly positive
        "beats estimates" to 1.0, "beat estimates" to 1.0, "beats expectations" to 1.0, "raises guidance" to 1.0, "raised guidance" to 1.0,
        "record revenue" to 0.9, "record profit" to 0.9, "record quarter" to 0.9, "upgraded to buy" to 0.9, "upgrades to buy" to 0.9,
        "price target raised" to 0.8, "raises price target" to 0.8, "strong demand" to 0.7, "surges" to 0.7, "soars" to 0.7, "jumps" to 0.5,
        "buyback" to 0.5, "dividend increase" to 0.5, "raises dividend" to 0.5, "fda approval" to 0.9, "wins contract" to 0.7, "new partnership" to 0.5,
        "upgrade" to 0.5, "outperform" to 0.5, "bullish" to 0.4, "rally" to 0.4, "all-time high" to 0.6, "better than expected" to 0.8,
        // strongly negative
        "misses estimates" to -1.0, "missed estimates" to -1.0, "misses expectations" to -1.0, "cuts guidance" to -1.0, "cut guidance" to -1.0, "lowers guidance" to -1.0,
        "downgraded to sell" to -0.9, "downgrades to sell" to -0.9, "price target cut" to -0.7, "cuts price target" to -0.7, "lawsuit" to -0.6, "investigation" to -0.6,
        "sec probe" to -0.8, "fraud" to -1.0, "bankruptcy" to -1.0, "recall" to -0.7, "layoffs" to -0.4, "plunges" to -0.8, "tumbles" to -0.7, "slumps" to -0.6, "falls" to -0.4,
        "downgrade" to -0.5, "underperform" to -0.5, "bearish" to -0.4, "dilution" to -0.7, "stock offering" to -0.6, "share offering" to -0.6, "worse than expected" to -0.8,
        "delay" to -0.4, "warning" to -0.5, "profit warning" to -0.9, "loses" to -0.4, "loss widens" to -0.7, "ceo resigns" to -0.5, "ceo steps down" to -0.4,
        // mildly positive/negative
        "growth" to 0.2, "profit" to 0.2, "expands" to 0.3, "launches" to 0.2, "decline" to -0.3, "weak" to -0.3, "cuts jobs" to -0.4, "shortfall" to -0.5
    )
    private val negations = listOf("no ", "not ", "denies ", "despite ", "without ", "avoids ", "isn't ", "won't ", "n't ")

    // Pushed by OracleGrowthEmergency when an owner-loaded file is active —
    // never set from anywhere else. Null means "use the built-in lexicon
    // above", which is the only path for every build until this is wired up.
    private var overridePhrases: List<Pair<String, Double>>? = null
    private var overrideNegations: List<String>? = null
    fun applyOverride(phrases: List<Pair<String, Double>>, negations: List<String>) { overridePhrases = phrases; overrideNegations = negations }
    fun clearOverride() { overridePhrases = null; overrideNegations = null }

    fun scoreOne(headline: String): Double {
        val activePhrases = overridePhrases ?: phrases
        val activeNegations = overrideNegations ?: negations
        val t = " " + headline.lowercase(Locale.US).replace(Regex("[^a-z0-9' -]"), " ").replace(Regex("\\s+"), " ") + " "
        var total = 0.0; var hits = 0
        for ((phrase, weight) in activePhrases) {
            var idx = t.indexOf(phrase)
            while (idx >= 0) {
                val before = t.substring(maxOf(0, idx - 12), idx)
                val negated = activeNegations.any { before.endsWith(it) || before.contains(" $it".trim() + " ") }
                total += if (negated) -weight * 0.6 else weight
                hits++
                idx = t.indexOf(phrase, idx + phrase.length)
            }
        }
        if (hits == 0) return 0.0
        return (total / hits).coerceIn(-1.0, 1.0)
    }

    /** Aggregate 0..100 for a set of headlines (50 = neutral), as newsContext expects. */
    fun score(headlines: List<String>): Int {
        if (headlines.isEmpty()) return 50
        val scored = headlines.map { scoreOne(it) }
        val avg = scored.average()
        // Confidence grows with the number of opinionated headlines.
        val conf = (scored.count { it != 0.0 } / 6.0).coerceIn(0.35, 1.0)
        return (50.0 + avg * 45.0 * conf).toInt().coerceIn(0, 100)
    }
}

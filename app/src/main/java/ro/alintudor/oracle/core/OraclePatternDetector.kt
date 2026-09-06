package ro.alintudor.oracle.core

/**
 * PATTERNoster: objective, geometry-based chart pattern detection — the 7
 * patterns that have actual measurable rules (peak/trough shape, level
 * clustering, trendline slope). Deliberately does NOT attempt Elliott Wave
 * or any pattern that depends on subjective labeling — those have no single
 * correct answer, so a formula claiming to find them would just be guessing
 * with extra steps.
 *
 * All thresholds below (similarity %, minimum pullback %, recency window)
 * are reasonable starting heuristics, not backtested constants — expect to
 * tune them once real tickers are run through this.
 */
data class OracleChartPattern(
    val type: String,
    val label: String,
    /** true=bullish, false=bearish, null=direction depends on the eventual breakout (e.g. a symmetric triangle). */
    val bullish: Boolean?,
    val fromIndex: Int,
    val toIndex: Int,
    val note: String,
    /** The specific points (index into the oldest-first candle list, price)
     *  that define this pattern — the two peaks of a Double Top, all three
     *  peaks + neckline points of a Head & Shoulders, the swing points of a
     *  Triangle's trendlines, etc. Ordered oldest-to-newest. Drawn as the
     *  visual "proof" on each pattern's chart snapshot. */
    val markers: List<Pair<Int, Double>>
)

data class OraclePatternSummary(val patterns: List<OracleChartPattern>, val verdict: String, val bullishCount: Int, val bearishCount: Int)

object OraclePatternDetector {
    private data class Swing(val index: Int, val price: Double, val isHigh: Boolean)

    private fun pctDiff(a: Double, b: Double): Double = kotlin.math.abs(a - b) / maxOf(a, b) * 100.0

    /** A candle is a swing high/low if it's the extreme within a small window
     *  on both sides — the standard "fractal" definition, nothing exotic. */
    private fun swings(candles: List<OracleOhlcvPoint>, window: Int = 3): List<Swing> {
        val out = mutableListOf<Swing>()
        for (i in candles.indices) {
            val lo = maxOf(0, i - window); val hi = minOf(candles.size - 1, i + window)
            if (i - lo < window || hi - i < window) continue
            if ((lo..hi).all { candles[it].high <= candles[i].high }) out += Swing(i, candles[i].high, true)
            if ((lo..hi).all { candles[it].low >= candles[i].low }) out += Swing(i, candles[i].low, false)
        }
        return out
    }

    private fun slope(points: List<Swing>): Double {
        if (points.size < 2) return 0.0
        val xs = points.indices.map { it.toDouble() }; val ys = points.map { it.price }
        val xMean = xs.average(); val yMean = ys.average()
        val num = xs.indices.sumOf { (xs[it] - xMean) * (ys[it] - yMean) }
        val den = xs.indices.sumOf { (xs[it] - xMean) * (xs[it] - xMean) }
        return if (den == 0.0) 0.0 else num / den
    }

    /** @param candlesAny any order — sorted internally to oldest-first, which every detector below assumes. */
    fun detect(candlesAny: List<OracleOhlcvPoint>): List<OracleChartPattern> {
        val candles = candlesAny.sortedBy { it.timestamp }
        if (candles.size < 20) return emptyList()
        val sw = swings(candles, 3)
        val highs = sw.filter { it.isHigh }
        val lows = sw.filter { !it.isHigh }
        return listOfNotNull(
            detectDoubleTop(candles, highs, lows),
            detectDoubleBottom(candles, highs, lows),
            detectHeadShoulders(candles, highs, lows),
            detectInverseHeadShoulders(candles, highs, lows),
            detectTriangle(candles, highs, lows),
            detectBreakout(candles),
            detectFlag(candles)
        )
    }

    fun summarize(patterns: List<OracleChartPattern>): OraclePatternSummary {
        val bullish = patterns.count { it.bullish == true }
        val bearish = patterns.count { it.bullish == false }
        val verdict = when {
            patterns.isEmpty() -> "No clear geometric pattern right now — price action doesn't match any of the 7 shapes."
            bullish > bearish -> "Bullish patterns dominate ($bullish vs $bearish)."
            bearish > bullish -> "Bearish patterns dominate ($bearish vs $bullish)."
            else -> "Mixed signals — bullish and bearish patterns both present."
        }
        return OraclePatternSummary(patterns, verdict, bullish, bearish)
    }

    // --- 1. Double Top — two similar-height peaks with a meaningful pullback between them ---
    private fun detectDoubleTop(candles: List<OracleOhlcvPoint>, highs: List<Swing>, lows: List<Swing>): OracleChartPattern? {
        if (highs.size < 2) return null
        val recent = highs.takeLast(5)
        for (i in 0 until recent.size - 1) {
            val h1 = recent[i]; val h2 = recent[i + 1]
            if (h2.index - h1.index < 5) continue
            if (pctDiff(h1.price, h2.price) > 3.0) continue
            val valley = lows.filter { it.index in h1.index..h2.index }.minByOrNull { it.price } ?: continue
            val dropPct = (minOf(h1.price, h2.price) - valley.price) / minOf(h1.price, h2.price) * 100.0
            if (dropPct < 3.0) continue
            if (candles.size - 1 - h2.index > 15) continue
            return OracleChartPattern("DOUBLE_TOP", "Double Top", false, h1.index, h2.index,
                "Two peaks near %.2f and %.2f, separated by a pullback to %.2f (%.1f%% drop). Bearish reversal signal.".format(h1.price, h2.price, valley.price, dropPct),
                listOf(h1.index to h1.price, valley.index to valley.price, h2.index to h2.price))
        }
        return null
    }

    // --- 2. Double Bottom — mirror of Double Top ---
    private fun detectDoubleBottom(candles: List<OracleOhlcvPoint>, highs: List<Swing>, lows: List<Swing>): OracleChartPattern? {
        if (lows.size < 2) return null
        val recent = lows.takeLast(5)
        for (i in 0 until recent.size - 1) {
            val l1 = recent[i]; val l2 = recent[i + 1]
            if (l2.index - l1.index < 5) continue
            if (pctDiff(l1.price, l2.price) > 3.0) continue
            val peak = highs.filter { it.index in l1.index..l2.index }.maxByOrNull { it.price } ?: continue
            val risePct = (peak.price - maxOf(l1.price, l2.price)) / maxOf(l1.price, l2.price) * 100.0
            if (risePct < 3.0) continue
            if (candles.size - 1 - l2.index > 15) continue
            return OracleChartPattern("DOUBLE_BOTTOM", "Double Bottom", true, l1.index, l2.index,
                "Two troughs near %.2f and %.2f, separated by a rally to %.2f (%.1f%% rise). Bullish reversal signal.".format(l1.price, l2.price, peak.price, risePct),
                listOf(l1.index to l1.price, peak.index to peak.price, l2.index to l2.price))
        }
        return null
    }

    // --- 3. Head & Shoulders — middle peak clearly above two roughly-level shoulders ---
    private fun detectHeadShoulders(candles: List<OracleOhlcvPoint>, highs: List<Swing>, lows: List<Swing>): OracleChartPattern? {
        if (highs.size < 3) return null
        val recent = highs.takeLast(6)
        for (i in 0 until recent.size - 2) {
            val ls = recent[i]; val head = recent[i + 1]; val rs = recent[i + 2]
            if (head.price <= ls.price * 1.02 || head.price <= rs.price * 1.02) continue
            if (pctDiff(ls.price, rs.price) > 6.0) continue
            val necklinePts = lows.filter { it.index in ls.index..rs.index }
            if (necklinePts.size < 2 || pctDiff(necklinePts.minOf { it.price }, necklinePts.maxOf { it.price }) > 5.0) continue
            if (candles.size - 1 - rs.index > 15) continue
            val neckAvg = necklinePts.map { it.price }.average()
            return OracleChartPattern("HEAD_SHOULDERS", "Head & Shoulders", false, ls.index, rs.index,
                "Head at %.2f clearly above both shoulders (%.2f, %.2f); neckline near %.2f. Bearish reversal signal.".format(head.price, ls.price, rs.price, neckAvg),
                (listOf(ls.index to ls.price, head.index to head.price, rs.index to rs.price) + necklinePts.map { it.index to it.price }).sortedBy { it.first })
        }
        return null
    }

    // --- 4. Inverse Head & Shoulders — mirror ---
    private fun detectInverseHeadShoulders(candles: List<OracleOhlcvPoint>, highs: List<Swing>, lows: List<Swing>): OracleChartPattern? {
        if (lows.size < 3) return null
        val recent = lows.takeLast(6)
        for (i in 0 until recent.size - 2) {
            val ls = recent[i]; val head = recent[i + 1]; val rs = recent[i + 2]
            if (head.price >= ls.price * 0.98 || head.price >= rs.price * 0.98) continue
            if (pctDiff(ls.price, rs.price) > 6.0) continue
            val necklinePts = highs.filter { it.index in ls.index..rs.index }
            if (necklinePts.size < 2 || pctDiff(necklinePts.minOf { it.price }, necklinePts.maxOf { it.price }) > 5.0) continue
            if (candles.size - 1 - rs.index > 15) continue
            val neckAvg = necklinePts.map { it.price }.average()
            return OracleChartPattern("INV_HEAD_SHOULDERS", "Inverse Head & Shoulders", true, ls.index, rs.index,
                "Head at %.2f clearly below both shoulders (%.2f, %.2f); neckline near %.2f. Bullish reversal signal.".format(head.price, ls.price, rs.price, neckAvg),
                (listOf(ls.index to ls.price, head.index to head.price, rs.index to rs.price) + necklinePts.map { it.index to it.price }).sortedBy { it.first })
        }
        return null
    }

    // --- 5. Triangle — recent swing highs/lows fit converging/flat trendlines ---
    private fun detectTriangle(candles: List<OracleOhlcvPoint>, highs: List<Swing>, lows: List<Swing>): OracleChartPattern? {
        val recentHighs = highs.takeLast(4); val recentLows = lows.takeLast(4)
        if (recentHighs.size < 3 || recentLows.size < 3) return null
        if (candles.size - 1 - maxOf(recentHighs.last().index, recentLows.last().index) > 15) return null
        val avgPrice = (recentHighs.map { it.price } + recentLows.map { it.price }).average()
        if (avgPrice <= 0.0) return null
        val hPct = slope(recentHighs) / avgPrice * 100.0
        val lPct = slope(recentLows) / avgPrice * 100.0
        val flat = 0.35
        val span = minOf(recentHighs.first().index, recentLows.first().index) to maxOf(recentHighs.last().index, recentLows.last().index)
        val allMarkers = (recentHighs + recentLows).sortedBy { it.index }.map { it.index to it.price }
        return when {
            kotlin.math.abs(hPct) < flat && lPct > flat -> OracleChartPattern("TRIANGLE_ASC", "Ascending Triangle", true, span.first, span.second,
                "Flat resistance near %.2f with rising support — typically resolves upward.".format(recentHighs.map { it.price }.average()), allMarkers)
            kotlin.math.abs(lPct) < flat && hPct < -flat -> OracleChartPattern("TRIANGLE_DESC", "Descending Triangle", false, span.first, span.second,
                "Flat support near %.2f with falling resistance — typically resolves downward.".format(recentLows.map { it.price }.average()), allMarkers)
            hPct < -flat && lPct > flat -> OracleChartPattern("TRIANGLE_SYM", "Symmetric Triangle", null, span.first, span.second,
                "Converging trendlines — the breakout direction is the signal to watch, not the triangle itself.", allMarkers)
            else -> null
        }
    }

    // --- 6. Support/Resistance Breakout — a level tested repeatedly, then decisively broken ---
    private fun detectBreakout(candles: List<OracleOhlcvPoint>): OracleChartPattern? {
        if (candles.size < 30) return null
        val recent = candles.takeLast(40)
        val baseIndex = candles.size - recent.size
        val last = recent.last()
        // Returns the clustered level, how many touches, and the LOCAL indices (into `recent`) that touched it.
        fun cluster(indexed: List<Pair<Int, Double>>): Triple<Double, Int, List<Int>>? {
            var bestValue = 0.0; var bestTouches = emptyList<Int>()
            for ((_, v) in indexed) {
                val touches = indexed.filter { kotlin.math.abs(it.second - v) / v * 100.0 < 1.0 }.map { it.first }
                if (touches.size >= 2 && touches.size > bestTouches.size) { bestValue = v; bestTouches = touches }
            }
            return if (bestTouches.size >= 2) Triple(bestValue, bestTouches.size, bestTouches) else null
        }
        val priorCount = recent.size - 3
        val resistance = cluster(recent.take(priorCount).mapIndexed { i, c -> i to c.high })
        val support = cluster(recent.take(priorCount).mapIndexed { i, c -> i to c.low })
        if (resistance != null && last.close > resistance.first * 1.005) {
            val markers = resistance.third.map { (baseIndex + it) to recent[it].high } + listOf((baseIndex + recent.size - 1) to last.close)
            return OracleChartPattern("BREAKOUT_RESISTANCE", "Resistance Breakout", true, baseIndex + recent.size - 4, baseIndex + recent.size - 1,
                "Price tested ~%.2f %d times, then closed above it at %.2f. Bullish breakout.".format(resistance.first, resistance.second, last.close), markers)
        }
        if (support != null && last.close < support.first * 0.995) {
            val markers = support.third.map { (baseIndex + it) to recent[it].low } + listOf((baseIndex + recent.size - 1) to last.close)
            return OracleChartPattern("BREAKOUT_SUPPORT", "Support Breakdown", false, baseIndex + recent.size - 4, baseIndex + recent.size - 1,
                "Price tested ~%.2f %d times, then closed below it at %.2f. Bearish breakdown.".format(support.first, support.second, last.close), markers)
        }
        return null
    }

    // --- 7. Flag/Pennant — a sharp "pole" move followed by a tight consolidation ---
    private fun detectFlag(candles: List<OracleOhlcvPoint>): OracleChartPattern? {
        // A real flag doesn't land on one exact candle count, so try a small
        // range of pole/flag window sizes and keep the tightest (most
        // convincing) match rather than requiring one rigid shape.
        var best: OracleChartPattern? = null
        var bestRatio = Double.MAX_VALUE
        for (poleWindow in 5..12) {
            for (flagWindow in 4..10) {
                val poleStart = candles.size - poleWindow - flagWindow
                if (poleStart < 0) continue
                val poleEndIndex = candles.size - flagWindow - 1
                val poleBegin = candles[poleStart].close
                val poleEnd = candles[poleEndIndex].close
                if (poleBegin <= 0.0) continue
                val poleMovePct = (poleEnd - poleBegin) / poleBegin * 100.0
                if (kotlin.math.abs(poleMovePct) < 8.0) continue
                val flagCandles = candles.takeLast(flagWindow)
                val flagHighPoint = flagCandles.maxByOrNull { it.high }!!
                val flagLowPoint = flagCandles.minByOrNull { it.low }!!
                val flagRangePct = (flagHighPoint.high - flagLowPoint.low) / poleEnd * 100.0
                val ratio = flagRangePct / kotlin.math.abs(poleMovePct)
                if (ratio > 0.5 || ratio >= bestRatio) continue
                bestRatio = ratio
                val bullish = poleMovePct > 0
                val flagHighIdx = candles.indexOf(flagHighPoint); val flagLowIdx = candles.indexOf(flagLowPoint)
                best = OracleChartPattern(if (bullish) "FLAG_BULLISH" else "FLAG_BEARISH", if (bullish) "Bull Flag" else "Bear Flag", bullish,
                    poleStart, candles.size - 1,
                    "Sharp %.1f%% move, then a tight %.1f%% consolidation — a %s continuation setup if it breaks the same direction.".format(poleMovePct, flagRangePct, if (bullish) "bullish" else "bearish"),
                    listOf(poleStart to poleBegin, poleEndIndex to poleEnd, flagHighIdx to flagHighPoint.high, flagLowIdx to flagLowPoint.low).sortedBy { it.first })
            }
        }
        return best
    }
}

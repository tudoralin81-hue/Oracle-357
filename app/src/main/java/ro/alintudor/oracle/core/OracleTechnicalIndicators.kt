package ro.alintudor.oracle.core

import kotlin.math.abs

/** Technical snapshot for a held position. Built from REAL daily candles
 *  (fromCandles) whenever the market data is reachable; the history-based
 *  fallback (forTicker) only covers a ticker whose candles could not be
 *  fetched and only once enough local quotes exist. Nothing is hardcoded. */
data class OracleTechnicalSnapshot(
    val ticker: String,
    val rsi: Double,
    val sma50: Double,
    val momentum5D: Double,
    val momentum20D: Double,
    val support20D: Double,
    val resistance20D: Double,
    val adx: Double? = null,
    val atr14: Double? = null,
    val sma200: Double? = null,
    // The same SHORT technical score Growth would give this ticker today —
    // so Portfolio's decisions and Growth's ranking speak one language.
    val techScore: Int? = null,
    val computedAt: Long = 0L
)

/** In-memory bridge between the refresh (which has real candles) and the UI
 *  helpers that only receive the sparse local history. Filled by
 *  OracleLocalProcessor on every refresh; read by OracleAnalytics and
 *  OracleTechnicalIndicators.all/forTicker so every screen sees the same,
 *  real numbers. */
object OracleTechnicalCache {
    @Volatile private var snapshots: Map<String, OracleTechnicalSnapshot> = emptyMap()
    @Volatile private var peaks: Map<String, Double> = emptyMap()
    fun put(snaps: Collection<OracleTechnicalSnapshot>, peakByTicker: Map<String, Double>) {
        snapshots = snaps.associateBy { it.ticker.uppercase() }
        peaks = peakByTicker.mapKeys { it.key.uppercase() }
    }
    fun snapshot(ticker: String): OracleTechnicalSnapshot? = snapshots[ticker.uppercase()]
    fun peak(ticker: String): Double? = peaks[ticker.uppercase()]
    fun isEmpty() = snapshots.isEmpty()
}

object OracleTechnicalIndicators {
    /** Real indicators from daily candles (ascending or descending order accepted). */
    fun fromCandles(ticker: String, candles: List<OracleOhlcvPoint>): OracleTechnicalSnapshot? {
        val d = candles.filter { it.close.isFinite() && it.close > 0.0 && it.high >= it.low }.sortedBy { it.timestamp }
        if (d.size < 30) return null
        val close = d.map { it.close }; val p = close.last()
        fun sma(n: Int) = if (close.size >= n) close.takeLast(n).average() else null
        fun mom(n: Int) = if (close.size > n) (p / close[close.size - 1 - n] - 1.0) * 100.0 else 0.0
        // Wilder RSI(14)
        val period = 14
        var gain = 0.0; var loss = 0.0
        for (i in 1..period) { val delta = close[i] - close[i - 1]; if (delta >= 0) gain += delta else loss -= delta }
        gain /= period; loss /= period
        for (i in period + 1 until close.size) { val delta = close[i] - close[i - 1]; gain = (gain * (period - 1) + maxOf(delta, 0.0)) / period; loss = (loss * (period - 1) + maxOf(-delta, 0.0)) / period }
        val rsi = if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + gain / loss)
        // ATR(14), Wilder
        val tr = (1 until d.size).map { i -> maxOf(d[i].high - d[i].low, abs(d[i].high - d[i - 1].close), abs(d[i].low - d[i - 1].close)) }
        var atr = tr.take(period).average()
        for (i in period until tr.size) atr = (atr * (period - 1) + tr[i]) / period
        val last20 = close.takeLast(20)
        return OracleTechnicalSnapshot(
            ticker = ticker.uppercase(), rsi = rsi.coerceIn(0.0, 100.0), sma50 = sma(50) ?: sma(close.size)!!,
            momentum5D = mom(5), momentum20D = mom(20), support20D = last20.min(), resistance20D = last20.max(),
            adx = adx14(d), atr14 = atr.takeIf { it.isFinite() && it > 0.0 }, sma200 = sma(200),
            techScore = OracleGrowthEngine.technicalScore(d), computedAt = System.currentTimeMillis()
        )
    }

    fun forTicker(ticker: String, history: List<OracleHistoryPoint>): OracleTechnicalSnapshot? {
        OracleTechnicalCache.snapshot(ticker)?.let { return it }
        val prices = history
            .filter { it.ticker.equals(ticker, true) && it.price.isFinite() && it.price > 0.0 }
            .sortedBy { it.timestamp }
            .map { it.price }
        // Never manufacture indicators from a handful of cached quotes — with
        // too little local history the honest answer is "no data" (the UI
        // shows N/A), not a fabricated RSI.
        val minimumReliableHistory = 20
        if (prices.size < minimumReliableHistory) return null
        fun momentum(lookback: Int): Double {
            if (prices.size <= lookback) return 0.0
            val base = prices[prices.size - lookback - 1]
            return if (base == 0.0) 0.0 else (prices.last() / base - 1.0) * 100.0
        }
        val window20 = prices.takeLast(20)
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        prices.takeLast(minOf(15, prices.size)).zipWithNext().forEach { (a, b) ->
            val delta = b - a
            if (delta >= 0) gains += delta else losses += -delta
        }
        val avgGain = if (gains.isEmpty()) 0.0 else gains.average()
        val avgLoss = if (losses.isEmpty()) 0.0 else losses.average()
        val rsi = when {
            avgLoss == 0.0 && avgGain > 0.0 -> 100.0
            avgGain == 0.0 -> 0.0
            else -> 100.0 - (100.0 / (1.0 + avgGain / avgLoss))
        }
        return OracleTechnicalSnapshot(
            ticker = ticker,
            rsi = rsi.coerceIn(0.0, 100.0),
            sma50 = prices.takeLast(minOf(50, prices.size)).average(),
            momentum5D = momentum(5),
            momentum20D = momentum(20),
            support20D = window20.minOrNull() ?: prices.last(),
            resistance20D = window20.maxOrNull() ?: prices.last()
        )
    }
    /** Wilder ADX(14), calculated from real OHLC candles. Returns null when history is insufficient. */
    fun adx14(candles: List<OracleOhlcvPoint>): Double? {
        val data = candles.filter {
            it.open.isFinite() && it.high.isFinite() && it.low.isFinite() && it.close.isFinite() &&
                it.high >= it.low && it.close > 0.0
        }.sortedBy { it.timestamp }
        val period = 14
        if (data.size < period * 2 + 1) return null

        val tr = ArrayList<Double>(data.size - 1)
        val plusDm = ArrayList<Double>(data.size - 1)
        val minusDm = ArrayList<Double>(data.size - 1)
        for (i in 1 until data.size) {
            val cur = data[i]
            val prev = data[i - 1]
            val upMove = cur.high - prev.high
            val downMove = prev.low - cur.low
            tr += maxOf(cur.high - cur.low, kotlin.math.abs(cur.high - prev.close), kotlin.math.abs(cur.low - prev.close))
            plusDm += if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDm += if (downMove > upMove && downMove > 0.0) downMove else 0.0
        }

        var smTr = tr.take(period).sum()
        var smPlus = plusDm.take(period).sum()
        var smMinus = minusDm.take(period).sum()
        val dx = ArrayList<Double>()

        fun appendDx() {
            if (smTr <= 0.0) return
            val plusDi = 100.0 * smPlus / smTr
            val minusDi = 100.0 * smMinus / smTr
            val denominator = plusDi + minusDi
            if (denominator > 0.0) dx += 100.0 * kotlin.math.abs(plusDi - minusDi) / denominator
        }

        appendDx()
        for (i in period until tr.size) {
            smTr = smTr - smTr / period + tr[i]
            smPlus = smPlus - smPlus / period + plusDm[i]
            smMinus = smMinus - smMinus / period + minusDm[i]
            appendDx()
        }
        if (dx.size < period) return null

        var adx = dx.take(period).average()
        for (i in period until dx.size) adx = (adx * (period - 1) + dx[i]) / period
        return adx.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    }

    fun all(history: List<OracleHistoryPoint>): Map<String, OracleTechnicalSnapshot> {
        val tickers = history.map { it.ticker.uppercase() }.distinct()
        return tickers.mapNotNull { ticker ->
            forTicker(ticker, history)?.let { ticker to it }
        }.toMap()
    }
}

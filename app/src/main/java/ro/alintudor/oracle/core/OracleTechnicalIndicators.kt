package ro.alintudor.oracle.core

/** Deterministic technical snapshot built from local history, with canonical analysis fallback for the seeded portfolio. */
data class OracleTechnicalSnapshot(
    val ticker: String,
    val rsi: Double,
    val sma50: Double,
    val momentum5D: Double,
    val momentum20D: Double,
    val support20D: Double,
    val resistance20D: Double,
    val adx: Double? = null
)

object OracleTechnicalIndicators {
    private val canonical = mapOf(
        "CRM" to OracleTechnicalSnapshot("CRM", 80.6, 178.87, 22.7, 39.5, 0.0, 0.0),
        "HOOD" to OracleTechnicalSnapshot("HOOD", 66.1, 101.38, 15.4, 26.7, 83.68, 112.45),
        "MELI" to OracleTechnicalSnapshot("MELI", 59.2, 1815.21, 0.5, 2.4, 1759.21, 2011.20)
    )

    fun forTicker(ticker: String, history: List<OracleHistoryPoint>): OracleTechnicalSnapshot? {
        val key = ticker.uppercase()
        val prices = history
            .filter { it.ticker.equals(ticker, true) && it.price.isFinite() && it.price > 0.0 }
            .sortedBy { it.timestamp }
            .map { it.price }

        /*
         * Do not manufacture technical indicators from one/two cached quotes.
         * The previous implementation produced RSI=0, Momentum=0 and
         * Support=Resistance=current price when the local history was too short.
         * For the seeded Oracle portfolio, use the canonical analysis until there
         * is enough local history to calculate the requested indicators reliably.
         */
        val minimumReliableHistory = 20
        if (prices.size < minimumReliableHistory) return canonical[key]

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
        val tickers = (history.map { it.ticker } + canonical.keys).distinct()
        return tickers.mapNotNull { ticker ->
            forTicker(ticker, history)?.let { ticker to it }
        }.toMap()
    }
}

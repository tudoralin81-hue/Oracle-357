package ro.alintudor.oracle.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ro.alintudor.oracle.core.OracleMarketData
import ro.alintudor.oracle.core.OracleOhlcvPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Real OHLCV Analysis chart. Visual hierarchy follows the Oracle Analysis reference design. */
class OracleAnalysisChartView(context: Context, private val ticker: String) : View(context) {
    private val bg = Color.rgb(2, 6, 12)
    private val grid = Color.rgb(25, 35, 50)
    private val text = Color.rgb(225, 232, 245)
    private val green = Color.rgb(45, 232, 92)
    private val red = Color.rgb(255, 72, 72)
    private val blue = Color.rgb(35, 175, 255)
    private val gold = Color.rgb(255, 195, 35)
    private val purple = Color.rgb(170, 105, 255)
    private val cyan = Color.rgb(60, 205, 255)
    private val paints = Paint(Paint.ANTI_ALIAS_FLAG)
    private var data: List<OracleOhlcvPoint> = emptyList()
    private var visible = 90
    private var offset = 0
    private var mode = "5M"
    private var showBB = true
    private var showMA = true
    private var showMACross = true
    private var showIchi = false
    private var showRSI = true
    private var showADX = true
    private var loading = true
    private var selectedIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var lastOffset = 0
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            visible = (visible / detector.scaleFactor).toInt().coerceIn(30, 180)
            clampOffset()
            selectedIndex = -1
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(bg)
        loadMode(mode)
    }

    private fun loadMode(requested: String) {
        loading = true
        invalidate()
        Thread {
            val fetched = runCatching { OracleMarketData.fetchForMode(ticker, requested) }.getOrDefault(emptyList())
            post {
                if (mode == requested) {
                    data = fetched
                    loading = false
                    clampOffset()
                    selectedIndex = -1
                    invalidate()
                }
            }
        }.start()
    }

    fun setMode(value: String) {
        val next = when (value) {
            "5M", "30M", "1H", "1D", "5D", "1M", "3M", "1Y" -> value
            else -> "5M"
        }
        if (next == mode && data.isNotEmpty()) return
        mode = next
        visible = 90
        offset = 0
        selectedIndex = -1
        loadMode(next)
    }

    fun toggleIndicator(name: String) {
        when (name) {
            "BB" -> showBB = !showBB
            "MA/EMA" -> showMA = !showMA
            "MA Cross" -> showMACross = !showMACross
            "ICHI" -> showIchi = !showIchi
            "RSI" -> showRSI = !showRSI
            "ADX" -> showADX = !showADX
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastOffset = offset
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && data.isNotEmpty()) {
                    val step = width.toFloat() / max(1, visible)
                    offset = (lastOffset + ((downX - event.x) / max(4f, step)).toInt())
                        .coerceIn(0, max(0, data.size - visible))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) < 18f && abs(event.y - downY) < 18f) selectCandle(event.x)
                return true
            }
        }
        return true
    }

    private fun selectCandle(x: Float) {
        if (data.isEmpty()) return
        val start = max(0, data.size - visible - offset)
        val end = min(data.size, start + visible)
        val count = end - start
        if (count <= 0) return
        val step = width.toFloat() / count
        selectedIndex = (x / step).toInt().coerceIn(0, count - 1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        paints.style = Paint.Style.FILL
        paints.color = bg
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints)
        if (loading) {
            label(c, "Loading live data for $ticker • $mode…", 18f, 38f, text, 16f)
            return
        }
        if (data.size < 10) {
            label(c, "Not enough OHLCV data for $ticker • $mode.", 18f, 38f, red, 16f)
            return
        }

        val start = max(0, data.size - visible - offset)
        val end = min(data.size, start + visible)
        val d = data.subList(start, end)
        val chartBottom = height * 0.66f
        val volumeTop = chartBottom + 12f
        val volumeHeight = height * 0.15f
        val oscTop = volumeTop + volumeHeight + 12f
        val oscHeight = height - oscTop - 10f

        drawGrid(c, 0f, 132f, chartBottom, 6)
        drawCandles(c, d, 12f, 132f, width - 48f, chartBottom)
        drawVolume(c, d, volumeTop, volumeHeight)
        if (showRSI) drawOscillator(c, d, oscTop, oscHeight * 0.48f, false)
        if (showADX) drawOscillator(c, d, oscTop + oscHeight * 0.52f, oscHeight * 0.44f, true)
        drawHeader(c, d)
    }

    private fun drawHeader(c: Canvas, d: List<OracleOhlcvPoint>) {
        // Canvas text is in physical pixels; enlarge the chart header substantially for high-density phones.
        label(c, "$ticker  •  $mode", 14f, 62f, Color.WHITE, 60f)
        if (d.isNotEmpty()) {
            paints.textAlign = Paint.Align.RIGHT
            label(c, money(d.last().close), width - 14f, 62f, green, 60f)
            paints.textAlign = Paint.Align.LEFT
        }
        if (selectedIndex in d.indices) {
            val p = d[selectedIndex]
            label(c, "O ${money(p.open)}  H ${money(p.high)}  L ${money(p.low)}  C ${money(p.close)}", 14f, 96f, if (p.close >= p.open) green else red, 30f)
            label(c, "${dateTime(p.timestamp)}  •  VOL ${volumeText(p.volume)}", 14f, 128f, text, 30f)
        } else {
            label(c, "Tap a candle for OHLC + date/time + volume", 14f, 96f, text, 28f)
        }
    }

    private fun drawGrid(c: Canvas, left: Float, top: Float, bottom: Float, rows: Int) {
        paints.strokeWidth = 1f
        paints.color = grid
        for (i in 0..rows) {
            val y = top + (bottom - top) * i / rows
            c.drawLine(left, y, width.toFloat() - 42f, y, paints)
        }
        for (i in 0..8) {
            val x = width * i / 8f
            c.drawLine(x, top, x, bottom, paints)
        }
    }

    private fun drawCandles(c: Canvas, d: List<OracleOhlcvPoint>, left: Float, top: Float, right: Float, bottom: Float) {
        val minP = d.minOf { it.low }
        val maxP = d.maxOf { it.high }
        val span = max(0.0001, maxP - minP)
        val step = (right - left) / max(1, d.size)
        val bodyW = max(3f, step * .62f)
        fun y(v: Double): Float = bottom - ((v - minP) / span * (bottom - top - 22f)).toFloat()
        val closes = d.map { it.close }

        if (selectedIndex in d.indices) {
            val p = d[selectedIndex]
            label(c, "O ${money(p.open)}  H ${money(p.high)}  L ${money(p.low)}  C ${money(p.close)}", 14f, 96f, if (p.close >= p.open) green else red, 30f)
            label(c, "${dateTime(p.timestamp)}  •  VOL ${volumeText(p.volume)}", 14f, 128f, text, 30f)
        } else {
            label(c, "Tap a candle for OHLC + date/time + volume", 14f, 96f, text, 28f)
        }
    }

    private fun drawGrid(c: Canvas, left: Float, top: Float, bottom: Float, rows: Int) {
        paints.strokeWidth = 1f
        paints.color = grid
        for (i in 0..rows) {
            val y = top + (bottom - top) * i / rows
            c.drawLine(left, y, width.toFloat() - 42f, y, paints)
        }
        for (i in 0..8) {
            val x = width * i / 8f
            c.drawLine(x, top, x, bottom, paints)
        }
    }

    private fun drawCandles(c: Canvas, d: List<OracleOhlcvPoint>, left: Float, top: Float, right: Float, bottom: Float) {
        val minP = d.minOf { it.low }
        val maxP = d.maxOf { it.high }
        val span = max(0.0001, maxP - minP)
        val step = (right - left) / max(1, d.size)
        val bodyW = max(3f, step * .62f)
        fun y(v: Double): Float = bottom - ((v - minP) / span * (bottom - top - 22f)).toFloat()
        val closes = d.map { it.close }


        if (showBB) {
            val upper = mutableListOf<Float>(); val mid = mutableListOf<Float>(); val lower = mutableListOf<Float>()
            d.indices.forEach { i ->
                val a = closes.subList(max(0, i - 19), i + 1)
                val m = a.average()
                val sd = sqrt(a.sumOf { (it - m) * (it - m) } / a.size)
                upper += y(m + 2 * sd); mid += y(m); lower += y(m - 2 * sd)
            }
            val cloud = Path()
            upper.forEachIndexed { i, yy ->
                val x = left + (i + .5f) * step
                if (i == 0) cloud.moveTo(x, yy) else cloud.lineTo(x, yy)
            }
            for (i in lower.indices.reversed()) {
                val x = left + (i + .5f) * step
                cloud.lineTo(x, lower[i])
            }
            cloud.close()
            paints.style = Paint.Style.FILL
            paints.color = Color.argb(24, blue.red(), blue.green(), blue.blue())
            c.drawPath(cloud, paints)
            lineSeries(c, upper, blue, step, left, 2.6f)
            lineSeries(c, mid, Color.rgb(85, 110, 160), step, left, 1.7f)
            lineSeries(c, lower, blue, step, left, 2.6f)
        }
        if (showMA) {
            lineSeries(c, moving(closes, 10).map { y(it) }, gold, step, left, 2.8f)
            lineSeries(c, ema(closes, 10).map { y(it) }, purple, step, left, 2.8f)
        }
        if (showIchi) {
            val tenkan = mutableListOf<Float>(); val kijun = mutableListOf<Float>(); val spanA = mutableListOf<Float>(); val spanB = mutableListOf<Float>()
            d.indices.forEach { i ->
                val h9 = d.subList(max(0, i - 8), i + 1).maxOf { it.high }; val l9 = d.subList(max(0, i - 8), i + 1).minOf { it.low }
                val h26 = d.subList(max(0, i - 25), i + 1).maxOf { it.high }; val l26 = d.subList(max(0, i - 25), i + 1).minOf { it.low }
                val h52 = d.subList(max(0, i - 51), i + 1).maxOf { it.high }; val l52 = d.subList(max(0, i - 51), i + 1).minOf { it.low }
                val t = (h9 + l9) / 2; val k = (h26 + l26) / 2
                tenkan += y(t); kijun += y(k); spanA += y((t + k) / 2); spanB += y((h52 + l52) / 2)
            }
            lineSeries(c, tenkan, red, step, left, 2.2f); lineSeries(c, kijun, blue, step, left, 2.2f)
            lineSeries(c, spanA, green, step, left, 1.9f); lineSeries(c, spanB, purple, step, left, 1.9f)
        }

        d.forEachIndexed { i, p ->
            val x = left + (i + .5f) * step
            val yo = y(p.open); val yc = y(p.close); val yh = y(p.high); val yl = y(p.low)
            paints.color = if (p.close >= p.open) green else red
            paints.strokeWidth = 2.2f
            c.drawLine(x, yh, x, yl, paints)
            c.drawRect(x - bodyW / 2, min(yo, yc), x + bodyW / 2, max(yo, yc).coerceAtLeast(min(yo, yc) + 2f), paints)
        }

        drawTrendAnalysis(c, d, left, top, right, bottom, minP, maxP, span, step)

        if (selectedIndex in d.indices) {
            val x = left + (selectedIndex + .5f) * step
            paints.color = Color.argb(210, 255, 255, 255); paints.strokeWidth = 2f
            c.drawLine(x, top, x, bottom, paints)
        }
    }

    private fun drawTrendAnalysis(c: Canvas, d: List<OracleOhlcvPoint>, left: Float, top: Float, right: Float, bottom: Float, minP: Double, maxP: Double, span: Double, step: Float) {
        if (d.size < 12) return
        fun y(v: Double): Float = bottom - ((v - minP) / span * (bottom - top - 22f)).toFloat()
        val n = min(28, d.size)
        val start = d.size - n
        val first = d[start].close
        val last = d.last().close
        val slope = (last - first) / max(1, n - 1)
        val channelWidth = max(span * .045, abs(slope) * n * .65)
        val y1 = y(first); val y2 = y(last)
        paints.style = Paint.Style.STROKE
        paints.strokeCap = Paint.Cap.ROUND
        paints.strokeJoin = Paint.Join.ROUND
        paints.strokeWidth = 4.0f
        paints.color = if (slope >= 0) green else red
        c.drawLine(left + start * step, y1, right - 5f, y2, paints)
        paints.strokeWidth = 3.0f
        paints.color = Color.argb(150, if (slope >= 0) 65 else 255, if (slope >= 0) 220 else 75, if (slope >= 0) 110 else 75)
        c.drawLine(left + start * step, y(y1Value(first, channelWidth, slope >= 0)), right - 5f, y(y2Value(last, channelWidth, slope >= 0)), paints)

        val recentLow = d.takeLast(min(35, d.size)).minOf { it.low }
        val recentHigh = d.takeLast(min(35, d.size)).maxOf { it.high }
        paints.strokeWidth = 3.5f; paints.color = blue
        c.drawLine(left + 4f, y(recentLow), right - 5f, y(recentLow), paints)
        paints.color = gold
        c.drawLine(left + 4f, y(recentHigh), right - 5f, y(recentHigh), paints)

        if (slope > 0) drawArrow(c, right - 150f, y(last + channelWidth * .15), right - 28f, y(last + channelWidth * .75), green)
        else drawArrow(c, right - 150f, y(last - channelWidth * .15), right - 28f, y(last - channelWidth * .75), red)

        if (showMACross) {
            val fast = moving(d.map { it.close }, 10); val slow = moving(d.map { it.close }, 20)
            for (i in 1 until d.size) {
                val was = fast[i - 1] - slow[i - 1]; val now = fast[i] - slow[i]
                if (was <= 0 && now > 0) drawCross(c, left + (i + .5f) * step, y(d[i].close), green)
                if (was >= 0 && now < 0) drawCross(c, left + (i + .5f) * step, y(d[i].close), red)
            }
        }
        paints.strokeCap = Paint.Cap.BUTT
        paints.strokeJoin = Paint.Join.MITER
        paints.style = Paint.Style.FILL
    }

    private fun drawCross(c: Canvas, x: Float, y: Float, color: Int) {
        paints.style = Paint.Style.STROKE
        paints.strokeWidth = 4.5f
        paints.strokeCap = Paint.Cap.ROUND
        paints.color = color
        val s = 11f
        c.drawLine(x - s, y - s, x + s, y + s, paints)
        c.drawLine(x + s, y - s, x - s, y + s, paints)
        paints.style = Paint.Style.FILL
    }

    private fun y1Value(first: Double, width: Double, up: Boolean) = if (up) first + width else first - width
    private fun y2Value(last: Double, width: Double, up: Boolean) = if (up) last + width else last - width

    private fun drawArrow(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        paints.style = Paint.Style.STROKE
        paints.strokeWidth = 6.0f
        paints.strokeCap = Paint.Cap.ROUND
        paints.strokeJoin = Paint.Join.ROUND
        paints.color = color
        c.drawLine(x1, y1, x2, y2, paints)
        val dx = x2 - x1; val dy = y2 - y1; val len = max(1f, sqrt(dx * dx + dy * dy)); val ux = dx / len; val uy = dy / len
        val px = -uy; val py = ux; val head = 32f
        val path = Path()
        path.moveTo(x2, y2)
        path.lineTo(x2 - ux * head + px * 16f, y2 - uy * head + py * 16f)
        path.moveTo(x2, y2)
        path.lineTo(x2 - ux * head - px * 16f, y2 - uy * head - py * 16f)
        c.drawPath(path, paints)
        paints.strokeCap = Paint.Cap.BUTT
        paints.style = Paint.Style.FILL
    }

    private fun drawVolume(c: Canvas, d: List<OracleOhlcvPoint>, top: Float, height: Float) {
        val maxV = max(1.0, d.maxOf { it.volume })
        val step = width.toFloat() / max(1, d.size)
        label(c, "VOLUME", 12f, top + 18f, text, 15f)
        d.forEachIndexed { i, p ->
            val x = (i + .5f) * step
            val h = ((p.volume / maxV) * (height - 24f)).toFloat()
            paints.color = if (p.close >= p.open) Color.rgb(30, 175, 70) else Color.rgb(180, 55, 55)
            paints.style = Paint.Style.FILL
            c.drawRect(x - max(2f, step * .30f), top + height - h, x + max(2f, step * .30f), top + height, paints)
        }
        if (selectedIndex in d.indices) label(c, "VOL ${volumeText(d[selectedIndex].volume)}", width - 130f, top + 18f, cyan, 15f)
    }

    private fun drawOscillator(c: Canvas, d: List<OracleOhlcvPoint>, top: Float, height: Float, adx: Boolean) {
        if (height < 30f) return
        paints.style = Paint.Style.STROKE; paints.strokeWidth = 1f; paints.color = grid
        c.drawRect(0f, top, width.toFloat() - 42f, top + height, paints)
        val values = if (adx) d.map { 20.0 + (it.close - it.low) / max(.0001, it.high - it.low) * 35.0 } else rsi(d.map { it.close })
        val minV = 0.0; val maxV = 100.0
        val step = width.toFloat() / max(1, values.size)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = (i + .5f) * step; val y = top + height - ((v.coerceIn(minV, maxV) - minV) / (maxV - minV) * height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        paints.color = if (adx) gold else purple; paints.strokeWidth = 2.8f; c.drawPath(path, paints)
        label(c, if (adx) "ADX" else "RSI", 10f, top + 18f, text, 14f)
    }

    private fun rsi(closes: List<Double>): List<Double> {
        if (closes.isEmpty()) return emptyList()
        val out = MutableList(closes.size) { 50.0 }
        for (i in 1 until closes.size) {
            val a = closes.subList(max(0, i - 13), i + 1)
            var gains = 0.0; var losses = 0.0
            for (j in 1 until a.size) { val d = a[j] - a[j - 1]; if (d >= 0) gains += d else losses -= d }
            out[i] = if (losses == 0.0) 100.0 else 100.0 - (100.0 / (1.0 + gains / losses))
        }
        return out
    }

    private fun moving(values: List<Double>, n: Int): List<Double> = values.mapIndexed { i, _ -> values.subList(max(0, i - n + 1), i + 1).average() }

    private fun ema(values: List<Double>, n: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val k = 2.0 / (n + 1); val out = MutableList(values.size) { values.first() }
        for (i in 1 until values.size) out[i] = values[i] * k + out[i - 1] * (1 - k)
        return out
    }

    private fun lineSeries(c: Canvas, values: List<Float>, color: Int, step: Float, left: Float, width: Float) {
        if (values.isEmpty()) return
        paints.style = Paint.Style.STROKE; paints.strokeWidth = width; paints.strokeCap = Paint.Cap.ROUND; paints.color = color
        val path = Path(); values.forEachIndexed { i, y -> val x = left + (i + .5f) * step; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; c.drawPath(path, paints); paints.strokeCap = Paint.Cap.BUTT
    }

    private fun clampOffset() { offset = offset.coerceIn(0, max(0, data.size - visible)) }
    private fun label(c: Canvas, value: String, x: Float, y: Float, color: Int, size: Float) { paints.style = Paint.Style.FILL; paints.typeface = Typeface.DEFAULT_BOLD; paints.color = color; paints.textSize = size; paints.isFakeBoldText = true; c.drawText(value, x, y, paints) }
    private fun money(v: Double) = "%.2f".format(Locale.US, v)
    private fun volumeText(v: Double) = when { v >= 1_000_000_000 -> "%.2fB".format(Locale.US, v / 1e9); v >= 1_000_000 -> "%.2fM".format(Locale.US, v / 1e6); v >= 1_000 -> "%.1fK".format(Locale.US, v / 1e3); else -> "%.0f".format(Locale.US, v) }
    private fun dateTime(ts: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US).format(Date(ts))
}

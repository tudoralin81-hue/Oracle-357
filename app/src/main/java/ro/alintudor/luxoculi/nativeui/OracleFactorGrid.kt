package ro.alintudor.luxoculi.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one 18-cell parameter grid both Growth and Analysis draw, so the two
 * screens share a single visual language for "the evidence": 17 named
 * factors as 5-segment bars in a 6-column grid, plus the LO cell.
 *
 * Growth passes the horizon WEIGHTS (bars relative to the strongest weight,
 * title "Weights"); Analysis passes the ticker's factor VALUES (bars
 * absolute on 0..100, title "Factor scores"). Same layout, same names, same
 * colors, same LO treatment — only the numbers and the title differ.
 */
object OracleFactorGrid {
    val NAMES = listOf("News", "BO", "Trend", "Mom", "Vol", "S/R", "Fund", "BB", "Ichimoku", "Mkt", "R/R", "ADX",
        "RelStr", "VolReg", "52wPos", "OBV", "Crowd")

    private val muted = Color.rgb(165, 174, 195)
    private val white = Color.WHITE
    private val green = Color.rgb(105, 245, 35)
    private val loadingColor = Color.rgb(90, 205, 255)

    private fun label(host: OracleNativeModule, value: String, size: Float, face: Typeface, color: Int, bottom: Int): TextView =
        TextView(host.root.context).apply { text = value; textSize = size; typeface = face; setTextColor(color); setPadding(0, 0, 0, host.dp(bottom)) }

    fun add(host: OracleNativeModule, parent: LinearLayout, title: String, values: List<Int>, maxValue: Int, lo: Int, silent: Boolean = false) {
        if (values.isEmpty()) return
        parent.addView(label(host, title, 10f, Typeface.DEFAULT_BOLD, white, 5))
        val safeMax = maxValue.coerceAtLeast(1)
        val grid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, host.dp(2), 0, host.dp(1)) }
        val columns = 6
        // LO is one more cell after the 17 factors — a real, per-ticker
        // \u00b13 adjustment now (volatility-derived, see oracle_lo_from_volatility
        // server-side), not a random draw, so its bar reflects the actual
        // value like every other cell here instead of a fixed animation.
        val cellCount = values.size + 1
        val rows = (cellCount + columns - 1) / columns
        for (r in 0 until rows) {
            val row = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            for (c in 0 until columns) {
                val i = r * columns + c
                val cell = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(host.dp(2), host.dp(2), host.dp(2), host.dp(2)) }
                when {
                    i < values.size -> {
                        cell.addView(label(host, NAMES.getOrElse(i) { "?" }, 8f, Typeface.DEFAULT, muted, 0))
                        bar(host, cell, values[i], safeMax, silent)
                    }
                    i == values.size -> {
                        cell.addView(label(host, "LO", 8f, Typeface.DEFAULT, muted, 0))
                        loCell(host, cell, lo, silent)
                    }
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            grid.addView(row)
        }
        parent.addView(grid)
    }

    /** Runs a 6-step sweep (step 0 = nothing lit, step 5 = everything lit)
     *  three times, each cycle faster than the last — an accelerating
     *  "computing…" tell — then calls [onSettle] once to land on the real,
     *  final look. Deferred to [anchor]'s own post(): starting an animation
     *  before the view is actually attached to the window can silently skip
     *  its visible frames, which read as "sits still, then a refresh
     *  happens, then it animates" — exactly what starting it inline used to
     *  do here. */
    private fun sweepThenSettle(anchor: View, silent: Boolean = false, onOff: () -> Unit, onStep: (step: Int) -> Unit, onSettle: () -> Unit) {
        if (silent) { onSettle(); return }
        onOff()
        anchor.post {
            val handler = Handler(Looper.getMainLooper())
            val cycleDelays = longArrayOf(95L, 65L, 40L) // accelerating: each cycle faster than the last
            var cycle = 0
            fun runCycle() {
                if (cycle >= cycleDelays.size) { onSettle(); return }
                val delay = cycleDelays[cycle]
                var step = 0
                fun advance() {
                    if (step > 5) {
                        onOff()
                        cycle++
                        handler.postDelayed({ runCycle() }, delay)
                        return
                    }
                    onStep(step)
                    step++
                    handler.postDelayed(::advance, delay)
                }
                advance()
            }
            runCycle()
        }
    }

    /** 5-segment bar filled proportionally to value/maxValue; colored by 5
     *  shades of green (see greenShades below — dim for a weak factor,
     *  vivid for a strong one). The real, final segments are computed up
     *  front — the sweep never guesses, it just delays revealing them
     *  behind three accelerating light-chases. */
    // 5 shades of green, indexed by how many of the bar's 5 segments are lit
    // — a weak factor is a dim, muted green, a strong one the same vivid
    // green used everywhere else in the app. No red/orange at all: a low
    // factor score isn't a warning the way a critical alert is, it's just
    // one input among 17, and the red/orange heat-map read as more alarming
    // than that.
    private val greenShades = listOf(
        Color.rgb(40, 82, 38), Color.rgb(60, 130, 48), Color.rgb(80, 175, 55),
        Color.rgb(95, 210, 48), Color.rgb(105, 245, 35)
    )

    private fun bar(host: OracleNativeModule, parent: LinearLayout, value: Int, maxValue: Int, silent: Boolean = false) {
        val pct = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        val filledSegments = kotlin.math.round(pct * 5f).toInt().coerceIn(if (value > 0) 1 else 0, 5)
        val finalColor = greenShades[(filledSegments - 1).coerceIn(0, 4)]
        val bar = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(3), 0, 0) }
        val segments = (0 until 5).map { seg ->
            View(host.root.context).also { bar.addView(it, LinearLayout.LayoutParams(host.dp(7), host.dp(9)).apply { if (seg < 4) marginEnd = host.dp(2) }) }
        }
        fun paint(seg: View, on: Boolean, color: Int) {
            seg.background = GradientDrawable().apply {
                setColor(if (on) color else Color.TRANSPARENT)
                cornerRadius = host.dp(1).toFloat()
                if (!on) setStroke(host.dp(1), Color.rgb(60, 68, 84))
            }
        }
        sweepThenSettle(
            anchor = bar, silent = silent,
            onOff = { segments.forEach { paint(it, false, loadingColor) } },
            onStep = { step -> segments.forEachIndexed { i, seg -> paint(seg, i < step, loadingColor) } },
            onSettle = { segments.forEachIndexed { i, seg -> paint(seg, i < filledSegments, finalColor) } }
        )
        parent.addView(bar)
    }

    /** LO: identical on every card — a symmetric band with a marked centre —
     *  showing the random nudge is in play, never its value or sign. Same
     *  accelerating sweep as every other cell, in LO's own purple tones,
     *  settling on the fixed centre-lit band. */
    private fun loCell(host: OracleNativeModule, parent: LinearLayout, lo: Int, silent: Boolean = false) {
        val loColor = Color.rgb(190, 150, 255)
        val filledSegments = kotlin.math.round((lo.coerceIn(-3, 3) + 3) * 5.0 / 6.0).toInt().coerceIn(0, 5)
        val bar = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(3), 0, 0) }
        val segments = (0 until 5).map {
            View(host.root.context).also { v -> bar.addView(v, LinearLayout.LayoutParams(host.dp(7), host.dp(6)).apply { setMargins(host.dp(1), 0, host.dp(1), 0) }) }
        }
        fun paintOff(seg: View) { seg.background = OracleNativeModule.rounded(Color.rgb(58, 48, 82), host.dp(1), loColor, host.dp(1)); seg.alpha = 0.35f }
        fun paintOn(seg: View) { seg.background = OracleNativeModule.rounded(loadingColor, host.dp(1), loadingColor, 0); seg.alpha = 0.9f }
        sweepThenSettle(
            anchor = bar, silent = silent,
            onOff = { segments.forEach { paintOff(it) } },
            onStep = { step -> segments.forEachIndexed { i, seg -> if (i < step) paintOn(seg) else paintOff(seg) } },
            onSettle = {
                segments.forEachIndexed { i, seg ->
                    val lit = i < filledSegments
                    seg.background = OracleNativeModule.rounded(if (lit) loColor else Color.rgb(58, 48, 82), host.dp(1), loColor, if (lit) 0 else host.dp(1))
                    seg.alpha = if (lit) 0.95f else 0.55f
                }
            }
        )
        parent.addView(bar)
    }
}

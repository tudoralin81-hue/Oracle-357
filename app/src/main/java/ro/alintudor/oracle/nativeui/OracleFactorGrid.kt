package ro.alintudor.oracle.nativeui

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
    private val orange = Color.rgb(255, 160, 25)
    private val red = Color.rgb(255, 80, 90)
    private val loadingColor = Color.rgb(90, 205, 255)

    private fun label(host: OracleNativeModule, value: String, size: Float, face: Typeface, color: Int, bottom: Int): TextView =
        TextView(host.root.context).apply { text = value; textSize = size; typeface = face; setTextColor(color); setPadding(0, 0, 0, host.dp(bottom)) }

    fun add(host: OracleNativeModule, parent: LinearLayout, title: String, values: List<Int>, maxValue: Int) {
        if (values.isEmpty()) return
        parent.addView(label(host, title, 10f, Typeface.DEFAULT_BOLD, white, 5))
        val safeMax = maxValue.coerceAtLeast(1)
        val grid = LinearLayout(host.root.context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, host.dp(2), 0, host.dp(1)) }
        val columns = 6
        // LO (Lucky Oracle) is one more cell after the factors. It is not a
        // factor value or weight but the random nudge on the final score, so it
        // gets a constant rendering that shows it is in play without
        // disclosing the draw (see loCell).
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
                        bar(host, cell, values[i], safeMax)
                    }
                    i == values.size -> {
                        cell.addView(label(host, "LO", 8f, Typeface.DEFAULT, muted, 0))
                        loCell(host, cell)
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
    private fun sweepThenSettle(anchor: View, onOff: () -> Unit, onStep: (step: Int) -> Unit, onSettle: () -> Unit) {
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

    /** 5-segment bar filled proportionally to value/maxValue; colored by that
     *  same proportion (green = high, orange = middling, red = low). The
     *  real, final segments are computed up front — the sweep never guesses,
     *  it just delays revealing them behind three accelerating light-chases. */
    private fun bar(host: OracleNativeModule, parent: LinearLayout, value: Int, maxValue: Int) {
        val pct = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        val filledSegments = kotlin.math.round(pct * 5f).toInt().coerceIn(if (value > 0) 1 else 0, 5)
        val finalColor = when { pct >= 0.6f -> green; pct >= 0.3f -> orange; else -> red }
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
            anchor = bar,
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
    private fun loCell(host: OracleNativeModule, parent: LinearLayout) {
        val loColor = Color.rgb(190, 150, 255)
        val bar = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, host.dp(3), 0, 0) }
        val segments = (0 until 5).map {
            View(host.root.context).also { v -> bar.addView(v, LinearLayout.LayoutParams(host.dp(7), host.dp(6)).apply { setMargins(host.dp(1), 0, host.dp(1), 0) }) }
        }
        fun paintOff(seg: View) { seg.background = OracleNativeModule.rounded(Color.rgb(58, 48, 82), host.dp(1), loColor, host.dp(1)); seg.alpha = 0.35f }
        fun paintOn(seg: View) { seg.background = OracleNativeModule.rounded(loadingColor, host.dp(1), loadingColor, 0); seg.alpha = 0.9f }
        sweepThenSettle(
            anchor = bar,
            onOff = { segments.forEach { paintOff(it) } },
            onStep = { step -> segments.forEachIndexed { i, seg -> if (i < step) paintOn(seg) else paintOff(seg) } },
            onSettle = {
                segments.forEachIndexed { i, seg ->
                    val centre = i == 2
                    seg.background = OracleNativeModule.rounded(if (centre) loColor else Color.rgb(58, 48, 82), host.dp(1), loColor, if (centre) 0 else host.dp(1))
                    seg.alpha = if (centre) 0.95f else 0.55f
                }
            }
        )
        parent.addView(bar)
    }
}

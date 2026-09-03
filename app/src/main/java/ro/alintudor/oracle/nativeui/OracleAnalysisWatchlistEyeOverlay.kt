package ro.alintudor.oracle.nativeui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ro.alintudor.oracle.core.OracleWatchlistStore
import java.util.Locale

/**
 * Analysis-only Watchlist control.
 *
 * - Adds the eye only to Analysis result cards.
 * - Does not add any eye/star to Watchlist.
 * - Also hardens Watchlist row navigation from the Analysis host without changing
 *   the Watchlist visual layout.
 */
object OracleAnalysisWatchlistEyeOverlay {
    private const val EYE_TAG = "oracle_watchlist_eye_v2"
    private const val BRIDGE_TAG = "oracle_watchlist_bridge_v2"
    private val tickerPattern = Regex("^[A-Z][A-Z0-9.-]{0,7}$")

    fun install(host: OracleNativeModule) {
        fun scanAnalysis() {
            val content = host.content
            for (i in 0 until content.childCount) {
                val card = content.getChildAt(i) as? ViewGroup ?: continue
                if (!containsSectorLabel(card)) continue
                val tickerView = findTickerView(card) ?: continue
                val ticker = tickerView.text?.toString()?.trim()?.uppercase(Locale.US) ?: continue
                if (!tickerPattern.matches(ticker)) continue
                val headline = tickerView.parent as? ViewGroup ?: continue
                if (headline.findViewWithTag<View>(EYE_TAG) != null) continue

                val store = OracleWatchlistStore(host.root.context)
                val eye = EyeView(host).apply {
                    tag = EYE_TAG
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Add $ticker to Watchlist"
                    refresh(store.load().any { it.equals(ticker, true) })
                    setOnClickListener {
                        val current = store.load().toMutableList()
                        val present = current.any { it.equals(ticker, true) }
                        if (present) current.removeAll { it.equals(ticker, true) }
                        else current.add(ticker)
                        store.save(current)
                        val nowPresent = current.any { it.equals(ticker, true) }
                        refresh(nowPresent)
                        contentDescription = if (nowPresent) {
                            "Remove $ticker from Watchlist"
                        } else {
                            "Add $ticker to Watchlist"
                        }
                        Toast.makeText(
                            host.root.context,
                            if (nowPresent) "$ticker added to Watchlist" else "$ticker removed from Watchlist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                // Put the eye immediately after the ticker, before the price text.
                headline.addView(
                    eye,
                    (tickerView.indexInParent() + 1).coerceAtMost(headline.childCount),
                    LinearLayout.LayoutParams(host.dp(46), host.dp(42))
                )
            }
        }

        host.content.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                child?.post { scanAnalysis() }
            }
            override fun onChildViewRemoved(parent: View?, child: View?) = Unit
        })
        host.content.post { scanAnalysis() }
        host.content.postDelayed({ scanAnalysis() }, 80L)
        host.content.postDelayed({ scanAnalysis() }, 250L)
        host.content.postDelayed({ scanAnalysis() }, 600L)

        installWatchlistBridge(host.root.context as? Activity)
    }

    private fun installWatchlistBridge(activity: Activity?) {
        val a = activity ?: return
        val decor = a.window?.decorView ?: return
        if (decor.getTag() == BRIDGE_TAG) return
        decor.setTag(BRIDGE_TAG)

        fun scanWatchlist() {
            val rows = ArrayList<ViewGroup>()
            collectRows(decor, rows)
            rows.forEach { row ->
                if (row.getTag() == BRIDGE_TAG) return@forEach
                val description = row.contentDescription?.toString() ?: return@forEach
                if (!description.contains("Analysis", ignoreCase = true)) return@forEach
                val ticker = description.substringBefore(" —").trim().uppercase(Locale.US)
                if (!tickerPattern.matches(ticker)) return@forEach

                row.setTag(BRIDGE_TAG)
                row.isClickable = true
                row.isFocusable = true
                // Make the two navigation targets explicit; the delete child keeps its own listener.
                for (j in 0 until row.childCount) {
                    val child = row.getChildAt(j)
                    if (child is TextView && child.text?.toString()?.trim() == ticker) {
                        child.isClickable = true
                        child.isFocusable = true
                        child.setOnClickListener { openAnalysis(a, ticker) }
                    } else if (child is TextView && child.text?.toString()?.trim() == "›") {
                        child.isClickable = true
                        child.isFocusable = true
                        child.setOnClickListener { openAnalysis(a, ticker) }
                    }
                }
                row.setOnClickListener { openAnalysis(a, ticker) }
            }
        }

        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() { scanWatchlist() }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        decor.post { scanWatchlist() }
    }

    private fun collectRows(v: View, out: MutableList<ViewGroup>) {
        if (v is ViewGroup) {
            val desc = v.contentDescription?.toString()
            if (!desc.isNullOrBlank() && desc.contains("open in Analysis", ignoreCase = true)) out.add(v)
            for (i in 0 until v.childCount) collectRows(v.getChildAt(i), out)
        }
    }

    private fun openAnalysis(activity: Activity, ticker: String) {
        OracleSimpleModule.setTickerDraft(ticker)
        // MainActivity owns navigation; invoke its private openModule method only for
        // this bridge. This avoids changing the Watchlist layout or data model.
        runCatching {
            val method = activity.javaClass.getDeclaredMethod("openModule", String::class.java)
            method.isAccessible = true
            method.invoke(activity, "analysis")
        }.onFailure {
            Toast.makeText(activity, "Could not open Analysis for $ticker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun containsSectorLabel(v: ViewGroup): Boolean {
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child is TextView && child.text?.toString()?.contains("Sector:", ignoreCase = true) == true) return true
            if (child is ViewGroup && containsSectorLabel(child)) return true
        }
        return false
    }

    private fun findTickerView(v: ViewGroup): TextView? {
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (child is TextView) {
                val t = child.text?.toString()?.trim()?.uppercase(Locale.US) ?: ""
                if (tickerPattern.matches(t)) return child
            }
            if (child is ViewGroup) {
                val found = findTickerView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun View.indexInParent(): Int {
        val p = parent as? ViewGroup ?: return 0
        for (i in 0 until p.childCount) if (p.getChildAt(i) === this) return i
        return 0
    }

    private class EyeView(private val host: OracleNativeModule) : View(host.root.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = host.dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private var selected = false

        fun refresh(value: Boolean) {
            selected = value
            paint.color = if (selected) Color.rgb(255, 210, 45) else Color.rgb(125, 135, 155)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val rx = host.dp(15).toFloat()
            val ry = host.dp(9).toFloat()
            canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
            canvas.drawCircle(cx, cy, host.dp(4).toFloat(), paint)
            if (selected) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, host.dp(2).toFloat(), paint)
                paint.style = Paint.Style.STROKE
            }
        }
    }
}

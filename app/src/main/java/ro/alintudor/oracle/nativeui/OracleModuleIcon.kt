package ro.alintudor.oracle.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The same hand-drawn glyph each module uses as its icon on the START hub
 * (OracleMysticStartView's card()), extracted into a small standalone View
 * so module headers can show it too — same shape, same stroke, just without
 * the big background rings and card chrome a START tile has around it.
 *
 * `key` matches the lowercase module keys used everywhere else in the app
 * ("growth", "analysis", "portfolio", "watchlist", "news", "knowledge",
 * "alerts", "journal") — anything else draws nothing.
 */
class OracleModuleIcon(context: Context, private val key: String, private val color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f
        val rr = minOf(width, height) * 0.42f
        if (rr <= 0f) return
        p.color = color; p.alpha = 235; p.strokeWidth = rr * 0.09f
        when (key) {
            "watchlist" -> {
                path.reset()
                path.moveTo(cx - rr, cy); path.cubicTo(cx - rr * .55f, cy - rr * .48f, cx + rr * .55f, cy - rr * .48f, cx + rr, cy)
                path.cubicTo(cx + rr * .55f, cy + rr * .48f, cx - rr * .55f, cy + rr * .48f, cx - rr, cy)
                canvas.drawPath(path, p)
                p.style = Paint.Style.FILL; canvas.drawCircle(cx, cy, rr * .16f, p); p.style = Paint.Style.STROKE
            }
            "portfolio" -> {
                canvas.drawRect(cx - rr * .5f, cy - rr * .38f, cx + rr * .5f, cy + rr * .38f, p)
                canvas.drawCircle(cx + rr * .22f, cy + rr * .17f, rr * .16f, p)
            }
            "analysis" -> {
                path.reset()
                path.moveTo(cx - rr * .58f, cy + rr * .35f); path.lineTo(cx - rr * .2f, cy); path.lineTo(cx, cy + rr * .12f); path.lineTo(cx + rr * .56f, cy - rr * .5f)
                canvas.drawPath(path, p)
            }
            "growth" -> {
                path.reset()
                path.moveTo(cx - rr * .6f, cy + rr * .34f); path.lineTo(cx - rr * .2f, cy + .05f); path.lineTo(cx + rr * .04f, cy + .18f); path.lineTo(cx + rr * .58f, cy - rr * .5f)
                canvas.drawPath(path, p)
            }
            "alerts" -> {
                canvas.drawArc(RectF(cx - rr * .46f, cy - rr * .48f, cx + rr * .46f, cy + rr * .42f), 210f, 120f, false, p)
                canvas.drawLine(cx - rr * .2f, cy + rr * .42f, cx + rr * .2f, cy + rr * .42f, p)
            }
            "news" -> {
                canvas.drawRect(cx - rr * .46f, cy - rr * .44f, cx + rr * .46f, cy + rr * .44f, p)
                for (j in -1..1) canvas.drawLine(cx - rr * .28f, cy + j * rr * .19f, cx + rr * .28f, cy + j * rr * .19f, p)
            }
            "knowledge" -> {
                canvas.drawRect(cx - rr * .48f, cy - rr * .44f, cx, cy + rr * .44f, p)
                canvas.drawRect(cx, cy - rr * .44f, cx + rr * .48f, cy + rr * .44f, p)
            }
            "journal" -> {
                canvas.drawRect(cx - rr * .46f, cy - rr * .44f, cx + rr * .46f, cy + rr * .44f, p)
                path.reset()
                path.moveTo(cx - rr * .3f, cy + rr * .22f); path.lineTo(cx - rr * .06f, cy - rr * .02f); path.lineTo(cx + rr * .08f, cy + rr * .1f); path.lineTo(cx + rr * .32f, cy - rr * .24f)
                canvas.drawPath(path, p)
            }
        }
    }
}

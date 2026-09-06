package ro.alintudor.oracle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Native Start V2: vector-only, responsive, seven-module composition. */
class OracleMysticStartViewV2(context: Context, private val onModule: (String) -> Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val hit = mutableListOf<Pair<RectF, String>>()
    private var sx = 1f
    private var sy = 1f
    private var ox = 0f
    private var oy = 0f

    private val gold = Color.rgb(255, 205, 55)
    private val white = Color.rgb(245, 241, 231)
    private val green = Color.rgb(60, 255, 85)
    private val modules = listOf(
        M("portfolio", "PORTFOLIO", "OVERVIEW", Color.rgb(220, 55, 255)),
        M("watchlist", "WATCHLIST", "TRACK & FOCUS", Color.rgb(255, 205, 35)),
        M("analysis", "ANALYSIS", "CHARTS & TOOLS", Color.rgb(20, 220, 255)),
        M("growth", "GROWTH", "FUTURE SCAN", Color.rgb(120, 255, 45)),
        M("alerts", "ALERTS", "STAY AHEAD", Color.rgb(255, 65, 45)),
        M("news", "NEWS", "MARKET PULSE", Color.rgb(25, 205, 255)),
        M("knowledge", "KNOWLEDGE", "LEARN & EVOLVE", Color.rgb(255, 210, 45))
    )
    private data class M(val key: String, val title: String, val sub: String, val color: Int)

    private fun X(v: Float) = ox + v * sx
    private fun Y(v: Float) = oy + v * sy
    private fun S(v: Float) = v * min(sx, sy)

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val wide = w / h > 1.18f
        val dw = if (wide) 1280f else 720f
        val dh = if (wide) 800f else 1120f
        sx = w / dw; sy = h / dh
        ox = 0f; oy = 0f
        p.style = Paint.Style.FILL; p.alpha = 255; p.color = Color.rgb(1, 2, 5)
        c.drawRect(0f, 0f, w, h, p)

        val time = System.nanoTime() / 1_000_000_000.0
        val cx = X(dw * .5f)
        val eyeY = Y(if (wide) 185f else 255f)
        val eyeR = S(if (wide) 135f else 126f)

        stars(c, w, h, time)
        grid(c, cx, eyeY, S(if (wide) 118f else 112f), S(18f))
        sigil(c, cx, Y(if (wide) 31f else 54f), S(20f), gold)
        text(c, "LUX OCULI", cx, Y(if (wide) 72f else 100f), S(if (wide) 34f else 31f), gold, Typeface.SERIF, .18f, true)
        text(c, "STOCK INTELLIGENCE", cx, Y(if (wide) 99f else 127f), S(9f), gold, Typeface.DEFAULT, .25f, true)
        eye(c, cx, eyeY, eyeR, time)
        text(c, "SEE MORE.  KNOW FIRST.", cx, Y(if (wide) 330f else 430f), S(10.5f), white, Typeface.DEFAULT, .25f, true)
        line(c, X(if (wide) 385f else 220f), Y(if (wide) 348f else 449f), X(if (wide) 895f else 500f), Y(if (wide) 348f else 449f), gold, 125, .7f)
        diamond(c, cx, Y(if (wide) 348f else 449f), S(4f), gold)

        hit.clear()
        if (wide) drawCards(c, 145f, 385f, 225f, 110f, 18f, time, true)
        else drawCards(c, 18f, 475f, 162f, 118f, 10f, time, false)
        drawStatus(c, wide, time)
        text(c, "357AT2026", cx, Y(if (wide) 775f else 1090f), S(10f), gold, Typeface.DEFAULT_BOLD, .18f, true)
        postInvalidateDelayed(32L)
    }

    private fun stars(c: Canvas, w: Float, h: Float, time: Double) {
        p.style = Paint.Style.FILL
        for (i in 0 until 70) {
            val x = ((i * 83 + 41) % 1000) / 1000f * w
            val y = ((i * 149 + 17) % 1000) / 1000f * h
            val q = (0.5 + 0.5 * sin(time * .7 + i)).toFloat()
            p.color = Color.argb((35 + 75 * q).toInt(), 210, 190, 80)
            c.drawCircle(x, y, S(.65f + (i % 3) * .35f), p)
        }
    }

    private fun grid(c: Canvas, cx: Float, cy: Float, first: Float, step: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = S(.55f); p.color = Color.argb(48, 205, 175, 65)
        for (i in 0 until 14) c.drawCircle(cx, cy, first + i * step, p)
        for (i in 0 until 32) {
            val a = i * Math.PI / 16.0
            val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
            c.drawLine(cx + dx * (first - S(16f)), cy + dy * (first - S(16f)), cx + dx * (first + S(255f)), cy + dy * (first + S(255f)), p)
        }
    }

    private fun eye(c: Canvas, x: Float, y: Float, r: Float, time: Double) {
        val q = (0.5 + 0.5 * sin(time * 1.25)).toFloat()
        p.style = Paint.Style.STROKE
        path.reset(); path.moveTo(x-r, y)
        path.cubicTo(x-r*.58f,y-r*.55f,x+r*.58f,y-r*.55f,x+r,y)
        path.cubicTo(x+r*.58f,y+r*.55f,x-r*.58f,y+r*.55f,x-r,y)
        p.color = gold; p.alpha = (180 + 70*q).toInt(); p.strokeWidth = S(2f); c.drawPath(path,p)
        p.color = green; p.alpha = (55 + 90*q).toInt(); p.strokeWidth = S(1.2f)
        c.drawCircle(x,y,r*(.48f+.035f*q),p)
        p.alpha = (160 + 90*q).toInt(); p.strokeWidth = S(2f); c.drawCircle(x,y,r*.29f,p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(2,10,4); p.alpha=255; c.drawCircle(x,y,r*.275f,p)
        p.color = green; p.alpha=(165+90*q).toInt(); c.drawCircle(x,y,r*(.09f+.035f*q),p)
        p.color = Color.argb((30+80*q).toInt(),60,255,85); c.drawCircle(x,y,r*(.15f+.05f*q),p)
        p.style = Paint.Style.STROKE; p.color = Color.rgb(255,105,35); p.alpha=(70+90*q).toInt(); p.strokeWidth=S(.8f)
        for(i in 0 until 28){ val a=i*Math.PI/14.0; val inn=r*.40f; val out=r*(.56f+.05f*q); c.drawLine(x+cos(a).toFloat()*inn,y+sin(a).toFloat()*inn,x+cos(a).toFloat()*out,y+sin(a).toFloat()*out,p) }
    }

    private fun drawCards(c: Canvas, left: Float, top: Float, cw: Float, ch: Float, gap: Float, time: Double, wide: Boolean) {
        for (i in modules.indices) {
            val col: Int; val row: Int
            if (i < 4) { col=i; row=0 }
            else { col=i-4; row=1 }
            val rowCount = if (row==0) 4 else 3
            val rowWidth = rowCount*cw + (rowCount-1)*gap
            val rowLeft = if (row==0) left else left + (4*cw+3*gap-rowWidth)/2f
            val l=rowLeft+col*(cw+gap); val t=top+row*(ch+gap)
            val r=RectF(X(l),Y(t),X(l+cw),Y(t+ch)); hit += r to modules[i].key
            card(c,r,modules[i],time,i,wide)
        }
    }

    private fun card(c: Canvas, r: RectF, m: M, time: Double, index: Int, wide: Boolean) {
        val q=(.5+.5*sin(time*1.1+index*.53)).toFloat(); val ccx=r.centerX(); val ccy=r.top+r.height()*.39f
        val rr=min(r.width(),r.height())*.255f
        p.style=Paint.Style.FILL; p.color=Color.rgb(2,4,8); p.alpha=248; c.drawRoundRect(r,S(10f),S(10f),p)
        p.style=Paint.Style.STROKE; p.color=m.color; p.alpha=(155+95*q).toInt(); p.strokeWidth=S(1.15f); c.drawRoundRect(r,S(10f),S(10f),p)
        p.alpha=(35+95*q).toInt(); p.strokeWidth=S(1f); c.drawCircle(ccx,ccy,rr*(1.16f+.06f*q),p)
        p.alpha=(100+130*q).toInt(); c.drawCircle(ccx,ccy,rr,p); p.alpha=90; c.drawCircle(ccx,ccy,rr*.78f,p)
        p.alpha=255; p.strokeWidth=S(1.8f)
        when(m.key){
            "watchlist"->miniEye(c,ccx,ccy,rr*.72f,m.color)
            "portfolio"->{c.drawRect(ccx-rr*.5f,ccy-rr*.38f,ccx+rr*.5f,ccy+rr*.38f,p);c.drawCircle(ccx+rr*.22f,ccy+rr*.17f,rr*.16f,p)}
            "analysis"->{path.reset();path.moveTo(ccx-rr*.58f,ccy+rr*.35f);path.lineTo(ccx-rr*.2f,ccy);path.lineTo(ccx,ccy+rr*.12f);path.lineTo(ccx+rr*.56f,ccy-rr*.5f);c.drawPath(path,p);c.drawLine(ccx+rr*.56f,ccy-rr*.5f,ccx+rr*.35f,ccy-rr*.48f,p);c.drawLine(ccx+rr*.56f,ccy-rr*.5f,ccx+rr*.54f,ccy-.28f,p)}
            "growth"->{path.reset();path.moveTo(ccx-rr*.6f,ccy+rr*.34f);path.lineTo(ccx-rr*.2f,ccy+.05f);path.lineTo(ccx+rr*.04f,ccy+.18f);path.lineTo(ccx+rr*.58f,ccy-rr*.5f);c.drawPath(path,p)}
            "alerts"->{c.drawArc(RectF(ccx-rr*.46f,ccy-rr*.48f,ccx+rr*.46f,ccy+rr*.42f),210f,120f,false,p);c.drawLine(ccx-rr*.2f,ccy+rr*.42f,ccx+rr*.2f,ccy+rr*.42f,p)}
            "news"->{c.drawRect(ccx-rr*.46f,ccy-rr*.44f,ccx+rr*.46f,ccy+rr*.44f,p);for(j in -1..1)c.drawLine(ccx-rr*.28f,ccy+j*rr*.19f,ccx+rr*.28f,ccy+j*rr*.19f,p)}
            "knowledge"->{c.drawRect(ccx-rr*.48f,ccy-rr*.44f,ccx,ccy+rr*.44f,p);c.drawRect(ccx,ccy-rr*.44f,ccx+rr*.48f,ccy+rr*.44f,p)}
        }
        text(c,m.title,ccx,r.top+r.height()*.73f,S(if(wide)11.5f else 11f),white,Typeface.DEFAULT,.01f,true)
        text(c,m.sub,ccx,r.top+r.height()*.88f,S(if(wide)7.7f else 7.2f),m.color,Typeface.DEFAULT,.02f,true)
        p.color=m.color;p.alpha=(130+115*q).toInt();p.strokeWidth=S(1f);c.drawLine(ccx-S(32f),r.bottom-S(12f),ccx+S(32f),r.bottom-S(12f),p);diamond(c,ccx,r.bottom-S(12f),S(3.3f),m.color)
    }

    private fun drawStatus(c: Canvas, wide: Boolean, time: Double) {
        val q=(.5+.5*sin(time*.72)).toFloat()
        val top=if(wide)635f else 845f; val bottom=if(wide)718f else 930f
        val left=if(wide)145f else 18f; val right=if(wide)1135f else 702f
        val r=RectF(X(left),Y(top),X(right),Y(bottom))
        p.style=Paint.Style.FILL;p.color=Color.rgb(3,7,9);p.alpha=250;c.drawRoundRect(r,S(11f),S(11f),p)
        p.style=Paint.Style.STROKE;p.color=green;p.alpha=(110+115*q).toInt();p.strokeWidth=S(1.1f);c.drawRoundRect(r,S(11f),S(11f),p)
        val eyeX=if(wide)225f else 75f; val leftText=if(wide)285f else 122f; val centerX=if(wide)640f else 360f; val rightText=if(wide)745f else 410f; val shieldX=if(wide)1050f else 650f
        miniEye(c,X(eyeX),Y(top+41f),S(25f),green)
        textLeft(c,"LUX OCULI READY",X(leftText),Y(top+36f),S(14f),white,Typeface.DEFAULT_BOLD)
        textLeft(c,"Market Intelligence Active",X(leftText),Y(top+59f),S(8.7f),green,Typeface.DEFAULT)
        p.color=gold;p.alpha=(125+120*q).toInt();p.strokeWidth=S(1.2f);c.drawCircle(X(centerX),Y(top+41f),S(24f+3f*q),p)
        for(i in -2..2){val xx=X(centerX+i*6f);val hh=S(8f+abs(i)*3f);c.drawLine(xx,Y(top+41f)-hh,xx,Y(top+41f)+hh,p)}
        textLeft(c,"LOCAL INTELLIGENCE",X(rightText),Y(top+36f),S(13f),white,Typeface.DEFAULT_BOLD)
        textLeft(c,"Synced & Protected",X(rightText),Y(top+59f),S(8.7f),green,Typeface.DEFAULT)
        shield(c,X(shieldX),Y(top+41f),S(24f),green,q)
    }

    private fun shield(c:Canvas,x:Float,y:Float,r:Float,color:Int,q:Float){p.style=Paint.Style.STROKE;p.color=color;p.alpha=(100+130*q).toInt();p.strokeWidth=S(1.2f);path.reset();path.moveTo(x,y-r);path.lineTo(x+r*.72f,y-r*.55f);path.lineTo(x+r*.6f,y+r*.48f);path.lineTo(x,y+r);path.lineTo(x-r*.6f,y+r*.48f);path.lineTo(x-r*.72f,y-r*.55f);path.close();c.drawPath(path,p);path.reset();path.moveTo(x-r*.3f,y);path.lineTo(x-r*.05f,y+r*.25f);path.lineTo(x+r*.38f,y-r*.28f);c.drawPath(path,p)}
    private fun miniEye(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=235;p.strokeWidth=S(1.5f);path.reset();path.moveTo(x-r,y);path.cubicTo(x-r*.55f,y-r*.48f,x+r*.55f,y-r*.48f,x+r,y);path.cubicTo(x+r*.55f,y+r*.48f,x-r*.55f,y+r*.48f,x-r,y);c.drawPath(path,p);p.style=Paint.Style.FILL;c.drawCircle(x,y,r*.16f,p)}
    private fun sigil(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=220;p.strokeWidth=S(1.2f);c.drawCircle(x,y,r*.45f,p);c.drawCircle(x,y,r*.14f,p);c.drawLine(x,y-r*.45f,x,y-r*.8f,p);c.drawLine(x-r*.65f,y,x-r*.25f,y,p);c.drawLine(x+r*.25f,y,x+r*.65f,y,p)}
    private fun diamond(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=220;p.strokeWidth=S(.8f);path.reset();path.moveTo(x,y-r);path.lineTo(x+r,y);path.lineTo(x,y+r);path.lineTo(x-r,y);path.close();c.drawPath(path,p)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,alpha:Int,width:Float){p.style=Paint.Style.STROKE;p.color=color;p.alpha=alpha;p.strokeWidth=S(width);c.drawLine(x1,y1,x2,y2,p)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,typeface:Typeface,spacing:Float,bold:Boolean){p.style=Paint.Style.FILL;p.color=color;p.alpha=255;p.textSize=size;p.typeface=if(bold)Typeface.create(typeface,Typeface.BOLD) else typeface;p.textAlign=Paint.Align.CENTER;p.letterSpacing=spacing;c.drawText(s,x,y,p)}
    private fun textLeft(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,typeface:Typeface){p.style=Paint.Style.FILL;p.color=color;p.alpha=255;p.textSize=size;p.typeface=typeface;p.textAlign=Paint.Align.LEFT;p.letterSpacing=.01f;c.drawText(s,x,y,p)}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_UP){for((r,key) in hit)if(r.contains(e.x,e.y)){performClick();onModule(key);return true}}
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

package ro.alintudor.oracle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Native Start B515: vector-only, responsive, seven-module composition. */
class OracleMysticStartView(context: Context, private val onModule: (String) -> Unit) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val hit = mutableListOf<Pair<RectF, String>>()
    private var sx = 1f; private var sy = 1f; private var ox = 0f; private var oy = 0f
    private val gold = Color.rgb(255, 205, 55); private val white = Color.rgb(245, 241, 231); private val green = Color.rgb(60, 255, 85)
    /** Set from the Activity after the local, silent alerts check resolves. */
    var alertsStatusText: String = "ALERTS \u2026"
    var alertsStatusColor: Int = Color.rgb(150, 150, 150)
    fun setAlertsStatus(text: String, color: Int) { alertsStatusText = text; alertsStatusColor = color; postInvalidate() }
    /** Set from the Activity after a background /ping resolves. */
    var serverStatusText: String = "SERVER \u2026"
    var serverStatusColor: Int = Color.rgb(150, 150, 150)
    fun setServerStatus(text: String, color: Int) { serverStatusText = text; serverStatusColor = color; postInvalidate() }
    /** Set from the Activity from the cached alert list — only the true
     *  URGENT/CRITICAL kinds, never Portfolio/Watchlist signals or the
     *  person's own alerts, so this stays reserved for what actually needs
     *  their attention right now. Empty list = no ticker drawn at all. */
    private var urgentAlertTexts: List<String> = emptyList()
    fun setUrgentAlerts(texts: List<String>) { urgentAlertTexts = texts; postInvalidate() }
    private val modules = listOf(
        M("growth", "GROWTH", "FUTURE SCAN", Color.rgb(120, 255, 45)),
        M("analysis", "ANALYSIS", "CHARTS & TOOLS", Color.rgb(20, 220, 255)),
        M("portfolio", "PORTFOLIO", "OVERVIEW", Color.rgb(220, 55, 255)),
        M("watchlist", "WATCHLIST", "TRACK & FOCUS", Color.rgb(255, 205, 35)),
        M("news", "NEWS", "MARKET PULSE", Color.rgb(25, 205, 255)),
        M("knowledge", "KNOWLEDGE", "LEARN & EVOLVE", Color.rgb(255, 210, 45)),
        M("alerts", "ALERTS", "STAY AHEAD", Color.rgb(255, 65, 45)),
        M("journal", "JOURNAL", "TRACK RECORD", Color.rgb(50, 220, 190))
    )
    private data class M(val key:String,val title:String,val sub:String,val color:Int)
    private data class ConstellationStar(val nx: Float, val ny: Float, val phase: Double)
    private data class Constellation(val stars: List<ConstellationStar>, val hue: Int) // 0=neutral, 1=green, 2=red
    private val constellations: List<Constellation> by lazy { buildConstellations() }
    private fun buildConstellations(): List<Constellation> {
        val rnd = java.util.Random(357202601L) // fixed seed: pattern stays stable across frames
        val clusters = mutableListOf<Constellation>()
        repeat(30) { idx ->
            var cx = 0.05f + rnd.nextFloat() * 0.90f
            var cy = 0.04f + rnd.nextFloat() * 0.88f
            val count = 3 + rnd.nextInt(2)
            val stars = mutableListOf<ConstellationStar>()
            repeat(count) {
                stars += ConstellationStar(cx.coerceIn(0.02f, 0.98f), cy.coerceIn(0.02f, 0.98f), rnd.nextDouble() * 6.28)
                cx += (rnd.nextFloat() - 0.5f) * 0.16f
                cy += (rnd.nextFloat() - 0.5f) * 0.11f
            }
            val hue = when (idx % 3) { 0 -> 1; 1 -> 2; else -> 0 } // roughly even green/red/neutral split
            clusters += Constellation(stars, hue)
        }
        return clusters
    }
    private fun X(v:Float)=ox+v*sx; private fun Y(v:Float)=oy+v*sy; private fun S(v:Float)=v*min(sx,sy)

    private var introStartNanos = 0L
    private var eyeCx = 0f; private var eyeCy = 0f; private var eyeRadius = 0f
    private var explosionStartNanos = 0L
    override fun onDraw(c:Canvas){
        super.onDraw(c); val w=width.toFloat(); val h=height.toFloat(); if(w<=0f||h<=0f)return
        if(introStartNanos==0L) introStartNanos=System.nanoTime()
        val wide=w/h>1.18f; val dw=if(wide)1280f else 720f; val dh=if(wide)832f else 1206f; sx=w/dw; sy=h/dh; ox=0f; oy=0f
        p.style=Paint.Style.FILL;p.alpha=255;p.shader=LinearGradient(0f,0f,0f,h,Color.rgb(4,9,32),Color.rgb(2,4,14),Shader.TileMode.CLAMP);c.drawRect(0f,0f,w,h,p);p.shader=null
        val time=System.nanoTime()/1_000_000_000.0; val cx=X(dw*.5f); val eyeY=Y(if(wide)185f else 255f); val eyeR=S(if(wide)135f else 126f)
        eyeCx=cx; eyeCy=eyeY; eyeRadius=eyeR
        stars(c,w,h,time); shootingStar(c,w,h,time); satellites(c,w,h,time); grid(c,cx,eyeY,S(if(wide)118f else 112f),S(18f)); sigil(c,cx,Y(if(wide)31f else 54f),S(20f),gold)
        text(c,"LUX OCULI",cx,Y(if(wide)72f else 100f),S(if(wide)34f else 31f),gold,Typeface.SERIF,.18f,true)
        text(c,"STOCK INTELLIGENCE",cx,Y(if(wide)99f else 127f),S(9f),gold,Typeface.DEFAULT,.25f,true); eye(c,cx,eyeY,eyeR,time)
        // A visitor should never be unsure they're in the demo — a small,
        // permanent tag right under the brand, not just a per-module banner.
        val demoActive = ro.alintudor.oracle.core.OracleDemo.active(context)
        if (demoActive) {
            val badgeY = Y(if (wide) 116f else 145f)
            p.textSize = S(8f); p.typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD); p.textAlign = Paint.Align.CENTER; p.letterSpacing = .2f
            val badgeText = "DEMO"; val badgeW = p.measureText(badgeText)
            p.style = Paint.Style.FILL; p.color = Color.argb(60, 255, 160, 25)
            c.drawRoundRect(RectF(cx - badgeW / 2f - S(10f), badgeY - S(11f), cx + badgeW / 2f + S(10f), badgeY + S(5f)), S(8f), S(8f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = S(1f); p.color = Color.rgb(255, 160, 25)
            c.drawRoundRect(RectF(cx - badgeW / 2f - S(10f), badgeY - S(11f), cx + badgeW / 2f + S(10f), badgeY + S(5f)), S(8f), S(8f), p)
            p.style = Paint.Style.FILL; p.color = Color.rgb(255, 160, 25)
            c.drawText(badgeText, cx, badgeY, p)
        }
        drawEyeExplosion(c)
        val introElapsed=(System.nanoTime()-introStartNanos)/1_000_000_000.0; val introDuration=0.7
        val introScale=if(introElapsed<introDuration){val t=(introElapsed/introDuration).toFloat();1f+0.65f*(1f-t)*(1f-t)}else 1f
        text(c,"SEE MORE.  KNOW FIRST.",cx,Y(if(wide)330f else 430f),S(15f)*introScale,white,Typeface.DEFAULT,.25f,true)
        line(c,X(if(wide)385f else 220f),Y(if(wide)348f else 449f),X(if(wide)895f else 500f),Y(if(wide)348f else 449f),gold,125,.7f); diamond(c,cx,Y(if(wide)348f else 449f),S(4f),gold)
        hit.clear(); if(wide)drawCards(c,101f,420f,250f,125f,26f,time,true) else drawCards(c,10f,680f,165f,132f,13f,time,false)
        if (urgentAlertTexts.isNotEmpty()) {
            val tickerY = Y(if (wide) 570f else 850f)
            val passSeconds = 6.0; val passes = 4; val pauseSeconds = 10.0
            val cycle = passes * passSeconds + pauseSeconds
            val withinCycle = time % cycle
            if (withinCycle < passes * passSeconds) {
                val passProgress = (withinCycle % passSeconds) / passSeconds
                val tickerText = "\u26A0  " + urgentAlertTexts.joinToString("    \u2022    ")
                p.style=Paint.Style.FILL;p.color=Color.rgb(255,90,90);p.alpha=255;p.textSize=S(12f)
                p.typeface=Typeface.create(Typeface.DEFAULT_BOLD,Typeface.BOLD);p.textAlign=Paint.Align.LEFT;p.letterSpacing=.04f
                val textWidth=p.measureText(tickerText)
                // Enters fully off-screen right, exits fully off-screen left —
                // "in and out of view" rather than just sliding within bounds.
                val startX = w; val endX = -textWidth
                val x = startX + (endX - startX) * passProgress.toFloat()
                c.save(); c.clipRect(0f, tickerY - S(14f), w, tickerY + S(6f))
                c.drawText(tickerText, x, tickerY, p)
                c.restore()
            }
        }
        p.style=Paint.Style.FILL;p.color=gold;p.alpha=255;p.textSize=S(10f);p.typeface=Typeface.create(Typeface.DEFAULT_BOLD,Typeface.BOLD);p.textAlign=Paint.Align.RIGHT;p.letterSpacing=.18f
        val brandX=X(if(wide)1180f else 660f); val brandY=Y(if(wide)775f else 1090f)
        c.drawText("357AT2026",brandX,brandY,p)

        text(c,"DISCLAIMER",cx,brandY,S(13f),Color.rgb(255,160,25),Typeface.DEFAULT,.18f,true)
        p.textAlign=Paint.Align.CENTER;p.textSize=S(13f);p.letterSpacing=.18f
        val discWidth=p.measureText("DISCLAIMER")
        hit+=RectF(cx-discWidth/2f-S(14f),brandY-S(22f),cx+discWidth/2f+S(14f),brandY+S(12f)) to "disclaimer"

        val alertsX=X(if(wide)100f else 60f)
        val dotR=S(3.5f); val dotCx=alertsX+dotR; val dotCy=brandY-S(3f)
        val dotBlink=(140+115*((0.5+0.5*sin(time*2.4)).coerceIn(0.0,1.0))).toInt()
        p.style=Paint.Style.FILL;p.color=alertsStatusColor;p.alpha=dotBlink
        c.drawCircle(dotCx,dotCy,dotR,p)
        p.alpha=255;p.textSize=S(11f);p.typeface=Typeface.create(Typeface.DEFAULT_BOLD,Typeface.BOLD);p.textAlign=Paint.Align.LEFT;p.letterSpacing=.14f
        c.drawText(alertsStatusText,dotCx+dotR+S(6f),brandY,p)
        val alertsTextWidth=p.measureText(alertsStatusText)
        val serverDotCx=dotCx+dotR+S(6f)+alertsTextWidth+S(18f); val serverDotCy=dotCy
        p.style=Paint.Style.FILL;p.color=serverStatusColor;p.alpha=dotBlink
        c.drawCircle(serverDotCx,serverDotCy,dotR,p)
        p.alpha=255;p.textSize=S(11f);p.typeface=Typeface.create(Typeface.DEFAULT_BOLD,Typeface.BOLD);p.textAlign=Paint.Align.LEFT;p.letterSpacing=.14f
        c.drawText(serverStatusText,serverDotCx+dotR+S(6f),brandY,p)

        val toolsY=brandY+S(32f)
        val modulesDotCx=alertsX+dotR; val modulesDotCy=toolsY-S(3f)
        p.style=Paint.Style.FILL;p.color=Color.rgb(105,245,35);p.alpha=dotBlink
        c.drawCircle(modulesDotCx,modulesDotCy,dotR,p)
        p.alpha=255;p.textSize=S(11f);p.typeface=Typeface.create(Typeface.DEFAULT_BOLD,Typeface.BOLD);p.textAlign=Paint.Align.LEFT;p.letterSpacing=.14f
        c.drawText("ALL MODULES ACTIVE",modulesDotCx+dotR+S(6f),toolsY,p)
        // Demo: this same slot becomes the exit door instead of TOOLS — TOOLS
        // itself is closed to a visitor, so there is no point pointing at it.
        p.color=if(demoActive) Color.rgb(255,110,110) else Color.rgb(150,160,182);p.textSize=S(14f);p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);p.textAlign=Paint.Align.RIGHT;p.letterSpacing=.14f
        val toolsLabel=if(demoActive) "\uD83D\uDD13  EXIT DEMO" else "\uD83D\uDD27  TOOLS"
        c.drawText(toolsLabel,brandX,toolsY,p)
        val toolsWidth=p.measureText(toolsLabel)
        hit+=RectF(brandX-toolsWidth-S(10f),toolsY-S(20f),brandX+S(10f),toolsY+S(14f)) to "backup"

        // Session line: who's logged in, since when, and which build of the
        // app they're running — a quick glance answers "is this really me,
        // and is this up to date" without opening TOOLS.
        if (!demoActive) {
            val sessionY=toolsY+S(30f)
            val auth = ro.alintudor.oracle.core.OracleAuthStore(context)
            val username = auth.username()
            val loginAt = auth.loginAt()
            val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
            if (username.isNotBlank()) {
                val loginText = if (loginAt > 0L) {
                    val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                    "$username \u2022 ${fmt.format(java.util.Date(loginAt))} \u2022 v$versionName"
                } else "$username \u2022 v$versionName"
                p.style=Paint.Style.FILL;p.color=Color.rgb(120,130,155);p.alpha=255;p.textSize=S(9.5f)
                p.typeface=Typeface.create(Typeface.DEFAULT,Typeface.NORMAL);p.textAlign=Paint.Align.LEFT;p.letterSpacing=.05f
                c.drawText(loginText,alertsX,sessionY,p)
            }
        }

        postInvalidateDelayed(32L)
    }
    private fun stars(c:Canvas,w:Float,h:Float,time:Double){
        p.style=Paint.Style.FILL
        for(i in 0 until 110){
            val x=((i*83+41)%1000)/1000f*w;val y=((i*149+17)%1000)/1000f*h
            if(i%9==0){
                // Twinkling stars: sharper flicker plus a size pulse.
                val flicker=sin(time*2.3+i*1.7).toFloat()
                val q=(0.5f+0.5f*flicker).coerceIn(0f,1f)
                val flash=if(flicker>0.85f) 1f else 0f
                p.color=Color.argb((90+150*q).toInt(),235,240,255)
                c.drawCircle(x,y,S(0.9f+1.7f*q+1.3f*flash),p)
            } else {
                val q=(.5+.5*sin(time*.7+i)).toFloat();p.color=Color.argb((70+140*q).toInt(),225,235,255);c.drawCircle(x,y,S(.6f+(i%3)*.4f),p)
            }
        }
        for(cst in constellations){
            val rgb = when(cst.hue){1->Triple(90,255,140);2->Triple(255,95,95);else->Triple(175,205,255)}
            val lineRgb = when(cst.hue){1->Triple(80,220,130);2->Triple(230,90,90);else->Triple(150,190,255)}
            p.style=Paint.Style.STROKE;p.strokeWidth=S(.75f);p.color=Color.argb(100,lineRgb.first,lineRgb.second,lineRgb.third)
            for(i in 0 until cst.stars.size-1){val a=cst.stars[i];val b=cst.stars[i+1];c.drawLine(a.nx*w,a.ny*h,b.nx*w,b.ny*h,p)}
            p.style=Paint.Style.FILL
            for(star in cst.stars){
                val q=(.5+.5*sin(time*1.15+star.phase)).toFloat()
                p.color=Color.argb((40+55*q).toInt(),rgb.first,rgb.second,rgb.third);c.drawCircle(star.nx*w,star.ny*h,S(3.6f+1.3f*q),p)
                p.color=Color.argb((175+80*q).toInt(),240,245,255);c.drawCircle(star.nx*w,star.ny*h,S(1.7f+0.7f*q),p)
            }
        }
    }
    private val satelliteTrajectories = listOf(
        floatArrayOf(0.02f, 0.14f, 0.98f, 0.34f),
        floatArrayOf(0.96f, 0.58f, 0.04f, 0.78f),
        floatArrayOf(0.10f, 0.92f, 0.90f, 0.62f)
    )
    private fun satellites(c:Canvas,w:Float,h:Float,time:Double){
        satelliteTrajectories.forEachIndexed { idx, traj ->
            val period=24.0+idx*8.5
            val t=(((time+idx*7.3)%period)/period).toFloat()
            val x=traj[0]*w+(traj[2]-traj[0])*w*t
            val y=traj[1]*h+(traj[3]-traj[1])*h*t
            val angle=Math.atan2((traj[3]-traj[1]).toDouble(),(traj[2]-traj[0]).toDouble())
            val dx=cos(angle).toFloat();val dy=sin(angle).toFloat()
            p.style=Paint.Style.STROKE;p.strokeWidth=S(.7f);p.color=Color.argb(150,190,205,225)
            c.drawLine(x-dy*S(4.5f),y+dx*S(4.5f),x+dy*S(4.5f),y-dx*S(4.5f),p)
            p.style=Paint.Style.FILL;p.color=Color.argb(230,215,222,235);c.drawCircle(x,y,S(1.5f),p)
            val blink=sin(time*3.2+idx*2.1)
            if(blink>0.6){p.color=Color.argb(230,255,95,95);c.drawCircle(x-dx*S(3f),y-dy*S(3f),S(1f),p)}
        }
    }
    private val shootingTrajectories = listOf(
        floatArrayOf(0.06f, 0.08f, 0.52f, 0.40f),
        floatArrayOf(0.60f, 0.05f, 0.97f, 0.28f),
        floatArrayOf(0.15f, 0.50f, 0.68f, 0.82f),
        floatArrayOf(0.85f, 0.12f, 0.40f, 0.55f)
    )
    private fun shootingStar(c:Canvas,w:Float,h:Float,time:Double){
        val period=8.5; val duration=1.0
        val cycle=(time/period).toLong(); val t=time%period
        if(t>duration) return
        val progress=(t/duration).toFloat().coerceIn(0f,1f)
        val traj=shootingTrajectories[(cycle%shootingTrajectories.size).toInt()]
        val x1=traj[0]*w; val y1=traj[1]*h; val x2=traj[2]*w; val y2=traj[3]*h
        val hx=x1+(x2-x1)*progress; val hy=y1+(y2-y1)*progress
        val tx=hx-(x2-x1)*0.22f; val ty=hy-(y2-y1)*0.22f
        val fade=(1f-progress*progress)
        p.style=Paint.Style.STROKE; p.strokeWidth=S(1.6f); p.shader=LinearGradient(tx,ty,hx,hy,Color.argb(0,255,255,255),Color.argb((235*fade).toInt(),255,255,255),Shader.TileMode.CLAMP)
        c.drawLine(tx,ty,hx,hy,p); p.shader=null
        p.style=Paint.Style.FILL; p.color=Color.argb((235*fade).toInt(),255,255,255); c.drawCircle(hx,hy,S(2.1f),p)
    }
    private fun grid(c:Canvas,cx:Float,cy:Float,first:Float,step:Float){p.style=Paint.Style.STROKE;p.strokeWidth=S(.55f);p.color=Color.argb(48,205,175,65);for(i in 0 until 14)c.drawCircle(cx,cy,first+i*step,p);for(i in 0 until 32){val a=i*Math.PI/16.0;val dx=cos(a).toFloat();val dy=sin(a).toFloat();c.drawLine(cx+dx*(first-S(16f)),cy+dy*(first-S(16f)),cx+dx*(first+S(255f)),cy+dy*(first+S(255f)),p)}}
    private fun drawEyeExplosion(c: Canvas) {
        if (explosionStartNanos == 0L) return
        val elapsedMs = (System.nanoTime() - explosionStartNanos) / 1_000_000.0
        if (elapsedMs > 3000.0) { explosionStartNanos = 0L; return }
        val t = (elapsedMs / 3000.0).toFloat().coerceIn(0f, 1f)
        val alpha = ((1f - t) * 255f).toInt().coerceIn(0, 255)
        val maxRadius = eyeRadius * 2.6f
        val radius = maxRadius * (0.12f + 0.88f * t)
        val rayCount = 36
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until rayCount) {
            val angle = i * (2.0 * Math.PI / rayCount)
            val cosA = cos(angle).toFloat(); val sinA = sin(angle).toFloat()
            val innerR = radius * 0.5f
            val sx = eyeCx + cosA * innerR; val sy = eyeCy + sinA * innerR
            val ex = eyeCx + cosA * radius; val ey = eyeCy + sinA * radius
            p.color = when (i % 3) { 0 -> gold; 1 -> green; else -> Color.rgb(255, 105, 35) }
            p.alpha = alpha
            p.strokeWidth = S(2.4f) * (1f - t * 0.4f)
            c.drawLine(sx, sy, ex, ey, p)
        }
        p.color = white; p.alpha = (alpha * 0.65f).toInt(); p.strokeWidth = S(1.4f)
        c.drawCircle(eyeCx, eyeCy, radius * 0.5f, p)
    }

    private fun eye(c:Canvas,x:Float,y:Float,r:Float,time:Double){val q=(.5+.5*sin(time*1.25)).toFloat();p.style=Paint.Style.STROKE;path.reset();path.moveTo(x-r,y);path.cubicTo(x-r*.58f,y-r*.55f,x+r*.58f,y-r*.55f,x+r,y);path.cubicTo(x+r*.58f,y+r*.55f,x-r*.58f,y+r*.55f,x-r,y);p.color=gold;p.alpha=(180+70*q).toInt();p.strokeWidth=S(2f);c.drawPath(path,p);p.color=green;p.alpha=(55+90*q).toInt();p.strokeWidth=S(1.2f);c.drawCircle(x,y,r*(.48f+.035f*q),p);p.alpha=(160+90*q).toInt();p.strokeWidth=S(2f);c.drawCircle(x,y,r*.29f,p);p.style=Paint.Style.FILL;p.color=Color.rgb(2,10,4);p.alpha=255;c.drawCircle(x,y,r*.275f,p);p.color=green;p.alpha=(165+90*q).toInt();c.drawCircle(x,y,r*(.09f+.035f*q),p);p.color=Color.argb((30+80*q).toInt(),60,255,85);c.drawCircle(x,y,r*(.15f+.05f*q),p);p.style=Paint.Style.STROKE;p.color=Color.rgb(255,105,35);p.alpha=(70+90*q).toInt();p.strokeWidth=S(.8f);for(i in 0 until 28){val a=i*Math.PI/14.0;val inn=r*.40f;val out=r*(.56f+.05f*q);c.drawLine(x+cos(a).toFloat()*inn,y+sin(a).toFloat()*inn,x+cos(a).toFloat()*out,y+sin(a).toFloat()*out,p)}}
    private fun drawCards(c:Canvas,left:Float,top:Float,cw:Float,ch:Float,gap:Float,time:Double,wide:Boolean){
        val rowGapFactor=if(wide) 2.3f else 2.8f
        val ampFactor=if(wide) 0.16f else 0.22f
        val rowGap=gap*rowGapFactor
        val amp=ch*ampFactor
        for(i in modules.indices){
            val col:Int;val row:Int
            if(i<4){col=i;row=0}else{col=i-4;row=1}
            val count=if(row==0)4 else modules.size-4
            val rowW=count*cw+(count-1)*gap
            val rowLeft=if(row==0)left else left+(4*cw+3*gap-rowW)/2f
            val l=rowLeft+col*(cw+gap)
            // Two flat rows, all cards aligned — no zigzag.
            val t=if(row==0) top else top+ch+gap*1.6f
            val r=RectF(X(l),Y(t),X(l+cw),Y(t+ch))
            hit+=r to modules[i].key
            // Entrance: each card flies in from beyond the screen edge on its
            // own side (left half from the left, right half from the right,
            // bottom row also from below), staggered 70 ms apart, easing out
            // as it settles into its slot — the row "gathers" into place.
            val introElapsed=(System.nanoTime()-introStartNanos)/1_000_000_000.0
            val delay=0.12+i*0.07; val dur=0.55
            val raw=((introElapsed-delay)/dur).coerceIn(0.0,1.0).toFloat()
            val e=1f-(1f-raw)*(1f-raw)*(1f-raw)   // ease-out cubic
            if(e<1f){
                val fromX=if(col<2) -(r.right+c.width*.15f) else (c.width-r.left)+c.width*.15f
                val fromY=if(row==0) 0f else c.height*.35f
                val dx=fromX*(1f-e); val dy=fromY*(1f-e)
                val scale=0.72f+0.28f*e
                c.saveLayerAlpha(r.left-S(40f),r.top-S(40f),r.right+S(40f),r.bottom+S(40f),(60+195*e).toInt())
                c.translate(dx,dy); c.scale(scale,scale,r.centerX(),r.centerY())
                card(c,r,modules[i],time,i,wide)
                c.restore()
            } else card(c,r,modules[i],time,i,wide)
        }
    }
    private fun card(c:Canvas,r:RectF,m:M,time:Double,index:Int,wide:Boolean){val q=(.5+.5*sin(time*1.1+index*.53)).toFloat();val cx=r.centerX();val cy=r.top+r.height()*.39f;val rr=min(r.width(),r.height())*.255f;p.style=Paint.Style.FILL;p.color=Color.rgb(2,4,8);p.alpha=248;c.drawRoundRect(r,S(10f),S(10f),p);p.style=Paint.Style.STROKE;p.color=m.color;p.alpha=(155+95*q).toInt();p.strokeWidth=S(1.15f);c.drawRoundRect(r,S(10f),S(10f),p);p.alpha=(35+95*q).toInt();p.strokeWidth=S(1f);c.drawCircle(cx,cy,rr*(1.16f+.06f*q),p);p.alpha=(100+130*q).toInt();c.drawCircle(cx,cy,rr,p);p.alpha=90;c.drawCircle(cx,cy,rr*.78f,p);p.alpha=255;p.strokeWidth=S(1.8f);when(m.key){"watchlist"->miniEye(c,cx,cy,rr*.72f,m.color);"portfolio"->{c.drawRect(cx-rr*.5f,cy-rr*.38f,cx+rr*.5f,cy+rr*.38f,p);c.drawCircle(cx+rr*.22f,cy+rr*.17f,rr*.16f,p)};"analysis"->{path.reset();path.moveTo(cx-rr*.58f,cy+rr*.35f);path.lineTo(cx-rr*.2f,cy);path.lineTo(cx,cy+rr*.12f);path.lineTo(cx+rr*.56f,cy-rr*.5f);c.drawPath(path,p)};"growth"->{path.reset();path.moveTo(cx-rr*.6f,cy+rr*.34f);path.lineTo(cx-rr*.2f,cy+.05f);path.lineTo(cx+rr*.04f,cy+.18f);path.lineTo(cx+rr*.58f,cy-rr*.5f);c.drawPath(path,p)};"alerts"->{c.drawArc(RectF(cx-rr*.46f,cy-rr*.48f,cx+rr*.46f,cy+rr*.42f),210f,120f,false,p);c.drawLine(cx-rr*.2f,cy+rr*.42f,cx+rr*.2f,cy+rr*.42f,p)};"news"->{c.drawRect(cx-rr*.46f,cy-rr*.44f,cx+rr*.46f,cy+rr*.44f,p);for(j in -1..1)c.drawLine(cx-rr*.28f,cy+j*rr*.19f,cx+rr*.28f,cy+j*rr*.19f,p)};"knowledge"->{c.drawRect(cx-rr*.48f,cy-rr*.44f,cx,cy+rr*.44f,p);c.drawRect(cx,cy-rr*.44f,cx+rr*.48f,cy+rr*.44f,p)};"journal"->{c.drawRect(cx-rr*.46f,cy-rr*.44f,cx+rr*.46f,cy+rr*.44f,p);path.reset();path.moveTo(cx-rr*.3f,cy+rr*.22f);path.lineTo(cx-rr*.06f,cy-rr*.02f);path.lineTo(cx+rr*.08f,cy+rr*.1f);path.lineTo(cx+rr*.32f,cy-rr*.24f);c.drawPath(path,p)}};text(c,m.title,cx,r.top+r.height()*.73f,S(if(wide)11.5f else 11f),white,Typeface.DEFAULT,.01f,true);text(c,m.sub,cx,r.top+r.height()*.88f,S(if(wide)7.7f else 7.2f),m.color,Typeface.DEFAULT,.02f,true);p.color=m.color;p.alpha=(130+115*q).toInt();p.strokeWidth=S(1f);c.drawLine(cx-S(32f),r.bottom-S(12f),cx+S(32f),r.bottom-S(12f),p);diamond(c,cx,r.bottom-S(12f),S(3.3f),m.color)}
    private fun miniEye(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=235;p.strokeWidth=S(1.5f);path.reset();path.moveTo(x-r,y);path.cubicTo(x-r*.55f,y-r*.48f,x+r*.55f,y-r*.48f,x+r,y);path.cubicTo(x+r*.55f,y+r*.48f,x-r*.55f,y+r*.48f,x-r,y);c.drawPath(path,p);p.style=Paint.Style.FILL;c.drawCircle(x,y,r*.16f,p)}
    private fun sigil(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=220;p.strokeWidth=S(1.2f);c.drawCircle(x,y,r*.45f,p);c.drawCircle(x,y,r*.14f,p);c.drawLine(x,y-r*.45f,x,y-r*.8f,p);c.drawLine(x-r*.65f,y,x-r*.25f,y,p);c.drawLine(x+r*.25f,y,x+r*.65f,y,p)}
    private fun diamond(c:Canvas,x:Float,y:Float,r:Float,color:Int){p.style=Paint.Style.STROKE;p.color=color;p.alpha=220;p.strokeWidth=S(.8f);path.reset();path.moveTo(x,y-r);path.lineTo(x+r,y);path.lineTo(x,y+r);path.lineTo(x-r,y);path.close();c.drawPath(path,p)}
    private fun line(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int,alpha:Int,width:Float){p.style=Paint.Style.STROKE;p.color=color;p.alpha=alpha;p.strokeWidth=S(width);c.drawLine(x1,y1,x2,y2,p)}
    private fun text(c:Canvas,s:String,x:Float,y:Float,size:Float,color:Int,typeface:Typeface,spacing:Float,bold:Boolean){p.style=Paint.Style.FILL;p.color=color;p.alpha=255;p.textSize=size;p.typeface=if(bold)Typeface.create(typeface,Typeface.BOLD) else typeface;p.textAlign=Paint.Align.CENTER;p.letterSpacing=spacing;c.drawText(s,x,y,p)}
    override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP){val dx=e.x-eyeCx;val dy=e.y-eyeCy;if(dx*dx+dy*dy<=eyeRadius*eyeRadius){performClick();explosionStartNanos=System.nanoTime();postInvalidate();return true};for((r,key)in hit)if(r.contains(e.x,e.y)){performClick();onModule(key);return true}};return true}
    override fun performClick():Boolean{super.performClick();return true}
}

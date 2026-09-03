package ro.alintudor.oracle

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import ro.alintudor.oracle.core.OracleBootstrap
import ro.alintudor.oracle.core.OracleGrowthLiveData
import ro.alintudor.oracle.core.OracleLocalProcessor
import ro.alintudor.oracle.core.OracleRepository
import ro.alintudor.oracle.core.OracleWatchlistStore
import ro.alintudor.oracle.core.OracleKnowledgeSync
import ro.alintudor.oracle.core.snapshot
import ro.alintudor.oracle.nativeui.*

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var repository: OracleRepository
    private var currentModule: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val titles = linkedMapOf("portfolio" to "PORTFOLIO", "alerts" to "ALERTS", "news" to "NEWS", "growth" to "GROWTH", "knowledge" to "KNOWLEDGE", "analysis" to "ANALYSIS", "watchlist" to "WATCHLIST", "journal" to "ACTIVITY JOURNAL")
    private val subtitles = mapOf("portfolio" to "Positions, P/L and allocation", "alerts" to "Signals and active alerts", "news" to "Relevant news and events", "growth" to "Return, local trend and contribution", "knowledge" to "Ideas, explanations and documentation", "analysis" to "Analysis and Oracle decisions", "watchlist" to "Tracked stocks and opportunities", "journal" to "Complete activity history")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = OracleRepository(this)
        window.statusBarColor = Color.rgb(1,3,8); window.navigationBarColor = Color.rgb(1,3,8)
        root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(1,3,8)) }
        setContentView(root)
        OracleKnowledgeSync.scheduleDaily(this)
        runCatching { OracleBootstrap.ensure(repository); showHub() }.onFailure { showFatalError("Oracle failed to start",it) }
    }

    private fun showHub() {
        currentModule=null; root.removeAllViews()
        val scroll=ScrollView(this).apply { isFillViewport=true; setBackgroundColor(Color.rgb(1,3,8)) }
        val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(6),dp(10),dp(24)) }
        val hero=OracleHeroView(this){ openModule(it) }
        val heroHeightPx=(resources.displayMetrics.heightPixels*.80f).toInt().coerceAtLeast(dp(620))
        page.addView(hero,LinearLayout.LayoutParams(-1,heroHeightPx))
        scroll.addView(page); root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
    }
    private fun makeHomeCard(number:Int,label:String,description:String,key:String)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(10),dp(14),dp(10));setBackgroundColor(Color.rgb(8,12,24));isClickable=true;isFocusable=true;elevation=dp(2).toFloat();setOnClickListener{openModule(key)}
        addView(TextView(this@MainActivity).apply{text="%02d".format(number);textSize=11f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.rgb(100,155,235))},LinearLayout.LayoutParams(dp(34),-2))
        addView(LinearLayout(this@MainActivity).apply{orientation=LinearLayout.VERTICAL;addView(TextView(this@MainActivity).apply{text=label;textSize=17f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)});addView(TextView(this@MainActivity).apply{text=description;textSize=12f;setTextColor(Color.rgb(165,172,190))})},LinearLayout.LayoutParams(0,-2,1f))
        addView(TextView(this@MainActivity).apply{text="›";textSize=26f;gravity=Gravity.CENTER;setTextColor(Color.rgb(125,140,165))},LinearLayout.LayoutParams(dp(28),dp(40)))
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()

    private fun openModule(key:String){
        currentModule=key
        runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}
        if (key == "analysis") return
        if (key == "knowledge") {
            if (OracleKnowledgeSync.isStale(this)) {
                OracleKnowledgeSync.refreshAsync(this) { ok, error ->
                    if (currentModule != "knowledge" || isFinishing) return@refreshAsync
                    if (ok) runCatching { renderModule("knowledge", false) }.onFailure { showModuleError("knowledge", it) }
                    else if (error != null) Toast.makeText(this, "Knowledge refresh failed: $error", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        Thread{val result=runCatching{OracleLocalProcessor.refresh(repository)};mainHandler.post{if(currentModule!=key||isFinishing)return@post;result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}.onFailure{e->Toast.makeText(this,"Local refresh failed: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}}}.start()
    }

    private fun openWatchlistTicker(ticker: String) {
        val normalized = ticker.trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return
        OracleSimpleModule.setTickerDraft(normalized)
        openModule("analysis")
    }

    private fun refreshModule(key:String){
        if(currentModule!=key || isFinishing)return
        Toast.makeText(this,"Updating ${titles[key]?:key.uppercase()}…",Toast.LENGTH_SHORT).show()
        Thread{
            val result=runCatching{OracleLocalProcessor.refresh(repository)}
            mainHandler.post{
                if(currentModule!=key || isFinishing)return@post
                result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}
                    .onFailure{e->Toast.makeText(this,"Local refresh failed: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}
            }
        }.start()
    }

    private fun renderModule(key:String,refresh:Boolean=false){
        val title = titles[key]?:key.uppercase()
        val preservedScrollY = OracleNativeModule.rememberedScroll(title)
        root.removeAllViews()
        val host=OracleNativeModule(this,title,{showHub()},{refreshModule(key)})
        root.addView(host.root,FrameLayout.LayoutParams(-1,-1))
        val data=if(refresh)OracleLocalProcessor.refresh(repository)else repository.snapshot()
        when(key){
            "portfolio"->OraclePortfolioModule(host).render(data.positions)
            "alerts"->OracleAlertsModule(host).render(data.alerts)
            "news"->OracleNewsModule(host).render(data.news)
            "journal"->OracleJournalModule(host).render(data.journal,data.history,data.alerts)
            "growth"->{ val liveGrowth=OracleGrowthLiveData.refresh(data.growth); OracleGrowthModule(host).render(liveGrowth,data.news) }
            "analysis"->OracleSimpleModule(host,title).render(actions=data.actions,knowledge=data.knowledge,positions=data.positions,history=data.history)
            "watchlist"->renderWatchlistDirect()
            "knowledge"->renderKnowledgeDirect(host)
        }
        host.restoreScrollY(preservedScrollY)
    }
    private fun renderKnowledgeDirect(host: OracleNativeModule) {
        host.content.removeAllViews()
        val context = this
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(18), host.dp(18), host.dp(18), host.dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(8, 14, 27))
                cornerRadius = host.dp(16).toFloat()
                setStroke(host.dp(1), Color.rgb(255, 205, 55))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openKnowledgeUrl("https://alintudor.ro/knowledge/") }
        }
        card.addView(TextView(context).apply {
            text = "KNOWLEDGE"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 215, 45))
        })
        card.addView(TextView(context).apply {
            text = "Open alintudor.ro/knowledge/"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, host.dp(8), 0, host.dp(12))
        })
        card.addView(Button(context).apply {
            text = "OPEN KNOWLEDGE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(12, 54, 82))
                cornerRadius = host.dp(11).toFloat()
            }
            setOnClickListener { openKnowledgeUrl("https://alintudor.ro/knowledge/") }
        }, LinearLayout.LayoutParams(-1, host.dp(46)))
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(14))
        })
    }

    private fun renderWatchlistDirect() {
        root.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(1, 3, 8))
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(30))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(5, 9, 18))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(255, 205, 55))
            }
            setOnClickListener { showHub() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(70), dp(52)))
        header.addView(TextView(this).apply {
            text = "WATCHLIST"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 215, 45))
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(Space(this), LinearLayout.LayoutParams(dp(70), dp(52)))
        page.addView(header)

        page.addView(View(this).apply { setBackgroundColor(Color.rgb(255, 205, 55)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, 0, 0, dp(28)) })
        page.addView(TextView(this).apply {
            text = "WATCHLIST • SAVED TICKERS"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .10f
            setTextColor(Color.rgb(255, 215, 45))
            setPadding(dp(8), 0, 0, dp(14))
        }, LinearLayout.LayoutParams(-1, -2))

        val store = OracleWatchlistStore(this)
        val tickers = store.load().map { it.trim().uppercase(java.util.Locale.US) }.filter { it.isNotBlank() }.distinct()
        if (tickers.isEmpty()) {
            page.addView(TextView(this).apply {
                text = "WATCHLIST EMPTY"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, dp(30))
            })
        } else {
            tickers.forEach { ticker ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(8), dp(8))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(6, 11, 22))
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), Color.rgb(45, 65, 95))
                    }
                }
                val open = Button(this).apply {
                    text = "$ticker   ›"
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), 0, dp(8), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(12).toFloat()
                    }
                    isAllCaps = false
                    contentDescription = "Open analysis for $ticker"
                    setOnClickListener { openWatchlistTicker(ticker) }
                    // FINAL_WATCHLIST_DIRECT_TOUCH_V2
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                scroll.requestDisallowInterceptTouchEvent(true)
                                false
                            }
                            MotionEvent.ACTION_UP -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                v.performClick()
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                false
                            }
                            else -> false
                        }
                    }
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                scroll.requestDisallowInterceptTouchEvent(true)
                                false
                            }
                            MotionEvent.ACTION_UP -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                v.performClick()
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                false
                            }
                            else -> false
                        }
                    }
                }
                row.addView(open, LinearLayout.LayoutParams(0, dp(76), 1f))

                row.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            scroll.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            openWatchlistTicker(ticker)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                        else -> false
                    }
                }

                // FINAL_WATCHLIST_ROW_TOUCH_V2
                row.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            scroll.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            openWatchlistTicker(ticker)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                        else -> false
                    }
                }

                val delete = Button(this).apply {
                    text = "DELETE"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(255, 105, 105))
                    setPadding(0, 0, 0, 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dp(10).toFloat()
                    }
                    contentDescription = "Remove $ticker from Watchlist"
                    setOnClickListener {
                        val current = store.load().toMutableList()
                        current.removeAll { it.equals(ticker, true) }
                        store.save(current)
                        renderWatchlistDirect()
                    }
                }
                row.addView(delete, LinearLayout.LayoutParams(dp(110), dp(76)))
                page.addView(row, LinearLayout.LayoutParams(-1, dp(92)).apply { setMargins(0, 0, 0, dp(12)) })
            }
        }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openKnowledgeUrl(url:String){
        if (url.isBlank()) return
        runCatching {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { Toast.makeText(this, "Unable to open the article", Toast.LENGTH_SHORT).show() }
    }

    private fun showModuleError(key:String,error:Throwable){root.removeAllViews();val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(32),dp(32),dp(32),dp(32));setBackgroundColor(Color.rgb(2,4,10))};box.addView(TextView(this).apply{text="ORACLE  •  ${titles[key]?:key.uppercase()}";textSize=22f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)});box.addView(TextView(this).apply{text="The module could not be loaded.\n\n${error.message?:error.javaClass.simpleName}";textSize=16f;gravity=Gravity.CENTER;setTextColor(Color.LTGRAY);setPadding(0,dp(24),0,dp(24))});box.addView(Button(this).apply{text="RETRY";setOnClickListener{openModule(key)}});box.addView(Button(this).apply{text="BACK TO ORACLE";setOnClickListener{showHub()}});root.addView(box,FrameLayout.LayoutParams(-1,-1))}
    private fun showFatalError(title:String,error:Throwable){root.removeAllViews();root.addView(TextView(this).apply{text="$title\n\n${error.message?:error.javaClass.simpleName}\n\nThe app will not stay stuck on loading.";textSize=17f;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setPadding(dp(32),dp(32),dp(32),dp(32))},FrameLayout.LayoutParams(-1,-1))}
    @Suppress("DEPRECATION") override fun onBackPressed(){if(currentModule!=null)showHub()else super.onBackPressed()}
}

private class OracleHeroView(context:android.content.Context,private val onModule:(String)->Unit):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodes=listOf(Node("portfolio","PORTFOLIO",Color.rgb(190,60,255),.50f,.15f),Node("alerts","ALERTS",Color.rgb(255,70,35),.18f,.29f),Node("news","NEWS",Color.rgb(0,215,255),.82f,.29f),Node("growth","GROWTH",Color.rgb(145,245,35),.12f,.53f),Node("knowledge","KNOWLEDGE",Color.rgb(255,210,40),.88f,.53f),Node("analysis","ANALYSIS",Color.rgb(30,205,255),.28f,.76f),Node("watchlist","WATCHLIST",Color.rgb(255,220,35),.72f,.76f))
    private data class Node(val key:String,val label:String,val color:Int,val x:Float,val y:Float)
    override fun onDraw(c:Canvas){
        val w=width.toFloat();val h=height.toFloat();val d=resources.displayMetrics.density;val base=minOf(w,h);val cx=w*.5f;val cy=h*.48f;val r=base*.215f;val nr=base*.105f
        c.drawColor(Color.rgb(1,2,6));p.style=Paint.Style.FILL
        for(i in 0 until 55){val x=((i*83+37)%1000)/1000f*w;val y=((i*149+91)%1000)/1000f*h*.94f;p.color=Color.argb(55+(i%5)*18,255,205,80);c.drawCircle(x,y,(.7f+(i%3)*.5f)*d,p)}
        p.style=Paint.Style.STROKE
        for(i in 1..6){p.strokeWidth=if(i==1)2.2f*d else .9f*d;p.color=Color.argb(105-i*12,255,190,35);c.drawCircle(cx,cy,r*(1f+i*.43f),p)}
        for(n in nodes){val x=w*n.x;val y=h*n.y;p.strokeWidth=.9f*d;p.color=Color.argb(125,255,205,55);c.drawLine(cx,cy,x,y,p);drawNode(c,x,y,nr,n,d);p.style=Paint.Style.FILL;p.color=Color.rgb(255,205,55);c.drawCircle(x,y-nr*1.02f,2.4f*d,p)}
        p.style=Paint.Style.FILL;p.shader=LinearGradient(cx-r,cy-r,cx+r,cy+r,Color.rgb(255,226,90),Color.rgb(238,145,10),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,r*1.10f,p);p.shader=null;p.color=Color.rgb(5,6,10);c.drawCircle(cx,cy,r*.98f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.2f*d;p.color=Color.argb(190,255,210,60);c.drawCircle(cx,cy,r*.90f,p);p.strokeWidth=1f*d;p.color=Color.argb(100,255,190,35);c.drawCircle(cx,cy,r*1.27f,p)
        val chart=Path();chart.moveTo(cx-r*.66f,cy+r*.57f);val pts=arrayOf(.00f to .08f,.10f to .02f,.20f to .16f,.30f to .05f,.40f to .20f,.50f to .12f,.60f to .34f,.70f to .22f,.80f to .45f,.90f to .39f,1f to .64f);for((x,y)in pts)chart.lineTo(cx-r*.66f+r*1.32f*x,cy+r*.57f-r*.40f*y);p.color=Color.rgb(255,195,35);p.strokeWidth=1.6f*d;c.drawPath(chart,p)
        p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.create(Typeface.SERIF,Typeface.BOLD);p.color=Color.WHITE;p.textSize=r*.29f;c.drawText("ORACLE",cx,cy+r*.10f,p);p.textSize=r*.105f;p.color=Color.rgb(255,205,65);c.drawText("STOCK INTELLIGENCE",cx,cy+r*.32f,p);p.typeface=Typeface.DEFAULT_BOLD;p.textSize=r*.38f;p.color=Color.rgb(255,205,35);c.drawText("↗",cx,cy-r*.17f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.4f*d;p.color=Color.rgb(125,100,35);c.drawRoundRect(4*d,10*d,48*d,54*d,10*d,10*d,p)
        p.style=Paint.Style.FILL;p.textSize=base*.034f;p.color=Color.WHITE;c.drawText("ORACLE",cx,base*.055f,p);p.textSize=base*.018f;p.color=Color.rgb(170,150,90);c.drawText("STOCK INTELLIGENCE",cx,base*.082f,p)
    }
    private fun drawNode(c:Canvas,x:Float,y:Float,rad:Float,n:Node,d:Float){
        p.style=Paint.Style.FILL;p.color=Color.argb(244,3,6,13);c.drawCircle(x,y,rad,p);p.style=Paint.Style.STROKE;p.strokeWidth=2.0f*d;p.color=n.color;c.drawCircle(x,y,rad,p);p.style=Paint.Style.FILL;p.color=n.color;c.drawCircle(x,y-rad*.72f,rad*.038f,p)
        drawIcon(c,x,y-rad*.25f,rad*.29f,n.key,n.color,d);p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.DEFAULT_BOLD;p.textSize=rad*.27f;p.color=n.color;c.drawText(n.label,x,y+rad*.17f,p);p.textSize=rad*.105f;p.color=Color.WHITE
        val desc=when(n.key){"portfolio"->"Performance and positions";"alerts"->"Signals and events";"news"->"Financial news";"growth"->"High-potential stocks";"knowledge"->"Ideas and documentation";"analysis"->"Detailed analysis";else->"Favorite stocks"};c.drawText(desc,x,y+rad*.42f,p);p.textSize=rad*.28f;c.drawText("›",x,y+rad*.73f,p)
    }
    private fun drawIcon(c:Canvas,x:Float,y:Float,s:Float,key:String,color:Int,d:Float){
        p.style=Paint.Style.STROKE;p.strokeWidth=1.8f*d;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=color
        when(key){"portfolio"->{c.drawCircle(x,y,s*.62f,p);c.drawLine(x,y,x,y-s*.62f,p);c.drawLine(x,y,x+s*.48f,y+s*.28f,p)}"alerts"->{c.drawArc(x-s*.48f,y-s*.35f,x+s*.48f,y+s*.42f,205f,130f,false,p);c.drawLine(x-s*.58f,y+s*.42f,x+s*.58f,y+s*.42f,p);c.drawCircle(x,y+s*.62f,s*.07f,p)}"news"->{c.drawRect(x-s*.58f,y-s*.55f,x+s*.58f,y+s*.55f,p);c.drawLine(x-s*.35f,y-s*.20f,x+s*.35f,y-s*.20f,p);c.drawLine(x-s*.35f,y+.02f,x+s*.35f,y+.02f,p);c.drawLine(x-s*.35f,y+s*.24f,x+s*.15f,y+s*.24f,p)}"growth"->{c.drawLine(x-s*.58f,y+s*.38f,x-s*.18f,y,p);c.drawLine(x-s*.18f,y,x+s*.12f,y+s*.20f,p);c.drawLine(x+s*.12f,y+s*.20f,x+s*.58f,y-s*.42f,p);c.drawLine(x+s*.30f,y-s*.42f,x+s*.58f,y-s*.42f,p);c.drawLine(x+s*.58f,y-s*.42f,x+s*.58f,y-s*.12f,p)}"knowledge"->{c.drawRect(x-s*.52f,y-s*.60f,x+s*.52f,y+s*.60f,p);c.drawLine(x-s*.28f,y-s*.26f,x+s*.28f,y-s*.26f,p);c.drawLine(x-s*.28f,y,x+s*.28f,y,p);c.drawLine(x-s*.28f,y+s*.26f,x+s*.20f,y+s*.26f,p)}"analysis"->{c.drawCircle(x,y,s*.48f,p);c.drawLine(x+s*.35f,y+s*.35f,x+s*.65f,y+s*.65f,p);c.drawLine(x-s*.20f,y+s*.08f,x-s*.02f,y-s*.12f,p);c.drawLine(x-s*.02f,y-s*.12f,x+s*.20f,y+s*.12f,p)}else->{c.drawCircle(x,y,s*.56f,p);c.drawLine(x-s*.38f,y-s*.05f,x+s*.38f,y-s*.05f,p);c.drawLine(x-s*.28f,y+s*.18f,x+s*.28f,y+s*.18f,p)}}
    }
    override fun onTouchEvent(e:MotionEvent):Boolean{if(e.action!=MotionEvent.ACTION_UP)return true;val w=width.toFloat();val h=height.toFloat();val base=minOf(w,h);val nr=base*.105f;val nodesLocal=nodes.map{it to (w*it.x to h*it.y)};val hit=nodesLocal.minByOrNull{val dx=e.x-it.second.first;val dy=e.y-it.second.second;dx*dx+dy*dy};if(hit!=null){val dx=e.x-hit.second.first;val dy=e.y-hit.second.second;if(dx*dx+dy*dy<nr*nr){onModule(hit.first.key);performClick();return true}};return true}
    override fun performClick():Boolean{super.performClick();return true}
}

package ro.alintudor.oracle.nativeui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import ro.alintudor.oracle.core.OracleNews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** News UI: economic/stock-market stories, grouped by publisher, with fixed search. */
class OracleNewsModule(private val host: OracleNativeModule) {
    private val time = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Bucharest") }
    private val sourceOrder = listOf("CNBC","BBC Business","Financial Times","Bloomberg","MarketWatch","The Wall Street Journal","The New York Times Business","Reuters","Investing.com","Google News • Markets")
    private var allNews: List<OracleNews> = emptyList()
    private var query = ""
    private var search: EditText? = null

    /** Every render advances the article rotation, while sourceOrder stays
     *  immutable — but only on a genuine fresh navigation or explicit refresh
     *  tap. A silent background refresh must never visibly reshuffle a list
     *  the user is already looking at, so it skips the rotation shift and
     *  produces the same deterministic order for the same input every time. */
    fun render(news: List<OracleNews>, silent: Boolean = false) {
        val incoming = dedupe(news).filter(::isEconomic).sortedByDescending { it.publishedAt }
        allNews = rotateArticlesWithinSources(incoming, silent)
        renderFiltered()
    }

    private fun rotateArticlesWithinSources(items: List<OracleNews>, silent: Boolean = false): List<OracleNews> {
        val cycle = if (silent) rotationCycle else synchronized(OracleNewsModule::class.java) {
            rotationCycle = (rotationCycle + 1) and Int.MAX_VALUE
            rotationCycle
        }
        val grouped = items.groupBy { sourceName(it) }
        return grouped.flatMap { (_, sourceItems) ->
            val ordered = sourceItems.sortedWith(compareByDescending<OracleNews> { it.breaking }.thenByDescending { it.publishedAt })
            if (ordered.size <= 1) ordered
            else {
                // BREAKING NEWS stays first; normal stories rotate by one slot per render.
                val breaking = ordered.filter { it.breaking }
                val normal = ordered.filterNot { it.breaking }
                val shift = cycle % normal.size
                val shifted = normal.drop(shift) + normal.take(shift)
                breaking + shifted
            }
        }
    }

    private fun renderFiltered() {
        host.fixedToolbar.removeAllViews()
        addSearchBar()
        host.content.rebuildWithoutFlicker {
            host.content.removeAllViews()
            val q = query.trim().lowercase(Locale.US)
            val clean = if (q.isBlank()) allNews else allNews.filter { searchable(it).contains(q) }
            if (clean.isEmpty()) {
                host.addCard("ECONOMIC NEWS", if (q.isBlank()) "No economic news available right now." else "No economic news for “$query”.")
                return@rebuildWithoutFlicker
            }
            host.addCard("ECONOMIC NEWS • MARKETS", "Markets, stocks, indices, earnings, Fed, rates, M&A and catalysts — organized by source.")
            renderGroups(clean)
        }
    }

    private fun renderGroups(clean: List<OracleNews>) {
        val groups = clean.groupBy { sourceName(it) }
        var index = 0
        sourceOrder.filter { groups.containsKey(it) }.forEach { source -> addSource(source, groups.getValue(source), index++) }
        groups.keys.filterNot { sourceOrder.contains(it) }.sorted().forEach { source -> addSource(source, groups.getValue(source), index++) }
    }

    private fun addSearchBar() {
        val field = EditText(host.root.context).apply {
            hint = "Search stocks, ticker, company, Fed, markets…"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setHorizontallyScrolling(true)
            setText(query)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(125,140,165))
            setPadding(host.dp(15), 0, host.dp(15), 0)
            background = GradientDrawable().apply { setColor(Color.rgb(9,15,29)); cornerRadius=host.dp(13).toFloat(); setStroke(host.dp(1),Color.rgb(25,205,255)) }
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query=s?.toString().orEmpty(); refreshResultsOnly() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        search = field
        host.fixedToolbar.addView(field, LinearLayout.LayoutParams(-1,host.dp(48)).apply{setMargins(0,0,0,host.dp(6))})
    }

    private fun refreshResultsOnly() {
        query = search?.text?.toString().orEmpty()
        host.content.rebuildWithoutFlicker {
            host.content.removeAllViews()
            val q=query.trim().lowercase(Locale.US)
            val clean=if(q.isBlank())allNews else allNews.filter{searchable(it).contains(q)}
            if(clean.isEmpty()){host.addCard("ECONOMIC NEWS","No economic news for “$query”.");return@rebuildWithoutFlicker}
            host.addCard("ECONOMIC NEWS • MARKETS","${clean.size} articles found")
            renderGroups(clean)
        }
    }

    private fun searchable(n: OracleNews): String = listOf(n.ticker,n.title,n.publisher,n.source).joinToString(" ").lowercase(Locale.US)
    private fun dedupe(items: List<OracleNews>): List<OracleNews> = items.filter { it.title.isNotBlank() }.groupBy { key(it) }.values.mapNotNull { it.maxByOrNull { n -> n.publishedAt } }
    private fun key(n: OracleNews): String = "title:" + n.title.trim().lowercase(Locale.US).replace(Regex("\\s+")," ").replace(Regex("[^a-z0-9 ]"),"")
    private fun sourceName(n: OracleNews): String = n.publisher.ifBlank { n.source }.ifBlank { "Other News" }

    private fun isEconomic(n: OracleNews): Boolean {
        val text=(n.title+" "+n.publisher+" "+n.source).lowercase(Locale.US)
        val keywords=listOf("stock","stocks","share","shares","equity","equities","market","markets","nasdaq","nyse","s&p","dow","index","indices","earnings","revenue","profit","loss","guidance","ipo","merger","acquisition","m&a","fed","federal reserve","interest rate","inflation","cpi","ppi","gdp","jobs","payroll","treasury","bond","yield","forex","currency","oil","gold","silver","bitcoin","crypto","investor","investing","wall street","business","finance","financial","economy","economic","tariff","trade","bank","banks","semiconductor","ai","technology","energy")
        return keywords.any{text.contains(it)}
    }

    private fun addSource(source:String,items:List<OracleNews>,index:Int=0){
        val accent=sourceAccent(source)
        val boxBg=GradientDrawable().apply{setColor(Color.rgb(9,15,29));cornerRadius=host.dp(16).toFloat();setStroke(host.dp(1),accent)}
        val box=LinearLayout(host.root.context).apply{orientation=LinearLayout.VERTICAL;background=boxBg;setPadding(host.dp(14),host.dp(13),host.dp(14),host.dp(12))}
        box.addView(TextView(host.root.context).apply{text=source;textSize=19f;typeface=Typeface.DEFAULT_BOLD;setTextColor(accent);setPadding(host.dp(2),0,0,host.dp(8))})
        // Keep the order produced by rotateArticlesWithinSources. Do NOT sort here:
        // sorting here would undo the visible refresh rotation.
        items.take(8).forEach{n->addStory(box,n,accent)}
        box.addView(TextView(host.root.context).apply{text="SEE $source  →";textSize=11f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setTextColor(Color.WHITE);background=GradientDrawable().apply{setColor(Color.rgb(18,34,58));cornerRadius=host.dp(11).toFloat()};setPadding(0,host.dp(10),0,host.dp(10));isClickable=true;if(items.firstOrNull()?.url?.isNotBlank()==true)setOnClickListener{open(items.first().url)}},LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,host.dp(8),0,0)})
        host.content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,host.dp(12))})
        box.alpha=0f; box.translationY=host.dp(22).toFloat()
        box.animate().alpha(1f).translationY(0f).setStartDelay(index*110L).setDuration(380L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        val strokePx=host.dp(1); val ar=Color.red(accent); val ag=Color.green(accent); val ab=Color.blue(accent)
        android.animation.ValueAnimator.ofFloat(0f,1f,0f).apply{
            duration=2000L; startDelay=index*160L; repeatCount=android.animation.ValueAnimator.INFINITE
            addUpdateListener{ anim->
                if(!box.isAttachedToWindow){anim.cancel();return@addUpdateListener}
                val q=anim.animatedValue as Float
                boxBg.setStroke((strokePx*(1f+0.7f*q)).toInt().coerceAtLeast(1),Color.argb((140+105*q).toInt(),ar,ag,ab))
            }
        }.start()
    }

    private fun addStory(box:LinearLayout,n:OracleNews,accent:Int){
        val row=LinearLayout(host.root.context).apply{orientation=LinearLayout.VERTICAL;setPadding(host.dp(3),host.dp(9),host.dp(3),host.dp(9));isClickable=n.url.isNotBlank();if(isClickable)setOnClickListener{open(n.url)}}
        val top=LinearLayout(host.root.context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}

        val title=TextView(host.root.context).apply{
            text=n.title
            textSize=15f
            typeface=Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(232,237,248))
            setLineSpacing(0f,1.05f)
        }
        top.addView(title, LinearLayout.LayoutParams(0,-2,1f))

        if(n.breaking) {
            val badge = TextView(host.root.context).apply {
                text = "BREAKING NEWS"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(255,25,25))
                gravity = Gravity.CENTER
                setPadding(host.dp(7), host.dp(3), host.dp(7), host.dp(3))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(45,8,8))
                    cornerRadius = host.dp(6).toFloat()
                    setStroke(host.dp(1), Color.rgb(255,25,25))
                }
            }
            top.addView(badge, LinearLayout.LayoutParams(host.dp(92), -2).apply {
                setMargins(host.dp(8), 0, 0, 0)
            })
            pulseBreaking(badge)
        }

        row.addView(top)
        val meta=buildList{if(n.publishedAt>0)add(time.format(Date(n.publishedAt)));if(n.sentimentScore!=null)add("Sent %+.2f".format(n.sentimentScore));if(n.relevanceScore>0)add("Rel %.0f".format(n.relevanceScore))}.joinToString("  •  ")
        if(meta.isNotBlank())row.addView(TextView(host.root.context).apply{text=meta;textSize=10f;setTextColor(Color.rgb(132,145,170));setPadding(0,host.dp(3),0,0)})
        box.addView(row)
        box.addView(TextView(host.root.context).apply{setBackgroundColor(Color.rgb(31,43,64))},LinearLayout.LayoutParams(-1,1))
    }

    private fun pulseBreaking(view: TextView) {
        fun loop() {
            view.animate().alpha(0.25f).setDuration(500).withEndAction {
                view.animate().alpha(1f).setDuration(500).withEndAction {
                    if (view.parent != null) loop()
                }.start()
            }.start()
        }
        loop()
    }

    private fun open(url:String){runCatching{host.root.context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}}
    private fun sourceAccent(source:String)=when{source.contains("CNBC",true)->Color.rgb(40,150,255);source.contains("BBC",true)->Color.rgb(235,40,60);source.contains("Financial Times",true)->Color.rgb(30,190,165);source.contains("Bloomberg",true)->Color.rgb(145,70,245);source.contains("MarketWatch",true)->Color.rgb(35,150,245);source.contains("Wall Street",true)->Color.rgb(70,90,120);source.contains("York Times",true)->Color.rgb(215,165,50);source.contains("Reuters",true)->Color.rgb(235,190,40);else->host.accent}

    companion object {
        private var rotationCycle: Int = 0
    }
}

package ro.alintudor.oracle.nativeui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import android.widget.FrameLayout
import ro.alintudor.oracle.core.OracleMarketCalendar

/** Shared Oracle module shell. Header semantics are fixed: left=Back, right=Refresh. */
class OracleNativeModule(
    private val context: Context,
    private val title: String,
    private val onBack: () -> Unit = {},
    private val onRefresh: () -> Unit = {}
) {
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(1,3,8)); setPadding(dp(10),0,dp(10),0) }
    val fixedToolbar = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2),0,dp(2),dp(4)) }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2),dp(10),dp(2),dp(24)) }
    val accent = when(title.uppercase()) { "ALERTS" -> Color.rgb(255,75,40); "NEWS","ANALYSIS" -> Color.rgb(25,205,255); "GROWTH" -> Color.rgb(145,245,35); "PORTFOLIO" -> Color.rgb(190,65,255); else -> Color.rgb(255,210,45) }
    private lateinit var scrollView: ScrollView
    private var marketStatusView: TextView? = null
    private val marketStatusHandler = Handler(Looper.getMainLooper())
    private val marketStatusUpdater = object : Runnable {
        override fun run() {
            val view = marketStatusView ?: return
            val status = OracleMarketCalendar.status()
            view.text = "${if (status.open) "☀" else "☾"}  ${status.label}\n${status.countdown}"
            view.setTextColor(if (status.open) Color.rgb(90,245,135) else Color.rgb(255,85,95))
            view.background = rounded(Color.rgb(7,11,22), dp(9), if (status.open) Color.rgb(55,130,80) else Color.rgb(145,55,65), dp(1))
            marketStatusHandler.postDelayed(this, 30_000L)
        }
    }

    init {
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2),dp(5),dp(2),dp(5)) }
        header.addView(button("‹","Back",Color.rgb(255,205,45)) { onBack() }, LinearLayout.LayoutParams(dp(46),dp(46)))
        val center = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER }
        center.addView(TextView(context).apply { text="ORACLE";textSize=21f;typeface=Typeface.create(Typeface.SERIF,Typeface.BOLD);setTextColor(Color.WHITE);gravity=Gravity.CENTER;includeFontPadding=true })
        center.addView(TextView(context).apply { text=title;textSize=11f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.18f;setTextColor(accent);gravity=Gravity.CENTER;includeFontPadding=true })
        center.addView(TextView(context).apply { text=ro.alintudor.oracle.core.OracleBuildInfo.label(title);textSize=10f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.10f;setTextColor(Color.rgb(25,205,255));gravity=Gravity.CENTER;includeFontPadding=true })
        header.addView(center,LinearLayout.LayoutParams(0,dp(76),1f))
        header.addView(button("↻","Refresh",Color.rgb(255,205,45)) { onRefresh() }, LinearLayout.LayoutParams(dp(46),dp(46)))
        root.addView(header,LinearLayout.LayoutParams(-1,dp(84)))
        root.addView(View(context).apply{setBackgroundColor(accent)},LinearLayout.LayoutParams(-1,dp(1)).apply{setMargins(dp(6),0,dp(6),dp(5))})
        if (title.equals("GROWTH", true)) {
            val statusView = TextView(context).apply {
                val status = OracleMarketCalendar.status()
                text = "${if (status.open) "☀" else "☾"}  ${status.label}\n${status.countdown}"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = .04f
                gravity = Gravity.CENTER
                setTextColor(if (status.open) Color.rgb(90,245,135) else Color.rgb(255,85,95))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = rounded(Color.rgb(7,11,22), dp(9), if (status.open) Color.rgb(55,130,80) else Color.rgb(145,55,65), dp(1))
            }
            marketStatusView = statusView
            fixedToolbar.addView(statusView, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(dp(4),0,dp(4),dp(4)) })
            marketStatusHandler.post(marketStatusUpdater)
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { marketStatusHandler.removeCallbacks(marketStatusUpdater) }
            })
        }
        root.addView(fixedToolbar,LinearLayout.LayoutParams(-1,-2))
        scrollView = ScrollView(context).apply {
            clipToPadding=false; isFillViewport=true; overScrollMode=View.OVER_SCROLL_ALWAYS; isNestedScrollingEnabled=false; addView(content)
            setOnScrollChangeListener { _, _, scrollY, _, _ -> scrollPositions[title] = scrollY }
        }
        if (ro.alintudor.oracle.core.OracleDemo.active(context)) {
            root.addView(TextView(context).apply {
                text = "DEMO  \u00b7  ${ro.alintudor.oracle.core.OracleDemo.LOCK} locked values \u2014 create an account for full access"
                textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .06f; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 205, 45))
                setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(Color.rgb(20, 16, 6), dp(10), Color.rgb(255, 205, 45), dp(1))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(8)) })
        }
        root.addView(scrollView, LinearLayout.LayoutParams(-1,0,1f))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else 0
            val bottom = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
            root.setPadding(dp(10), top + dp(16), dp(10), bottom); content.setPadding(dp(2),dp(10),dp(2),bottom + dp(32)); insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.requestApplyInsets() }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        scrollView.post { scrollView.scrollTo(0, scrollPositions[title] ?: 0) }
    }

    fun getScrollY(): Int = if (::scrollView.isInitialized) scrollView.scrollY else 0
    fun restoreScrollY(value: Int) { if (!::scrollView.isInitialized) return; scrollPositions[title] = value.coerceAtLeast(0); scrollView.post { scrollView.scrollTo(0, value.coerceAtLeast(0)) } }

    private fun button(symbol:String,desc:String,color:Int,click:()->Unit)=TextView(context).apply{ text=symbol;textSize=30f;gravity=Gravity.CENTER;contentDescription=desc;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);background=rounded(Color.rgb(5,8,17),dp(13),color,dp(1));isClickable=true;isFocusable=true;setOnClickListener{click()} }
    fun addCard(heading:String,body:String){
        val card=LinearLayout(context).apply{ orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=rounded(Color.rgb(7,11,22),dp(15),Color.rgb(42,52,76),dp(1)) }
        card.addView(TextView(context).apply{text=heading.uppercase();textSize=17f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.04f;setTextColor(Color.WHITE)})
        card.addView(TextView(context).apply{text=body;textSize=14f;setTextColor(Color.rgb(175,182,198));setPadding(0,dp(7),0,0)})
        content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,dp(10))})
    }
    fun addSectionLabel(text:String,sectionAccent:Int=accent){content.addView(TextView(context).apply{this.text=text.uppercase();textSize=11f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.14f;setTextColor(sectionAccent);setPadding(dp(5),dp(8),dp(5),dp(7))})}
    fun dp(v:Int)= (v*context.resources.displayMetrics.density).toInt()
    companion object{
        private val scrollPositions = mutableMapOf<String, Int>()
        fun rememberedScroll(title:String): Int = scrollPositions[title] ?: 0
        fun rounded(fill:Int,radius:Int,stroke:Int=Color.TRANSPARENT,strokeWidth:Int=0)=GradientDrawable().apply{setColor(fill);cornerRadius=radius.toFloat();if(strokeWidth>0)setStroke(strokeWidth,stroke)}
    }
}
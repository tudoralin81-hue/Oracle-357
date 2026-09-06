package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import ro.alintudor.oracle.core.OracleAlert
import ro.alintudor.oracle.core.OracleDemo
import ro.alintudor.oracle.core.OracleUserAlert
import ro.alintudor.oracle.core.OracleUserAlertStore
import ro.alintudor.oracle.core.OracleTickerScoreCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rich native alert center. Shows the plain BUY/SELL signal alerts plus the
 *  three critical conditions (urgent sell, fading growth, high volatility)
 *  that also push-notify and email automatically, using the account email
 *  set at registration — nothing to configure here. */
class OracleAlertsModule(private val host: OracleNativeModule) {
    private val context get() = host.root.context
    private val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    private var lastAlerts: List<OracleAlert> = emptyList()

    fun render(alerts: List<OracleAlert>) {
        lastAlerts = alerts
        host.content.rebuildWithoutFlicker {
            host.content.removeAllViews()
            val active = alerts.filter { it.active }
            val high = active.count { it.level.equals("HIGH", true) }
            val medium = active.count { it.level.equals("MEDIUM", true) }
            host.addCard("ALERT CENTER", "Everything that can notify you, grouped by what it actually is: your own alert rules, urgent conditions Lux Oculi watches automatically, and BUY/SELL/REDUCE signals — split by whether they're on something you own (Portfolio) or just watching (Watchlist).")
            addSummary(active.size, high, medium, alerts.size - active.size)
            addMyAlerts()

            // Five clearly separate groups instead of two vague ones — a
            // personal rule firing isn't the same thing as an urgent
            // condition Oracle detected on its own, and a Watchlist signal
            // isn't the same thing as a signal on money you actually hold.
            val trueCritical = alerts.filter { it.kind == "URGENT_SELL" || it.kind == "GROWTH_FADING" || it.kind == "HIGH_VOLATILITY" }
            val userFired = alerts.filter { it.kind == "USER" }
            val signalAll = alerts.filter { it.kind == "SIGNAL" }
            val watchlistSignals = signalAll.filter { it.title.endsWith("(Watchlist)") }
            val portfolioSignals = signalAll - watchlistSignals.toSet()

            if (alerts.isEmpty()) { host.addCard("NO ALERTS", "Nothing has fired yet."); return@rebuildWithoutFlicker }

            fun section(label: String, color: Int, list: List<OracleAlert>, critical: Boolean) {
                if (list.isEmpty()) return
                host.addSectionLabel(label, color)
                list.sortedWith(compareByDescending<OracleAlert> { it.active }.thenByDescending { severityRank(it.level) }.thenByDescending { it.timestamp }).forEach { addAlert(it, critical = critical) }
            }
            section("URGENT — PUSH + EMAIL", Color.rgb(255, 90, 90), trueCritical, critical = true)
            section("YOUR ALERTS \u2014 FIRED", Color.rgb(80, 200, 255), userFired, critical = true)
            section("PORTFOLIO SIGNALS", host.accent, portfolioSignals, critical = false)
            section("WATCHLIST SIGNALS", Color.rgb(255, 170, 40), watchlistSignals, critical = false)
        }
    }

    private fun addMyAlerts() {
        val store = OracleUserAlertStore(context)
        val mine = store.load()
        host.addSectionLabel("YOUR ALERT RULES \u2022 ${mine.size}", host.accent)
        mine.sortedWith(compareByDescending<OracleUserAlert> { it.enabled }.thenByDescending { it.createdAt }).forEach { a ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(host.dp(14), host.dp(10), host.dp(10), host.dp(10))
                background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(12), if (a.enabled) host.accent else Color.rgb(45, 55, 75), host.dp(1))
            }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply { text = "${a.ticker}  \u00b7  ${a.describe()}"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (a.enabled) Color.WHITE else Color.rgb(140, 150, 172)) })
            val now = OracleTickerScoreCache.get(context, a.ticker)
            val status = when {
                !a.enabled && a.lastFiredAt > 0L -> "Fired ${date.format(Date(a.lastFiredAt))} \u2014 disarmed"
                a.type == "SIGNAL_CHANGE" -> if (now != null) "Armed \u00b7 now ${now.signal} (score ${now.score})" else "Armed \u00b7 waiting for first score"
                a.type.startsWith("SCORE") -> if (now != null) "Armed \u00b7 now score ${now.score}" else "Armed"
                else -> if (now != null) "Armed \u00b7 now ${String.format(Locale.US, "%.2f", now.price)}" else "Armed"
            }
            col.addView(TextView(context).apply { text = status; textSize = 11f; setTextColor(Color.rgb(150, 160, 182)); setPadding(0, host.dp(3), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(context).apply {
                text = "\u2715"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.rgb(255, 105, 105))
                setPadding(host.dp(12), host.dp(6), host.dp(8), host.dp(6)); isClickable = true; isFocusable = true
                setOnClickListener { store.remove(a.id); render(lastAlerts) }
            })
            host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) })
        }
        host.content.addView(TextView(context).apply {
            text = "+ ADD ALERT"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(host.accent)
            background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), host.dp(11), host.accent, host.dp(1)); isClickable = true; isFocusable = true
            setOnClickListener { if (OracleDemo.active(context)) android.widget.Toast.makeText(context, "${OracleDemo.LOCK} Personal alerts need an account", android.widget.Toast.LENGTH_SHORT).show() else addAlertDialog() }
        }, LinearLayout.LayoutParams(-1, host.dp(44)).apply { setMargins(0, 0, 0, host.dp(12)) })
    }

    private fun addAlertDialog() {
        val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val types = listOf("PRICE_ABOVE" to "Price above", "PRICE_BELOW" to "Price below", "SCORE_ABOVE" to "Growth score \u2265", "SCORE_BELOW" to "Growth score \u2264", "SIGNAL_CHANGE" to "Signal changes")
        var selected = types[0].first
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(host.dp(20), host.dp(14), host.dp(20), host.dp(6)); setBackgroundColor(panel) }
        fun field(hint: String, type: Int) = EditText(context).apply {
            this.hint = hint; inputType = type; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(120, 130, 152)); textSize = 15f
            background = OracleNativeModule.rounded(Color.rgb(4, 8, 16), host.dp(10), border, host.dp(1)); setPadding(host.dp(12), host.dp(11), host.dp(12), host.dp(11))
        }
        val ticker = field("Ticker (e.g. NVDA)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val threshold = field("Level (price or score)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        box.addView(ticker, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
        val typeRow = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val buttons = ArrayList<TextView>()
        types.forEach { (key, label) ->
            val b = TextView(context).apply {
                text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, host.dp(9), 0, host.dp(9)); isClickable = true; isFocusable = true
                setOnClickListener { selected = key; buttons.forEach { it.setTextColor(Color.rgb(150, 160, 182)); it.background = OracleNativeModule.rounded(panel, host.dp(9), border, host.dp(1)) }
                    setTextColor(host.accent); background = OracleNativeModule.rounded(panel, host.dp(9), host.accent, host.dp(1)); threshold.visibility = if (key == "SIGNAL_CHANGE") android.view.View.GONE else android.view.View.VISIBLE }
            }
            buttons += b; typeRow.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(6)) })
        }
        buttons[0].performClick()
        box.addView(typeRow)
        box.addView(threshold, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(4), 0, 0) })
        AlertDialog.Builder(context).setTitle("New alert").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val t = ticker.text.toString().trim().uppercase(Locale.US)
                val v = threshold.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
                if (t.isBlank() || (selected != "SIGNAL_CHANGE" && v <= 0.0)) { android.widget.Toast.makeText(context, "Enter a ticker and a level", android.widget.Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                OracleUserAlertStore(context).add(t, selected, v)
                Thread { runCatching { OracleTickerScoreCache.refresh(context, listOf(t), maxFetches = 1) }; host.root.post { if (host.root.isAttachedToWindow) render(lastAlerts) } }.start()
                render(lastAlerts)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addSummary(active:Int,high:Int,medium:Int,closed:Int){
        val row=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
        stat(row,"ACTIVE",active.toString(),Color.rgb(145,245,35));stat(row,"HIGH",high.toString(),Color.rgb(255,75,60));stat(row,"MEDIUM",medium.toString(),Color.rgb(255,205,45));stat(row,"CLOSED",closed.toString(),Color.rgb(140,150,170))
        host.content.addView(row,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,0,0,host.dp(10))})
    }
    private fun stat(row:LinearLayout,label:String,value:String,color:Int){
        val box=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(host.dp(10),host.dp(11),host.dp(10),host.dp(11));background=GradientDrawable().apply{setColor(Color.rgb(7,11,22));cornerRadius=host.dp(11).toFloat();setStroke(host.dp(1),Color.rgb(35,44,66))}}
        box.addView(TextView(context).apply{text=label;textSize=9f;setTextColor(Color.rgb(145,155,176));gravity=Gravity.CENTER})
        box.addView(TextView(context).apply{text=value;textSize=18f;typeface=Typeface.DEFAULT_BOLD;setTextColor(color);gravity=Gravity.CENTER;setPadding(0,host.dp(2),0,0)})
        row.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(host.dp(3),0,host.dp(3),0)})
    }

    private fun kindLabel(kind: String) = when (kind) {
        "URGENT_SELL" -> "🚨 URGENT SELL"
        "GROWTH_FADING" -> "📈 RALLY MAY FADE"
        "HIGH_VOLATILITY" -> "⚡ HIGH VOLATILITY"
        "USER" -> "MY ALERT"
        else -> kind
    }

    private fun addAlert(a: OracleAlert, critical: Boolean) {
        val active = a.active
        val accent = when {
            a.kind == "URGENT_SELL" -> Color.rgb(255, 60, 60)
            a.kind == "GROWTH_FADING" -> Color.rgb(255, 205, 45)
            a.kind == "HIGH_VOLATILITY" -> Color.rgb(255, 150, 45)
            a.kind == "USER" -> Color.rgb(80, 200, 255)
            a.level.equals("HIGH", true) -> Color.rgb(255, 75, 60)
            a.level.equals("MEDIUM", true) -> Color.rgb(255, 205, 45)
            else -> Color.rgb(70, 185, 255)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(13), host.dp(13), host.dp(13))
            background = GradientDrawable().apply { setColor(Color.rgb(6, 10, 20)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(if (critical) 2 else 1), if (active) accent else Color.rgb(38, 46, 66)) }
        }
        if (critical) {
            card.addView(TextView(context).apply {
                text = kindLabel(a.kind); textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); letterSpacing = .04f
                setPadding(0, 0, 0, host.dp(4))
            })
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply { text = "●"; textSize = 11f; setTextColor(accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(host.dp(22), host.dp(25)))
        top.addView(TextView(context).apply { text = a.ticker.uppercase(Locale.getDefault()); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(context).apply { text = if (active) "ACTIVE" else "CLOSED"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(host.dp(60), host.dp(25)))
        card.addView(top)
        card.addView(TextView(context).apply { text = a.title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(host.dp(22), host.dp(5), 0, 0) })
        card.addView(TextView(context).apply { text = a.message; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(host.dp(22), host.dp(4), 0, 0) })
        if (a.timestamp > 0L) card.addView(TextView(context).apply { text = date.format(Date(a.timestamp)); textSize = 10f; setTextColor(Color.rgb(125, 137, 158)); setPadding(host.dp(22), host.dp(7), 0, 0) })
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })
    }
    private fun severityRank(level:String)=when(level.uppercase(Locale.getDefault())){"HIGH"->3;"MEDIUM"->2;else->1}
}

package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ro.alintudor.oracle.core.OracleAlert
import ro.alintudor.oracle.core.OracleAlertMailer
import ro.alintudor.oracle.core.OracleAlertSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rich native alert center. Shows the plain BUY/SELL signal alerts plus the
 *  three critical conditions (urgent sell, fading growth, high volatility)
 *  that also push-notify and can email — with an email address registered
 *  here, locally, on the device. */
class OracleAlertsModule(private val host: OracleNativeModule) {
    private val context get() = host.root.context
    private val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val settings by lazy { OracleAlertSettingsStore(context) }

    fun render(alerts: List<OracleAlert>) {
        host.content.removeAllViews()
        val active = alerts.filter { it.active }
        val high = active.count { it.level.equals("HIGH", true) }
        val medium = active.count { it.level.equals("MEDIUM", true) }
        host.addCard("SELL ALERTS", "Oracle local alert center")
        addEmailSettings()
        addSummary(active.size, high, medium, alerts.size - active.size)
        if (alerts.isEmpty()) { host.addCard("NO ALERTS", "There are no alerts in the local data."); return }

        val critical = alerts.filter { it.kind != "SIGNAL" }
        val plain = alerts.filter { it.kind == "SIGNAL" }
        if (critical.isNotEmpty()) {
            host.addSectionLabel("CRITICAL — PUSH + EMAIL", Color.rgb(255, 90, 90))
            critical.sortedWith(compareByDescending<OracleAlert> { it.active }.thenByDescending { it.timestamp }).forEach { addAlert(it, critical = true) }
            host.addSectionLabel("SIGNAL ALERTS", host.accent)
        }
        plain.sortedWith(compareByDescending<OracleAlert>{it.active}.thenByDescending{severityRank(it.level)}.thenByDescending{it.timestamp}).take(100).forEach { addAlert(it, critical = false) }
    }

    private fun addEmailSettings() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13))
            background = GradientDrawable().apply { setColor(Color.rgb(7, 11, 22)); cornerRadius = host.dp(14).toFloat(); setStroke(host.dp(1), Color.rgb(35, 44, 66)) }
        }
        card.addView(TextView(context).apply { text = "ALERT EMAIL"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(150, 170, 205)); letterSpacing = .05f })
        card.addView(TextView(context).apply {
            text = "Critical alerts (urgent sell, fading rally, high volatility) push-notify automatically. If an email is set, tapping the notification opens a ready-to-send draft — Oracle has no server, so it can't send silently in the background."
            textSize = 11f; setTextColor(Color.rgb(150, 158, 178)); setPadding(0, host.dp(4), 0, host.dp(10))
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(context).apply {
            setText(settings.email())
            hint = "you@example.com"
            setHintTextColor(Color.rgb(110, 118, 138))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            textSize = 14f
            background = GradientDrawable().apply { setColor(Color.rgb(5, 8, 17)); cornerRadius = host.dp(10).toFloat(); setStroke(host.dp(1), Color.rgb(45, 55, 78)) }
            setPadding(host.dp(12), host.dp(10), host.dp(12), host.dp(10))
        }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val save = TextView(context).apply {
            text = "SAVE"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = host.dp(10).toFloat() }
            isClickable = true; isFocusable = true
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
                    Toast.makeText(context, "That doesn't look like a valid email address", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                settings.setEmail(value)
                Toast.makeText(context, if (value.isBlank()) "Alert email cleared" else "Alert email saved", Toast.LENGTH_SHORT).show()
            }
        }
        row.addView(save, LinearLayout.LayoutParams(host.dp(70), host.dp(42)).apply { setMargins(host.dp(8), 0, 0, 0) })
        card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, host.dp(2), 0, 0) })
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
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
        else -> kind
    }

    private fun addAlert(a: OracleAlert, critical: Boolean) {
        val active = a.active
        val accent = when {
            a.kind == "URGENT_SELL" -> Color.rgb(255, 60, 60)
            a.kind == "GROWTH_FADING" -> Color.rgb(255, 205, 45)
            a.kind == "HIGH_VOLATILITY" -> Color.rgb(255, 150, 45)
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
        if (critical) {
            val emailBtn = TextView(context).apply {
                text = "✉  EMAIL THIS ALERT"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.rgb(20, 30, 50)); cornerRadius = host.dp(9).toFloat(); setStroke(host.dp(1), accent) }
                isClickable = true; isFocusable = true
                setPadding(0, host.dp(9), 0, host.dp(9))
                setOnClickListener {
                    val email = settings.email()
                    if (email.isBlank()) {
                        Toast.makeText(context, "Add an alert email above first", Toast.LENGTH_LONG).show()
                    } else {
                        runCatching { context.startActivity(OracleAlertMailer.buildIntent(email, a)) }
                    }
                }
            }
            card.addView(emailBtn, LinearLayout.LayoutParams(-1, -2).apply { setMargins(host.dp(22), host.dp(10), 0, 0) })
        }
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(9)) })
    }
    private fun severityRank(level:String)=when(level.uppercase(Locale.getDefault())){"HIGH"->3;"MEDIUM"->2;else->1}
}

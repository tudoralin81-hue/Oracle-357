package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ro.alintudor.oracle.core.OracleKnowledgeArticle
import ro.alintudor.oracle.core.OracleKnowledgeSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Knowledge UI backed by the WordPress REST sync cache. */
class OracleKnowledgeModule(private val host: OracleNativeModule) {
    fun render(items: List<OracleKnowledgeArticle>, onOpen: (String) -> Unit, onRefresh: () -> Unit = {}) {
        host.content.removeAllViews()
        host.addSectionLabel("KNOWLEDGE")
        val context = host.root.context
        val error = OracleKnowledgeSync.lastError(context)

        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(host.dp(22), host.dp(24), host.dp(22), host.dp(22))
            background = GradientDrawable().apply {
                setColor(Color.rgb(7, 12, 23)); cornerRadius = host.dp(18).toFloat(); setStroke(host.dp(1), host.accent)
            }
        }
        hero.addView(TextView(context).apply {
            text = "▱"; textSize = 62f; gravity = Gravity.CENTER; setTextColor(host.accent)
        }, LinearLayout.LayoutParams(-1, host.dp(74)))
        hero.addView(TextView(context).apply {
            text = "KNOWLEDGE"; textSize = 28f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, host.dp(48)))
        hero.addView(TextView(context).apply {
            text = "━━━━━━━━━━━━━━━━━━━━"; textSize = 10f; gravity = Gravity.CENTER; setTextColor(host.accent); alpha = .85f
        }, LinearLayout.LayoutParams(-1, host.dp(24)))
        hero.addView(TextView(context).apply {
            text = "Access articles, analysis and ideas\nfor intelligent investors."; textSize = 15f; gravity = Gravity.CENTER; setLineSpacing(host.dp(3).toFloat(), 1f); setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(-1, host.dp(64)))
        hero.addView(Button(context).apply {
            text = "↗   OPEN ALINTUDOR.RO/KNOWLEDGE"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; setTextColor(Color.rgb(8,12,20))
            background = GradientDrawable().apply { setColor(host.accent); cornerRadius = host.dp(12).toFloat() }
            setOnClickListener { onOpen(OracleKnowledgeSync.SOURCE_URL) }
        }, LinearLayout.LayoutParams(-1, host.dp(54)).apply { setMargins(0, host.dp(10), 0, 0) })
        host.content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(14)) })

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14))
            background = GradientDrawable().apply { setColor(Color.rgb(9,15,27)); cornerRadius = host.dp(15).toFloat(); setStroke(host.dp(1), Color.rgb(38,55,80)) }
        }
        info.addView(TextView(context).apply { text = "✓"; textSize = 25f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(host.accent) }, LinearLayout.LayoutParams(host.dp(42), host.dp(42)))
        info.addView(TextView(context).apply {
            text = "Independent content\nNo sign-in required.\nOpens in the browser."; textSize = 13f; setLineSpacing(host.dp(2).toFloat(), 1f); setTextColor(Color.rgb(205,212,225)); setPadding(host.dp(10),0,0,0)
        }, LinearLayout.LayoutParams(0,-2,1f))
        host.content.addView(info, LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,host.dp(12)) })

        host.content.addView(Button(context).apply {
            text = "⟳  REFRESH KNOWLEDGE"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(12,54,82)); cornerRadius = host.dp(11).toFloat(); setStroke(host.dp(1), Color.rgb(55,105,145)) }
            setOnClickListener { onRefresh() }
        }, LinearLayout.LayoutParams(-1,host.dp(44)).apply { setMargins(0,0,0,host.dp(12)) })

        if (error.isNotBlank()) host.addCard("LAST SYNC ERROR", error)
        if (items.isEmpty()) {
            host.addCard("ARTICLES", "No articles cached yet. Use REFRESH KNOWLEDGE to fetch them for the first time.")
            return
        }
        host.addSectionLabel("ARTICLES • ${items.size}")
        items.forEach { article ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(host.dp(15),host.dp(13),host.dp(15),host.dp(13))
                background = GradientDrawable().apply { setColor(Color.rgb(7,12,23)); cornerRadius = host.dp(15).toFloat(); setStroke(host.dp(1),Color.rgb(38,55,80)) }
            }
            card.addView(TextView(context).apply { text=article.title; textSize=18f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            if (article.publishedAt > 0L) card.addView(TextView(context).apply { text=SimpleDateFormat("dd.MM.yyyy",Locale.getDefault()).format(Date(article.publishedAt)); textSize=11f; setTextColor(host.accent); setPadding(0,host.dp(5),0,0) })
            card.addView(TextView(context).apply { text=article.excerpt; textSize=13f; setTextColor(Color.rgb(190,198,213)); setPadding(0,host.dp(8),0,host.dp(8)) })
            card.addView(Button(context).apply {
                text="OPEN ARTICLE"; textSize=12f; typeface=Typeface.DEFAULT_BOLD; isAllCaps=false; setTextColor(Color.WHITE)
                background=GradientDrawable().apply { setColor(Color.rgb(12,54,82)); cornerRadius=host.dp(11).toFloat() }; setOnClickListener { onOpen(article.url) }
            },LinearLayout.LayoutParams(-1,host.dp(44)))
            host.content.addView(card,LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,host.dp(10)) })
        }
    }
}

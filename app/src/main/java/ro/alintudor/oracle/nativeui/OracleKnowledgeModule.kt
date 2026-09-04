package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import ro.alintudor.oracle.core.OracleKnowledgeArticle
import ro.alintudor.oracle.core.OracleKnowledgeSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Knowledge UI backed by the WordPress REST sync cache. Styled to match the
 *  rest of the app: host.addCard() for intro/info blocks, bordered
 *  accent-colored buttons (same pattern as Portfolio's EDIT/SELL/ADD), and
 *  plain bordered cards per article — no module-specific decoration. */
class OracleKnowledgeModule(private val host: OracleNativeModule) {
    private val context get() = host.root.context

    fun render(items: List<OracleKnowledgeArticle>, onRefresh: () -> Unit = {}) {
        host.content.removeAllViews()
        val error = OracleKnowledgeSync.lastError(context)

        // heading == "KNOWLEDGE" makes addCard() render this as the shared
        // clickable teaser style (gold border, flashing call-to-action) used
        // for Knowledge elsewhere in the app — opens the site directly, so no
        // separate button is needed here.
        host.addCard("KNOWLEDGE", "Access articles, analysis and ideas for intelligent investors.")
        host.addCard("INDEPENDENT CONTENT", "No sign-in required. Opens in the browser.")

        val refreshBtn = actionButton("REFRESH KNOWLEDGE", host.accent)
        refreshBtn.setOnClickListener {
            refreshBtn.text = "REFRESHING…"; refreshBtn.isEnabled = false; refreshBtn.alpha = 0.6f
            onRefresh()
        }
        host.content.addView(refreshBtn, LinearLayout.LayoutParams(-1, host.dp(46)).apply { setMargins(0, 0, 0, host.dp(10)) })

        if (error.isNotBlank()) host.addCard("LAST SYNC ERROR", error)
        if (items.isEmpty()) {
            host.addCard("ARTICLES", "No articles cached yet. Use REFRESH KNOWLEDGE to fetch them for the first time.")
            return
        }
        host.addSectionLabel("ARTICLES • ${items.size}")
        items.forEach { article ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13))
                background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(12), Color.rgb(35, 44, 66), host.dp(1))
            }
            card.addView(TextView(context).apply { text = article.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            if (article.publishedAt > 0L) card.addView(TextView(context).apply { text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(article.publishedAt)); textSize = 11f; setTextColor(host.accent); setPadding(0, host.dp(5), 0, 0) })
            card.addView(TextView(context).apply { text = article.excerpt; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(0, host.dp(8), 0, 0) })
            host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
        }
    }

    // Same bordered, uppercase-bold action-button style used across every
    // other module (Portfolio's EDIT / SELL SHARES / ADD POSITION, etc.).
    private fun actionButton(label: String, color: Int) = TextView(context).apply {
        text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(color)
        background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), host.dp(11), color, host.dp(1))
        isClickable = true; isFocusable = true
    }
}


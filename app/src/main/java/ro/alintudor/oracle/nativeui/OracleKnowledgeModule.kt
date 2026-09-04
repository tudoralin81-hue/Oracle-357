package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
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

    fun render(items: List<OracleKnowledgeArticle>) {
        host.content.removeAllViews()
        val error = OracleKnowledgeSync.lastError(context)

        // heading == "KNOWLEDGE" makes addCard() render this as the shared
        // clickable teaser style (gold border, flashing call-to-action) used
        // for Knowledge elsewhere in the app — opens the site directly, so no
        // separate button is needed here.
        host.addCard("KNOWLEDGE", "Access articles, analysis and ideas for intelligent investors.")

        if (error.isNotBlank()) host.addCard("LAST SYNC ERROR", error)
        if (items.isEmpty()) {
            host.addCard("ARTICLES", "Loading articles…")
            return
        }
        // One header per chapter (category), articles listed under it in
        // chronological order — the chapter is written once, not per card.
        var currentGroup: String? = null
        items.forEach { article ->
            val group = article.category.ifBlank { "ARTICLES" }.uppercase(Locale.getDefault())
            if (group != currentGroup) {
                currentGroup = group
                val count = items.count { it.category.ifBlank { "ARTICLES" }.uppercase(Locale.getDefault()) == group }
                host.addSectionLabel("$group • $count")
            }
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13))
                background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(12), Color.rgb(35, 44, 66), host.dp(1))
                isClickable = true; isFocusable = true
                setOnClickListener { showArticlePopup(context, article) }
            }
            card.addView(TextView(context).apply { text = article.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            if (article.publishedAt > 0L) card.addView(TextView(context).apply { text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(article.publishedAt)); textSize = 11f; setTextColor(host.accent); setPadding(0, host.dp(5), 0, 0) })
            card.addView(TextView(context).apply { text = article.excerpt; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(0, host.dp(8), 0, 0) })
            host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
        }
    }
}


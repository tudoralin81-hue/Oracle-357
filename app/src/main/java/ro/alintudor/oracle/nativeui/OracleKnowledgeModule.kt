package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import ro.alintudor.oracle.core.OracleKnowledgeArticle
import ro.alintudor.oracle.core.OracleKnowledgeSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Knowledge UI backed by the WordPress REST sync cache. Styled to match the
 *  rest of the app: host.addCard() for the intro block, bordered
 *  accent-colored cards per article, entrance animation on the cards (same
 *  fade + slide-up used in Portfolio), and a few oversized low-opacity
 *  glyphs scattered in as plain decoration. */
class OracleKnowledgeModule(private val host: OracleNativeModule) {
    private val context get() = host.root.context

    fun render(items: List<OracleKnowledgeArticle>) {
        host.content.removeAllViews()
        val error = OracleKnowledgeSync.lastError(context)

        host.addCard("KNOWLEDGE", "Access articles, analysis and ideas for intelligent investors.")
        host.content.addView(decorativeGlyph("∿"))

        if (error.isNotBlank()) host.addCard("LAST SYNC ERROR", error)
        if (items.isEmpty()) {
            host.addCard("ARTICLES", "Loading articles…")
            return
        }
        // One header per chapter (category), articles listed under it in
        // chronological order — the chapter is written once, not per card.
        var currentGroup: String? = null
        var rank = 0
        items.forEach { article ->
            val group = article.category.ifBlank { "ARTICLES" }.uppercase(Locale.getDefault())
            if (group != currentGroup) {
                currentGroup = group
                val count = items.count { it.category.ifBlank { "ARTICLES" }.uppercase(Locale.getDefault()) == group }
                host.addSectionLabel("$group • $count ARTICLES")
            }
            rank++
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(host.dp(15), host.dp(13), host.dp(15), host.dp(13))
                background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(12), Color.rgb(35, 44, 66), host.dp(1))
                isClickable = true; isFocusable = true
                setOnClickListener { showArticlePopup(context, article) }
                alpha = 0f; translationY = host.dp(24).toFloat()
            }
            card.addView(TextView(context).apply { text = article.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            if (article.publishedAt > 0L) card.addView(TextView(context).apply { text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(article.publishedAt)); textSize = 11f; setTextColor(host.accent); setPadding(0, host.dp(5), 0, 0) })
            card.addView(TextView(context).apply { text = article.excerpt; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(0, host.dp(8), 0, 0) })
            host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
            card.animate().alpha(1f).translationY(0f).setStartDelay((rank - 1) * 90L).setDuration(380L).setInterpolator(DecelerateInterpolator()).start()
            // A quiet decorative marker every few cards — not per card, so it
            // reads as scenery rather than a repeating UI element.
            if (rank % 4 == 0) host.content.addView(decorativeGlyph(listOf("◈", "▤", "⟁").random()))
        }
        host.content.addView(decorativeGlyph("◈"))
    }

    // Oversized, near-invisible symbol used purely as background texture
    // between sections — same idea as the ▱ glyph the Knowledge hero used to
    // have, just spread through the list instead of fixed at the top.
    private fun decorativeGlyph(symbol: String) = TextView(context).apply {
        text = symbol; textSize = 78f; gravity = Gravity.CENTER; setTextColor(host.accent); alpha = 0.07f
    }.let { view ->
        LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(view, LinearLayout.LayoutParams(-2, host.dp(64)))
        }
    }
}

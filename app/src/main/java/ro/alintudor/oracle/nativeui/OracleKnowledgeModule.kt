package ro.alintudor.oracle.nativeui

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import ro.alintudor.oracle.core.OracleDemo
import ro.alintudor.oracle.core.OracleKnowledgeArticle
import ro.alintudor.oracle.core.OracleKnowledgeSync
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Knowledge UI backed by the WordPress REST sync cache. Styled to match the
 *  rest of the app: host.addCard() for the intro block, bordered
 *  accent-colored cards per article with an entrance animation (same fade +
 *  slide-up used in Portfolio), and a discreet row of decorative symbols
 *  under each card's text. */
class OracleKnowledgeModule(private val host: OracleNativeModule) {
    private val context get() = host.root.context

    // A deliberately wide mix — math notation, classical (column) motifs,
    // and a couple of figurative ones — so no two cards read the same way.
    private val decorationPool = listOf(
        "∑", "π", "∞", "√", "Δ", "∫", "Ω", "Φ", "Ψ", "ξ", "±", "≈", "∇", "θ", "λ",
        "🏛", "🏛", "🦉", "🧠"
    )

    fun render(items: List<OracleKnowledgeArticle>, silent: Boolean = false) {
        host.content.removeAllViews()
        val error = OracleKnowledgeSync.lastError(context)

        host.addCard("KNOWLEDGE", "Access articles, analysis and ideas for intelligent investors.")

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
                setOnClickListener {
                    val locked = OracleDemo.active(context) && items.indexOf(article) > 0
                    showArticlePopup(context, if (locked) article.copy(content = "", excerpt = "${OracleDemo.LOCK} In the demo only the first chapter opens in full. ${article.excerpt}") else article)
                }
                if (!silent) { alpha = 0f; translationY = host.dp(24).toFloat() }
            }
            card.addView(TextView(context).apply { text = article.title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            if (article.publishedAt > 0L) card.addView(TextView(context).apply { text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(article.publishedAt)); textSize = 11f; setTextColor(host.accent); setPadding(0, host.dp(5), 0, 0) })
            card.addView(TextView(context).apply { text = article.excerpt; textSize = 13f; setTextColor(Color.rgb(175, 183, 201)); setPadding(0, host.dp(8), 0, 0) })
            card.addView(decorationRow())
            host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
            if (!silent) card.animate().alpha(1f).translationY(0f).setStartDelay((rank - 1) * 90L).setDuration(380L).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // A quiet strip of 3 symbols under a card's text — visible but never
    // competing with the title or excerpt above it.
    private fun decorationRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
        setPadding(0, host.dp(10), 0, 0)
        repeat(3) {
            addView(TextView(context).apply {
                text = decorationPool.random(Random); textSize = 20f; alpha = 0.22f
                setTextColor(host.accent); setPadding(host.dp(6), 0, host.dp(6), 0)
            })
        }
    }
}

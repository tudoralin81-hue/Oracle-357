package ro.alintudor.oracle.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors

data class OracleKnowledgeArticle(
    val title: String,
    val url: String,
    val excerpt: String,
    val content: String,
    val publishedAt: Long,
    val refreshedAt: Long
)

object OracleKnowledgeSync {
    const val SOURCE_URL = "https://alintudor.ro/knowledge/"
    // /knowledge/ is a WordPress category-archive page (its permalink base has
    // the usual "/category/" prefix stripped) — it is NOT a URL prefix that
    // individual articles share. A post's own permalink lives at the site
    // root (e.g. /cum-identifici-o-miscare-sanatoasa-a-pretului-inainte-sa-
    // intri-in-tranzactie/), confirmed against a real published article.
    // Filtering by URL path therefore always matched zero posts. Resolve the
    // real "knowledge" category (or tag, as a fallback) id instead and scope
    // the posts query to it server-side.
    private const val CATEGORY_LOOKUP_URL = "https://alintudor.ro/wp-json/wp/v2/categories?slug=knowledge&_fields=id,slug"
    private const val TAG_LOOKUP_URL = "https://alintudor.ro/wp-json/wp/v2/tags?slug=knowledge&_fields=id,slug"
    private const val POSTS_BASE_URL = "https://alintudor.ro/wp-json/wp/v2/posts?per_page=100&orderby=date&order=desc&_fields=id,date,link,title,excerpt,content"
    private const val PREFS = "oracle_knowledge"
    private const val ITEMS = "articles"
    private const val LAST_SUCCESS = "last_success"
    private const val LAST_ERROR = "last_error"
    private const val MAX_ARTICLES = 100
    private const val STALE_MS = 20L * 60L * 60L * 1000L
    private const val REQUEST_TIMEOUT = 15000
    private val executor = Executors.newSingleThreadExecutor()

    fun load(context: Context): List<OracleKnowledgeArticle> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEMS, "[]") ?: "[]"
        val a = JSONArray(raw)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            OracleKnowledgeArticle(o.optString("title"), o.optString("url"), o.optString("excerpt"), o.optString("content"), o.optLong("publishedAt"), o.optLong("refreshedAt"))
        }
    }.getOrDefault(emptyList())

    fun lastSuccess(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0L)
    fun lastError(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_ERROR, "") ?: ""
    fun isStale(context: Context): Boolean = System.currentTimeMillis() - lastSuccess(context) >= STALE_MS

    fun refreshAsync(context: Context, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        executor.execute {
            val result = runCatching { refreshBlocking(app) }
            Handler(Looper.getMainLooper()).post {
                result.fold(
                    { onDone(true, null) },
                    { error ->
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(LAST_ERROR, error.message ?: error.javaClass.simpleName).apply()
                        onDone(false, error.message ?: error.javaClass.simpleName)
                    }
                )
            }
        }
    }

    fun refreshBlocking(context: Context): List<OracleKnowledgeArticle> {
        val now = System.currentTimeMillis()
        // Resolving the category (or tag) id can fail on its own (network
        // hiccup, taxonomy renamed) without that being a reason to give up —
        // fall back to the unscoped posts feed so a transient lookup failure
        // degrades gracefully instead of surfacing as "no articles found".
        val termId = runCatching { resolveTermId(CATEGORY_LOOKUP_URL) }.getOrNull()
            ?: runCatching { resolveTermId(TAG_LOOKUP_URL) }.getOrNull()
        val postsUrl = if (termId != null) "$POSTS_BASE_URL&categories=$termId" else POSTS_BASE_URL
        val json = get(postsUrl)
        // Once the query is scoped server-side to the real "knowledge" term,
        // every post returned already belongs to Knowledge — the old
        // path-based check no longer applies (and never matched anyway).
        // Only fall back to the path check if term scoping wasn't available.
        val apiItems = parseRestArticles(json, now, requirePathMatch = termId == null)
        if (apiItems.isEmpty()) throw IllegalStateException("No published articles found in /knowledge/ via the WordPress REST API.")
        val payload = JSONArray().apply {
            apiItems.forEach { a ->
                put(JSONObject().apply {
                    put("title", a.title); put("url", a.url); put("excerpt", a.excerpt); put("content", a.content)
                    put("publishedAt", a.publishedAt); put("refreshedAt", a.refreshedAt)
                })
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ITEMS, payload.toString()).putLong(LAST_SUCCESS, now).remove(LAST_ERROR).apply()
        return apiItems
    }

    /** Looks up a taxonomy term's id by its "knowledge" slug. Returns null if
     *  the term doesn't exist or the request fails (caller decides fallback). */
    private fun resolveTermId(lookupUrl: String): Int? {
        val raw = get(lookupUrl)
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        return arr.getJSONObject(0).optInt("id", -1).takeIf { it > 0 }
    }

    private fun parseRestArticles(raw: String, refreshedAt: Long, requirePathMatch: Boolean): List<OracleKnowledgeArticle> {
        val array = JSONArray(raw)
        val out = ArrayList<OracleKnowledgeArticle>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val title = decodeHtml(item.optJSONObject("title")?.optString("rendered", "") ?: "")
            val url = normalizeUrl(item.optString("link", "").trim())
            if (title.isBlank() || url.isBlank()) continue
            if (requirePathMatch && !isKnowledgeUrl(url)) continue
            val contentHtml = item.optJSONObject("content")?.optString("rendered", "") ?: ""
            val excerptHtml = item.optJSONObject("excerpt")?.optString("rendered", "") ?: ""
            val content = cleanText(contentHtml)
            val excerpt = cleanText(excerptHtml).ifBlank { content }.take(420)
            val publishedAt = runCatching { Instant.parse(item.optString("date")).toEpochMilli() }.getOrDefault(0L)
            out += OracleKnowledgeArticle(title, url, excerpt, content.take(12000), publishedAt, refreshedAt)
        }
        return out.distinctBy { it.url }.sortedByDescending { it.publishedAt }.take(MAX_ARTICLES)
    }

    // Kept only as the last-resort fallback path (see requirePathMatch above)
    // for the rare case term-id lookup itself fails.
    private fun isKnowledgeUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        val path = u.path.trimEnd('/')
        path == "/knowledge" || path.startsWith("/knowledge/")
    }.getOrDefault(false)

    fun scheduleDaily(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(app, OracleKnowledgeRefreshReceiver::class.java)
        val pending = android.app.PendingIntent.getBroadcast(app, 7107, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val first = System.currentTimeMillis() + 24L * 60L * 60L * 1000L
        if (android.os.Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, first, pending)
        else alarm.set(android.app.AlarmManager.RTC_WAKEUP, first, pending)
    }

    private fun get(url: String): String {
        val finalUrl = url + "&oracle_knowledge_refresh=" + System.currentTimeMillis()
        val c = (URL(finalUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT; readTimeout = REQUEST_TIMEOUT; useCaches = false; requestMethod = "GET"
            setRequestProperty("User-Agent", "OracleKnowledge/3.0")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} pentru Knowledge REST API")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun decodeHtml(raw: String): String = cleanText(raw)

    private fun cleanText(raw: String): String {
        var s = raw.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
        mapOf("&nbsp;" to " ", "&amp;" to "&", "&quot;" to "\"", "&#8211;" to "–", "&#8212;" to "—", "&#8217;" to "’", "&#8220;" to "“", "&#8221;" to "”", "&#038;" to "&", "&#39;" to "'").forEach { (a,b) -> s = s.replace(a,b,ignoreCase=true) }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeUrl(raw: String): String = runCatching {
        val u = URI(raw.trim())
        if (u.isAbsolute) u.toString().substringBefore('#') else URI(SOURCE_URL).resolve(u).toString().substringBefore('#')
    }.getOrDefault(raw.trim().substringBefore('#'))
}

class OracleKnowledgeRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            OracleKnowledgeSync.scheduleDaily(context.applicationContext); return
        }
        val pending = goAsync()
        Thread {
            try { runCatching { OracleKnowledgeSync.refreshBlocking(context.applicationContext) }.onFailure { context.applicationContext.getSharedPreferences("oracle_knowledge", Context.MODE_PRIVATE).edit().putString("last_error", it.message ?: it.javaClass.simpleName).apply() } }
            finally { OracleKnowledgeSync.scheduleDaily(context.applicationContext); pending.finish() }
        }.start()
    }
}

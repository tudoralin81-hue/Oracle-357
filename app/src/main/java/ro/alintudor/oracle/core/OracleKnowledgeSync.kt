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
    // Confirmed against the live REST API: every published post on this site
    // sits in the WordPress default category (id 1, "Uncategorized") — there
    // is no distinct "knowledge" category or tag to scope by, and posts'
    // permalinks live at the site root, not under /knowledge/ (that page is
    // just how the site presents its one and only stream of trading-education
    // articles). So no server-side or client-side filtering is needed or even
    // possible here — every published post IS a Knowledge article.
    private const val POSTS_URL = "https://alintudor.ro/wp-json/wp/v2/posts?per_page=100&orderby=date&order=desc&_fields=id,date,link,title,excerpt,content"
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
        val json = get(POSTS_URL)
        val apiItems = parseRestArticles(json, now)
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

    private fun parseRestArticles(raw: String, refreshedAt: Long): List<OracleKnowledgeArticle> {
        val array = JSONArray(raw)
        val out = ArrayList<OracleKnowledgeArticle>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val title = decodeHtml(item.optJSONObject("title")?.optString("rendered", "") ?: "")
            val url = normalizeUrl(item.optString("link", "").trim())
            if (title.isBlank() || url.isBlank()) continue
            val contentHtml = item.optJSONObject("content")?.optString("rendered", "") ?: ""
            val excerptHtml = item.optJSONObject("excerpt")?.optString("rendered", "") ?: ""
            val content = cleanText(contentHtml)
            val excerpt = cleanText(excerptHtml).ifBlank { content }.take(420)
            val publishedAt = runCatching { Instant.parse(item.optString("date")).toEpochMilli() }.getOrDefault(0L)
            out += OracleKnowledgeArticle(title, url, excerpt, content.take(12000), publishedAt, refreshedAt)
        }
        return out.distinctBy { it.url }.sortedByDescending { it.publishedAt }.take(MAX_ARTICLES)
    }

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

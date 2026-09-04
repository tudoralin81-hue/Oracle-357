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
import java.util.Locale
import java.util.concurrent.Executors

data class OracleKnowledgeArticle(
    val title: String,
    val url: String,
    val excerpt: String,
    val content: String,
    val imageUrl: String,
    val category: String,
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
    private const val POSTS_URL = "https://alintudor.ro/wp-json/wp/v2/posts?per_page=100&orderby=date&order=desc&_embed=wp:featuredmedia,wp:term&_fields=id,date,date_gmt,link,title,excerpt,content,featured_media,_links,_embedded"
    private const val PREFS = "oracle_knowledge"
    private const val ITEMS = "articles"
    private const val LAST_SUCCESS = "last_success"
    private const val LAST_ERROR = "last_error"
    // Bump whenever the cached article format changes: load() then ignores
    // the old cache and the module re-syncs on its own next time it opens,
    // so a new build never shows data written in an older build's format.
    private const val CACHE_VERSION_KEY = "cache_version"
    private const val CACHE_VERSION = 4
    private const val MAX_ARTICLES = 100
    // Bounds how many articles get their own page fetched per refresh (for
    // the description + image below) — a generous cap for a personal blog's
    // article count, keeping a REFRESH KNOWLEDGE press from taking minutes if
    // the site ever grows a large backlog.
    private const val MAX_PAGE_FETCHES = 40
    private const val STALE_MS = 20L * 60L * 60L * 1000L
    private const val LAST_ATTEMPT = "last_attempt"
    // Auto-sync runs whenever the Knowledge screen renders, throttled so a
    // render triggered by a sync finishing (or a failing one) can't loop.
    private const val AUTO_SYNC_MIN_GAP_MS = 60L * 1000L
    private const val REQUEST_TIMEOUT = 15000
    private val executor = Executors.newSingleThreadExecutor()

    fun load(context: Context): List<OracleKnowledgeArticle> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(CACHE_VERSION_KEY, 0) != CACHE_VERSION) return emptyList()
        val raw = prefs.getString(ITEMS, "[]") ?: "[]"
        val a = JSONArray(raw)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            OracleKnowledgeArticle(o.optString("title"), o.optString("url"), o.optString("excerpt"), o.optString("content"), o.optString("imageUrl"), o.optString("category"), o.optLong("publishedAt"), o.optLong("refreshedAt"))
        }
    }.getOrDefault(emptyList())

    fun lastSuccess(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_SUCCESS, 0L)
    fun lastError(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LAST_ERROR, "") ?: ""
    fun isStale(context: Context): Boolean = System.currentTimeMillis() - lastSuccess(context) >= STALE_MS

    /** True when an automatic background sync should start now: nothing
     *  cached yet, or at least AUTO_SYNC_MIN_GAP_MS since the last attempt. */
    fun shouldAutoSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(CACHE_VERSION_KEY, 0) != CACHE_VERSION) return true
        return System.currentTimeMillis() - prefs.getLong(LAST_ATTEMPT, 0L) >= AUTO_SYNC_MIN_GAP_MS
    }

    fun refreshAsync(context: Context, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(LAST_ATTEMPT, System.currentTimeMillis()).apply()
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
                    put("imageUrl", a.imageUrl); put("category", a.category); put("publishedAt", a.publishedAt); put("refreshedAt", a.refreshedAt)
                })
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ITEMS, payload.toString()).putInt(CACHE_VERSION_KEY, CACHE_VERSION).putLong(LAST_SUCCESS, now).remove(LAST_ERROR).apply()
        return apiItems
    }

    private fun parseRestArticles(raw: String, refreshedAt: Long): List<OracleKnowledgeArticle> {
        val array = JSONArray(raw)
        val out = ArrayList<Pair<Long, OracleKnowledgeArticle>>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val title = decodeHtml(item.optJSONObject("title")?.optString("rendered", "") ?: "")
            val url = normalizeUrl(item.optString("link", "").trim())
            if (title.isBlank() || url.isBlank()) continue
            val contentHtml = item.optJSONObject("content")?.optString("rendered", "") ?: ""
            val excerptHtml = item.optJSONObject("excerpt")?.optString("rendered", "") ?: ""
            val contentText = cleanText(contentHtml)
            var excerpt = cleanText(excerptHtml).ifBlank { contentText }.take(420)
            // Full article body, kept as light HTML (paragraphs, headings,
            // bold, lists) so the popup can render it readably. Empty while
            // the membership plugin still gates it — the popup then falls
            // back to the preview text instead.
            val content = if (contentText.isBlank() || looksRestricted(contentText)) "" else sanitizeArticleHtml(contentHtml)
            var imageUrl = featuredImageFromEmbed(item)
            val category = categoryFromEmbed(item)
            // A membership plugin gates the full article behind a login/
            // register wall — confirmed by fetching a real article's own
            // public page: the REST API's excerpt/content fields come back
            // as that same restriction notice rather than real text. The
            // page's own <meta name="description"> tag is NOT gated (it's
            // what the site's own /knowledge/ listing shows as the free
            // preview), so read the real preview text straight off the page.
            //
            // Image priority: the post's real featured image (embedded via
            // the REST API above), then the featured <img> in the page
            // itself, and only then og:image — which on this site resolves
            // to the SITE LOGO (the SEO plugin's default social image, the
            // same file used as the site icon), not the article's picture.
            if (i < MAX_PAGE_FETCHES) {
                val page = runCatching { getHtml(url) }.getOrNull()
                if (page != null) {
                    extractMetaDescription(page)?.takeIf { it.isNotBlank() }?.let { excerpt = it }
                    if (imageUrl.isBlank()) {
                        // The membership plugin (WP-Members) also hides the
                        // featured-media embed from anonymous REST callers,
                        // and this theme (Kubio) doesn't tag its featured
                        // <img> with wp-post-image — but the image itself IS
                        // in the anonymous page, right after the <h1>. So:
                        // first uploads/ image after the title, never the
                        // site logo that og:image points at.
                        val logo = extractMetaImage(page)
                        imageUrl = extractFeaturedImage(page)
                            ?: extractFirstContentImage(page, exclude = logo)
                            ?: logo
                            ?: ""
                    }
                }
            }
            if (looksRestricted(excerpt)) {
                // Page fetch above failed too, or the tag itself was missing
                // — don't leave the raw server restriction notice sitting in
                // the UI, it reads like an app error. A plain local note is
                // more honest either way.
                excerpt = "Full article requires a free alintudor.ro account to read."
            }
            val publishedAt = parseWpDate(item)
            val wpId = item.optLong("id", 0L)
            out += wpId to OracleKnowledgeArticle(title, url, excerpt, content.take(80000), imageUrl, category, publishedAt, refreshedAt)
        }
        // Chronological, oldest first — these are chapters, read in order.
        // WordPress post id breaks ties (it only ever grows with creation).
        return out.distinctBy { it.second.url }
            .sortedWith(compareBy({ it.second.publishedAt }, { it.first }))
            .map { it.second }
            .take(MAX_ARTICLES)
    }

    /** WordPress REST dates carry no offset ("2026-09-04T13:00:08"), so
     *  Instant.parse() rejects them. date_gmt is UTC; date is site-local. */
    private fun parseWpDate(item: JSONObject): Long {
        item.optString("date_gmt", "").takeIf { it.isNotBlank() }?.let { s ->
            runCatching { java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()?.let { return it }
        }
        val local = item.optString("date", "")
        if (local.isBlank()) return 0L
        return runCatching { Instant.parse(local).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(local).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun looksRestricted(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return t.contains("restricted to site members") || t.contains("must be logged in") || t.contains("please log in") || t.contains("new user registration")
    }

    /** The post's real featured image, from the media object the REST API
     *  embeds when asked with _embed=wp:featuredmedia. Prefers a mid-size
     *  rendition (plenty for a phone popup) over the full-resolution original. */
    private fun featuredImageFromEmbed(item: JSONObject): String {
        val media = item.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0) ?: return ""
        val sizes = media.optJSONObject("media_details")?.optJSONObject("sizes")
        for (size in listOf("large", "medium_large", "medium", "full")) {
            val u = sizes?.optJSONObject(size)?.optString("source_url", "")?.trim() ?: ""
            if (u.isNotBlank()) return u
        }
        return media.optString("source_url", "").trim()
    }

    /** The post's category name(s) — on this site the category is the
     *  chapter ("Chapter 1"), shown on the page next to the date. Embedded
     *  via _embed=wp:term as one array per taxonomy. */
    private fun categoryFromEmbed(item: JSONObject): String {
        val groups = item.optJSONObject("_embedded")?.optJSONArray("wp:term") ?: return ""
        val names = ArrayList<String>()
        for (g in 0 until groups.length()) {
            val terms = groups.optJSONArray(g) ?: continue
            for (t in 0 until terms.length()) {
                val term = terms.optJSONObject(t) ?: continue
                if (term.optString("taxonomy") != "category") continue
                val name = decodeHtml(term.optString("name", ""))
                if (name.isNotBlank() && !name.equals("Uncategorized", ignoreCase = true)) names += name
            }
        }
        return names.joinToString(" • ")
    }

    // WordPress tags the featured image it renders with the wp-post-image
    // class — a far more specific marker than og:image for "this article's
    // own picture". Both attribute orders (class before src, src before class).
    private val featuredImagePatterns = listOf(
        Regex("""<img[^>]+class=["'][^"']*wp-post-image[^"']*["'][^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<img[^>]+src=["']([^"']+)["'][^>]*class=["'][^"']*wp-post-image[^"']*["']""", RegexOption.IGNORE_CASE)
    )

    private fun extractFeaturedImage(html: String): String? {
        for (pattern in featuredImagePatterns) {
            val text = pattern.find(html)?.groupValues?.get(1)?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    // Any <img> (src or lazy-load data-src) — scanned from the article's
    // <h1> onward, so header/menu/logo images above the title are skipped.
    private val anyImagePattern = Regex("""<img[^>]+(?:data-src|src)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun extractFirstContentImage(html: String, exclude: String?): String? {
        val start = Regex("<h1[\\s>]", RegexOption.IGNORE_CASE).find(html)?.range?.first ?: 0
        val excludeName = exclude?.substringAfterLast('/')?.substringBefore('?')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        for (match in anyImagePattern.findAll(html, start)) {
            val src = match.groupValues[1].trim()
            if (src.startsWith("data:", ignoreCase = true)) continue
            if (!src.contains("/wp-content/uploads/", ignoreCase = true)) continue
            // Skip the site logo (any rendition of it: -300x300 etc.).
            if (excludeName != null && src.contains(excludeName, ignoreCase = true)) continue
            return src
        }
        return null
    }

    /** Checks for new articles roughly once an hour, Monday–Friday only —
     *  skips weekends entirely (the site doesn't publish then) by jumping
     *  straight to Monday 08:00 instead of firing through Saturday/Sunday.
     *  Self-reschedules from the receiver below, and once more from app
     *  startup / device boot to keep the chain alive. */
    fun scheduleNextCheck(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(app, OracleKnowledgeRefreshReceiver::class.java)
        val pending = android.app.PendingIntent.getBroadcast(app, 7107, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val next = nextWeekdayCheckTime()
        if (android.os.Build.VERSION.SDK_INT >= 23) alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next, pending)
        else alarm.set(android.app.AlarmManager.RTC_WAKEUP, next, pending)
    }

    private fun nextWeekdayCheckTime(): Long {
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.HOUR_OF_DAY, 1) }
        val daysToMonday = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.SATURDAY -> 2
            java.util.Calendar.SUNDAY -> 1
            else -> 0
        }
        if (daysToMonday > 0) {
            cal.add(java.util.Calendar.DAY_OF_YEAR, daysToMonday)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 8)
            cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
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

    /** Plain HTML GET for an article's own public page (used only to read its
     *  unrestricted <meta name="description"> tag — see looksRestricted).
     *  Uses a real browser User-Agent: the site's membership plugin gates
     *  the rendered article body per-session regardless of caller, but some
     *  security/anti-bot layers key off User-Agent specifically, so this
     *  avoids the app's own identifying UA tripping one of those on the
     *  full themed page route (the JSON REST endpoint is a separate route
     *  and was already confirmed reachable with that UA). */
    private fun getHtml(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT; readTimeout = REQUEST_TIMEOUT; useCaches = false; requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }
        return try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} fetching article page")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private val metaDescriptionPatterns = listOf(
        Regex("""<meta[^>]+name=["']description["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*name=["']description["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+property=["']og:description["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*property=["']og:description["']""", RegexOption.IGNORE_CASE)
    )

    private fun extractMetaDescription(html: String): String? {
        for (pattern in metaDescriptionPatterns) {
            val match = pattern.find(html) ?: continue
            val text = cleanText(match.groupValues[1])
            if (text.isNotBlank()) return text
        }
        return null
    }

    private val metaImagePatterns = listOf(
        Regex("""<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:image["'][^>]*content=["']([^"']*)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]*name=["']twitter:image["']""", RegexOption.IGNORE_CASE)
    )

    private fun extractMetaImage(html: String): String? {
        for (pattern in metaImagePatterns) {
            // Pages here carry two og:image tags: a generic site-logo one
            // output by the theme, and the real per-article one added later
            // in <head> by the SEO plugin — confirmed by comparing the app's
            // output against the actual page. .find() (first match) grabbed
            // the wrong, generic one; the real image is reliably the LAST
            // match for a given tag rather than the first.
            val matches = pattern.findAll(html).toList()
            val text = matches.lastOrNull()?.groupValues?.get(1)?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun decodeHtml(raw: String): String = cleanText(raw)

    /** Strips what Html.fromHtml can't render or shouldn't (scripts, styles,
     *  iframes, images, forms, comments) and keeps the text structure. */
    private fun sanitizeArticleHtml(raw: String): String = raw
        .replace(Regex("<!--[\\s\\S]*?-->"), " ")
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<iframe[\\s\\S]*?</iframe>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<form[\\s\\S]*?</form>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<(?:img|input|button|svg)[^>]*>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("</?(?:figure|figcaption|picture|source|video|audio|noscript)[^>]*>", RegexOption.IGNORE_CASE), " ")
        .trim()

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
            OracleKnowledgeSync.scheduleNextCheck(context.applicationContext); return
        }
        val pending = goAsync()
        Thread {
            try { runCatching { OracleKnowledgeSync.refreshBlocking(context.applicationContext) }.onFailure { context.applicationContext.getSharedPreferences("oracle_knowledge", Context.MODE_PRIVATE).edit().putString("last_error", it.message ?: it.javaClass.simpleName).apply() } }
            finally { OracleKnowledgeSync.scheduleNextCheck(context.applicationContext); pending.finish() }
        }.start()
    }
}

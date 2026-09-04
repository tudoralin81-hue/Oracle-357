package ro.alintudor.oracle.core

import android.util.Xml
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.xmlpull.v1.XmlPullParser

/** Fast, fault-tolerant RSS/Atom ingestion for finance and stock-market news only. */
object OracleNewsFetcher {
    private data class Feed(val name: String, val url: String)
    private val feeds = listOf(
        Feed("CNBC", "https://www.cnbc.com/id/100003114/device/rss/rss.html"),
        Feed("BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml"),
        Feed("Financial Times", "https://www.ft.com/?format=rss"),
        Feed("Bloomberg", "https://feeds.bloomberg.com/markets/news.rss"),
        Feed("MarketWatch", "https://feeds.marketwatch.com/marketwatch/topstories/"),
        Feed("The Wall Street Journal", "https://feeds.a.dj.com/rss/RSSMarketsMain.xml"),
        Feed("The New York Times Business", "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml"),
        Feed("Reuters", "https://news.google.com/rss/search?q=site%3Areuters.com%20business%20OR%20markets&hl=en-US&gl=US&ceid=US:en"),
        Feed("Investing.com", "https://www.investing.com/rss/news_25.rss"),
        Feed("Google News • Markets", "https://news.google.com/rss/search?q=stock%20market%20OR%20stocks%20OR%20markets&hl=en-US&gl=US&ceid=US:en")
    )
    private val economicKeywords = listOf("stock","stocks","share","shares","equity","equities","market","markets","nasdaq","nyse","s&p","dow","index","indices","earnings","revenue","profit","loss","guidance","ipo","merger","acquisition","m&a","fed","federal reserve","interest rate","inflation","cpi","ppi","gdp","jobs","payroll","treasury","bond","yield","forex","currency","oil","gold","silver","bitcoin","crypto","investor","investing","wall street","business","finance","financial","economy","economic","tariff","trade","bank","banks","semiconductor","energy")

    /** Always performs a network read. No local news cache is used by the fetcher. */
    /**
     * @param priorityTerms tickers and company names the person actually
     *   holds / watches / was recommended — headlines mentioning them rank
     *   first and carry a relevance score, so the feed is theirs, not generic.
     */
    fun fetch(limit: Int = 150, priorityTerms: List<String> = emptyList()): List<OracleNews> {
        val pool = Executors.newFixedThreadPool(feeds.size.coerceAtMost(10))
        val terms = priorityTerms.map { it.trim() }.filter { it.length >= 2 }.distinct()
        return try {
            feeds.map { feed -> pool.submit(Callable { runCatching { readFeed(feed) }.getOrDefault(emptyList()) }) }
                .flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
                .filter { it.title.isNotBlank() && isEconomic(it) }
                .groupBy { canonicalKey(it) }
                .values
                // Prefer the original outlet over an aggregator copy of the same story.
                .mapNotNull { group -> group.sortedWith(compareBy<OracleNews> { if (it.source.contains("Google News", true)) 1 else 0 }.thenByDescending { it.publishedAt }).firstOrNull() }
                .map { n ->
                    val matched = matchedTerm(n.title, terms)
                    n.copy(
                        ticker = n.ticker.ifBlank { matched?.takeIf { it.length <= 6 && it.uppercase(Locale.US) == it } ?: "" },
                        relevanceScore = relevance(n.title, matched),
                        sentimentScore = OracleSentiment.scoreOne(n.title).takeIf { it != 0.0 }
                    )
                }
                .sortedWith(compareByDescending<OracleNews> { it.breaking }.thenByDescending { it.relevanceScore }.thenByDescending { it.publishedAt })
                .take(limit)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun matchedTerm(title: String, terms: List<String>): String? {
        val t = " " + title.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ") + " "
        return terms.firstOrNull { term -> t.contains(" " + term.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").trim() + " ") }
    }

    private val marketTerms = listOf("stock", "shares", "earnings", "guidance", "fed", "rates", "inflation", "s&p", "nasdaq", "dow", "wall street", "treasury", "ipo", "upgrade", "downgrade")

    private fun relevance(title: String, matched: String?): Double {
        if (matched != null) return 100.0
        val t = title.lowercase(Locale.US)
        val hits = marketTerms.count { t.contains(it) }
        return (30.0 + hits * 15.0).coerceAtMost(75.0)
    }

    private fun isEconomic(n: OracleNews): Boolean {
        val text = (n.title + " " + n.publisher + " " + n.source).lowercase(Locale.US)
        return economicKeywords.any { text.contains(it) }
    }

    // Fuzzy key: the first 8 meaningful words. Two outlets writing the same
    // story with slightly different headlines collapse into one item.
    private val stopWords = setOf("the", "a", "an", "of", "to", "in", "on", "for", "and", "as", "at", "by", "with", "is", "are", "its", "it", "s", "from", "after", "amid", "over")
    private fun canonicalKey(n: OracleNews): String = "title:" + canonicalTitle(n.title).split(" ").filter { it.isNotBlank() && it !in stopWords }.take(8).joinToString(" ")

    private fun canonicalTitle(value: String): String = value.trim().lowercase(Locale.US)
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*[-|–—:]\\s*(reuters|cnbc|bloomberg|marketwatch|investing\\.com|bbc|financial times|wsj|the wall street journal)\\s*$"), "")
        .replace(Regex("[^a-z0-9 ]"), "")
        .trim()

    private fun readFeed(feed: Feed): List<OracleNews> {
        // Explicit cache-buster + HTTP no-cache headers ensure pull-to-refresh
        // asks the feed server for a new response instead of reusing an old body.
        val separator = if (feed.url.contains("?")) "&" else "?"
        val freshUrl = feed.url + separator + "oracle_refresh=" + System.currentTimeMillis()
        val connection = (URL(freshUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 7000
            useCaches = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "OracleStockIntelligence/1.2")
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            connection.inputStream.use { parse(feed, it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(feed: Feed, input: java.io.InputStream): List<OracleNews> {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        val out = ArrayList<OracleNews>()
        var event = parser.eventType
        var inItem = false
        var title = ""
        var link = ""
        var id = ""
        var published = 0L
        var source = feed.name
        var currentTag = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.US)
                    if (name == "item" || name == "entry") {
                        inItem = true
                        title = ""
                        link = ""
                        id = ""
                        published = 0L
                        source = feed.name
                        currentTag = ""
                    } else if (inItem) {
                        currentTag = name
                        if (name == "link") {
                            parser.getAttributeValue(null, "href")?.takeIf { it.isNotBlank() }?.let { link = it }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inItem) {
                        val text = parser.text?.trim().orEmpty()
                        when (currentTag) {
                            "title" -> if (title.isBlank()) title = text
                            "link" -> if (link.isBlank()) link = text
                            "guid", "id" -> if (id.isBlank()) id = text
                            "pubdate", "published", "updated", "dc:date" -> if (published == 0L) published = parseDate(text)
                            "source" -> if (text.isNotBlank()) source = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                        if (title.isNotBlank()) {
                            val now = System.currentTimeMillis()
                            val ts = if (published > 0L) published else now
                            out += OracleNews(
                                ticker = "",
                                title = clean(title),
                                source = source,
                                url = link.trim(),
                                publishedAt = ts,
                                breaking = isBreaking(title),
                                publisher = source,
                                sourceType = "NEWS",
                                receivedAt = now,
                                timezone = "Europe/Bucharest",
                                rawId = id.ifBlank { link.ifBlank { title } },
                                engineVersion = "NEWS-INGEST-5"
                            )
                        }
                        inItem = false
                        currentTag = ""
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun clean(v: String): String = v.replace(Regex("\\s+"), " ").trim()

    private fun isBreaking(title: String): Boolean {
        val t = title.lowercase(Locale.US)
        if (Regex("\\bbreakingviews\\b").containsMatchIn(t)) return false
        return Regex("\\b(breaking|urgent|flash|just in|fed emergency|market halt)\\b").containsMatchIn(t)
    }

    private fun parseDate(v: String): Long = runCatching {
        ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching { Instant.parse(v).toEpochMilli() }.getOrDefault(0L)
    }
}

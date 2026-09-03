package ro.alintudor.oracle.core

/** Core domain models shared by all native Oracle modules. */
data class OraclePosition(
    val ticker: String,
    val company: String = "",
    val shares: Double = 0.0,
    val avgCost: Double = 0.0,
    val currentPrice: Double = 0.0,
    val currency: String = "USD",
    val pnl: Double = 0.0,
    val pnlPercent: Double = 0.0,
    val marketValue: Double = 0.0,
    val weight: Double = 0.0,
    val status: String = "ACTIVE"
)

data class OracleAlert(
    val ticker: String,
    val level: String,
    val title: String,
    val message: String = "",
    val timestamp: Long = 0L,
    val active: Boolean = true
)

data class OracleNews(
    val ticker: String = "",
    val title: String,
    val source: String = "",
    val url: String = "",
    val publishedAt: Long = 0L,
    val breaking: Boolean = false,
    val publisher: String = source,
    val sourceType: String = "NEWS",
    val receivedAt: Long = 0L,
    val timezone: String = "UTC",
    val relevanceScore: Double = 0.0,
    val sentimentScore: Double? = null,
    val rawId: String = "",
    val engineVersion: String = ""
)

data class OracleHistoryPoint(
    val ticker: String,
    val timestamp: Long,
    val price: Double,
    val value: Double = 0.0,
    val pnl: Double = 0.0
)

data class OracleAction(
    val ticker: String,
    val action: String,
    val score: Double,
    val reason: String = "",
    val timestamp: Long = 0L
)

data class OracleKnowledgeItem(
    val title: String,
    val category: String = "",
    val content: String = "",
    val publishedAt: Long = 0L
)

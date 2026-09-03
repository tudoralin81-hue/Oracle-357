package ro.alintudor.oracle.core

/** Local activity journal: every Oracle decision/event can be persisted and shown offline. */
data class OracleJournalEntry(
    val timestamp: Long,
    val ticker: String,
    val action: String,
    val score: Double,
    val reason: String,
    val status: String = "ACTIVE",
    val shares: Double = 0.0,
    val entryPrice: Double = 0.0,
    val salePrice: Double = 0.0,
    val salePercent: Double = 0.0,
    val entryValue: Double = 0.0,
    val saleValue: Double = 0.0,
    val realizedPnl: Double = 0.0,
    val positionId: String = ""
)

object OracleActivityJournal {
    fun fromActions(actions: List<OracleAction>): List<OracleJournalEntry> = actions
        .sortedByDescending { it.timestamp }
        .map { OracleJournalEntry(it.timestamp, it.ticker, it.action, it.score, it.reason) }

    fun merge(existing: List<OracleJournalEntry>, actions: List<OracleAction>): List<OracleJournalEntry> =
        (existing + fromActions(actions))
            .distinctBy { "${it.timestamp}:${it.ticker}:${it.action}:${it.score}" }
            .sortedByDescending { it.timestamp }
}

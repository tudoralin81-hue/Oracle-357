package ro.alintudor.luxoculi.core

/** Builds the complete local timeline used by History/Jurnal and Alerts. */
data class OracleTimelineItem(
    val timestamp: Long,
    val ticker: String,
    val type: String,
    val title: String,
    val detail: String,
    val severity: String = "INFO"
)

object OracleLocalTimeline {
    fun build(
        history: List<OracleHistoryPoint>,
        actions: List<OracleAction>,
        alerts: List<OracleAlert>
    ): List<OracleTimelineItem> {
        val h = history.map { OracleTimelineItem(it.timestamp,it.ticker,"HISTORY","Price ${"%.2f".format(java.util.Locale.US, it.price)}","Value ${"%.2f".format(java.util.Locale.US, it.value)} / P&L ${"%.2f".format(java.util.Locale.US, it.pnl)}") }
        val a = actions.map { OracleTimelineItem(it.timestamp,it.ticker,"ACTION",it.action,it.reason,if(it.action=="SELL")"HIGH" else "INFO") }
        val al = alerts.map { OracleTimelineItem(it.timestamp,it.ticker,"ALERT",it.title,it.message,it.level) }
        return (h + a + al).sortedByDescending { it.timestamp }
    }
}

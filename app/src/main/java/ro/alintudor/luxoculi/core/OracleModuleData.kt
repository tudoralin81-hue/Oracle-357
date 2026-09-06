package ro.alintudor.luxoculi.core

/** Single snapshot shared by all native Oracle modules. */
data class OracleModuleData(
    val positions: List<OraclePosition> = emptyList(),
    val alerts: List<OracleAlert> = emptyList(),
    val news: List<OracleNews> = emptyList(),
    val history: List<OracleHistoryPoint> = emptyList(),
    val actions: List<OracleAction> = emptyList(),
    val technical: List<OracleTechnicalSnapshot> = emptyList(),
    val knowledge: List<OracleKnowledgeItem> = emptyList(),
    val journal: List<OracleJournalEntry> = emptyList(),
    val growth: List<OracleGrowthRecommendation> = emptyList()
)

fun OracleRepository.snapshot(): OracleModuleData {
    val actions = cachedActions()
    val persistedJournal = cachedJournal()
    return OracleModuleData(
        positions = cachedPositions(),
        alerts = cachedAlerts(),
        news = cachedNews(),
        history = cachedHistory(),
        actions = actions,
        technical = cachedTechnical(),
        knowledge = cachedKnowledge(),
        journal = if (persistedJournal.isNotEmpty()) persistedJournal else OracleActivityJournal.fromActions(actions),
        growth = cachedGrowth()
    )
}

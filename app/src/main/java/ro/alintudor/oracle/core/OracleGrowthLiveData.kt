package ro.alintudor.oracle.core

/**
 * Growth is a historical 16:00 snapshot.
 *
 * The UI may call refresh more than once while the snapshot is being loaded.
 * Never suppress the first valid result: the previous B518 implementation used
 * a first-render gate that discarded the only loaded snapshot on a fresh screen.
 *
 * Live market data must not mutate the persisted Growth state. This adapter only
 * validates that the snapshot belongs to the current Growth anchor.
 *
 * B536 validation marker: launch-time warm-up is owned by OracleMysticActivity.
 */
object OracleGrowthLiveData {
    fun refresh(items: List<OracleGrowthRecommendation>): List<OracleGrowthRecommendation> {
        if (items.isEmpty()) return emptyList()
        if (items.any { it.referenceTimestamp <= 0L }) return emptyList()
        val expectedAnchor = OracleMarketCalendar.growthAnchor(System.currentTimeMillis())
        if (items.any { it.referenceTimestamp != expectedAnchor }) return emptyList()
        return items
    }
}
package ro.alintudor.luxoculi

import android.content.Context
import ro.alintudor.luxoculi.core.OracleRepository
import ro.alintudor.luxoculi.core.snapshot

/** Compatibility store backed by the same local data schema as OracleRepository. */
class OracleStore(context: Context) {
    private val repository = OracleRepository(context)

    fun load(): OracleModuleState = repository.snapshot()

    fun save(state: OracleModuleState) {
        repository.savePositions(state.positions)
        repository.saveAlerts(state.alerts)
        repository.saveNews(state.news)
        repository.saveHistory(state.history)
        repository.saveActions(state.actions)
        repository.saveKnowledge(state.knowledge)
    }
}

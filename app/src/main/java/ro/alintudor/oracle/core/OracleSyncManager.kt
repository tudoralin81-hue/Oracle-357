package ro.alintudor.oracle.core

import android.content.Context

/**
 * Keeps the local cache (OracleRepository, read instantly by every module)
 * and the server backend in sync, without the rest of the app needing to
 * know the server exists.
 *
 * Push: fire-and-forget, called from OracleRepository right after any local
 * save — only actually sends anything if a session (login token) exists.
 * Pull: called once right after a successful login, to bring this device's
 * local cache up to date with whatever the server already has (e.g. after
 * a reinstall, or logging in on first ever run of a fresh install).
 */
object OracleSyncManager {
    private val DATA_TYPES = listOf("positions", "alerts", "news", "history", "actions", "technical", "knowledge", "journal", "growth")

    fun pushDataType(context: Context, type: String, payloadJson: String) {
        val auth = OracleAuthStore(context)
        if (!auth.hasSession()) return
        Thread { OracleApiClient.saveData(auth.token(), type, payloadJson) }.start()
    }

    fun pullAll(context: Context, token: String, onDone: (Boolean) -> Unit) {
        Thread {
            val result = OracleApiClient.getAllData(token)
            val success = result.isSuccess
            if (success) {
                val data = result.getOrThrow()
                val prefs = context.getSharedPreferences("oracle_data", Context.MODE_PRIVATE)
                val editor = prefs.edit()
                for (type in DATA_TYPES) {
                    if (data.has(type)) editor.putString(type, data.getJSONArray(type).toString())
                }
                editor.apply()
            }
            onDone(success)
        }.start()
    }
}

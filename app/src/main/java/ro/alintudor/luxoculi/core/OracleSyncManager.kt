package ro.alintudor.luxoculi.core

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
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            // onDone touches UI (it ultimately calls proceedPastAuth, which
            // rebuilds the screen) and must run exactly once, no matter what
            // happens above — an unexpected/malformed field in the server's
            // response must never leave the login screen stuck on
            // "LOGGING IN..." forever. Every step below is individually
            // guarded so one bad field can't take the whole pull down with it.
            var success = false
            try {
                val result = OracleApiClient.getAllData(token)
                success = result.isSuccess
                if (success) {
                    val data = result.getOrThrow()
                    val prefs = context.getSharedPreferences("oracle_data", Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    for (type in DATA_TYPES) {
                        if (data.has(type)) runCatching { editor.putString(type, data.getJSONArray(type).toString()) }
                    }
                    editor.apply()
                    if (data.has("widget")) {
                        runCatching { ro.alintudor.luxoculi.widget.OracleWidgetSettingsStore.restoreFromServerPayload(context, data.optJSONObject("widget")) }
                    }
                }
            } catch (_: Exception) {
                success = false
            } finally {
                mainHandler.post { onDone(success) }
            }
        }.start()
    }
}

package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local file export/import of the persisted historical data (Portfolio,
 * Growth history, Journal, Alerts, News, Technical snapshots, Knowledge,
 * Actions) — an extra, offline copy under the user's own control, separate
 * from the automatic server sync (OracleSyncManager) that now mirrors this
 * same data to alintudor.ro on every save.
 *
 * Does NOT include the account (that lives server-side now — recover it by
 * logging in again, not by restoring a file) or any email settings (email
 * is sent by the server itself, nothing to back up on-device for that).
 */
object OracleBackupManager {
    private val DATA_KEYS = listOf("positions", "alerts", "news", "history", "actions", "technical", "knowledge", "journal", "growth")
    private const val PREFS_NAME = "oracle_data"

    fun buildExportJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val out = JSONObject()
        out.put("oracle_backup_version", 3)
        out.put("exported_at", System.currentTimeMillis())
        for (key in DATA_KEYS) out.put(key, JSONArray(prefs.getString(key, "[]") ?: "[]"))
        return out
    }

    /** Returns the list of data keys actually found and restored. */
    fun restoreFromJson(context: Context, json: JSONObject): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val restored = mutableListOf<String>()
        for (key in DATA_KEYS) {
            if (json.has(key)) {
                editor.putString(key, json.getJSONArray(key).toString())
                restored += key
            }
        }
        editor.apply()
        return restored
    }

    fun lastExportTimestamp(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("last_backup_export_at", 0L)

    fun markExported(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("last_backup_export_at", System.currentTimeMillis()).apply()
    }
}

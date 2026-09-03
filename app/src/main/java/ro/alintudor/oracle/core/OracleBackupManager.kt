package ro.alintudor.oracle.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports the persisted, historical Oracle data (Portfolio, Growth
 * history, Journal, Alerts, News, Technical snapshots, Knowledge, Actions)
 * as a single JSON file the user can save outside the app (Downloads,
 * Drive, email to themselves, etc.) and restore later.
 *
 * This is the only way this data survives an actual uninstall + reinstall —
 * Android deletes an app's private storage on uninstall no matter what the
 * app itself does; there is no way around that from inside the app.
 *
 * Does NOT include login credentials (username, password, security
 * answers, backup code) — those are re-created by registering again on the
 * new install, same as any local device lock.
 */
object OracleBackupManager {
    private val KEYS = listOf("positions", "alerts", "news", "history", "actions", "technical", "knowledge", "journal", "growth")
    private const val PREFS_NAME = "oracle_data"

    fun buildExportJson(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val out = JSONObject()
        out.put("oracle_backup_version", 1)
        out.put("exported_at", System.currentTimeMillis())
        for (key in KEYS) out.put(key, JSONArray(prefs.getString(key, "[]") ?: "[]"))
        return out
    }

    /** Returns the list of data keys actually found and restored. */
    fun restoreFromJson(context: Context, json: JSONObject): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val restored = mutableListOf<String>()
        for (key in KEYS) {
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

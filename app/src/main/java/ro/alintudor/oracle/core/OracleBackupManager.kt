package ro.alintudor.oracle.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports/imports everything that would otherwise be lost on an uninstall +
 * reinstall: the persisted historical data (Portfolio, Growth history,
 * Journal, Alerts, News, Technical snapshots, Knowledge, Actions), the
 * login account (username, hashed password, hashed security answers,
 * hashed backup code, biometric preference, notification email), and the
 * SMTP settings (host/port/username/password) for automatic email.
 *
 * Deliberately NOT included: the remote-backup site URL and secret token
 * themselves. Restoring a backup requires already having those two values
 * to reach the server in the first place — including them in the backup
 * they gate would be circular. Keep the URL and token saved somewhere
 * outside the app (e.g. alongside the WordPress snippet file).
 *
 * The login password itself is never stored in plain text, only its salted
 * hash, so it's safe to include here. The SMTP password IS stored in plain
 * text (SMTP needs it usable, not hashed) — including it means it also ends
 * up in this backup file, wherever it's saved or uploaded.
 */
object OracleBackupManager {
    private val DATA_KEYS = listOf("positions", "alerts", "news", "history", "actions", "technical", "knowledge", "journal", "growth")
    private const val DATA_PREFS = "oracle_data"
    private const val AUTH_PREFS = "oracle_auth"
    private val SMTP_KEYS = listOf("smtp_enabled", "smtp_host", "smtp_port", "smtp_username", "smtp_password")
    private const val SERVER_PREFS = "oracle_server_settings"

    fun buildExportJson(context: Context): JSONObject {
        val dataPrefs = context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)
        val authPrefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
        val serverPrefs = context.getSharedPreferences(SERVER_PREFS, Context.MODE_PRIVATE)

        val out = JSONObject()
        out.put("oracle_backup_version", 2)
        out.put("exported_at", System.currentTimeMillis())

        val data = JSONObject()
        for (key in DATA_KEYS) data.put(key, JSONArray(dataPrefs.getString(key, "[]") ?: "[]"))
        out.put("data", data)

        out.put("auth", prefsToJson(authPrefs))

        val smtp = JSONObject()
        for (key in SMTP_KEYS) prefsValue(serverPrefs, key)?.let { smtp.put(key, it) }
        out.put("smtp", smtp)

        return out
    }

    /** Returns which sections were actually found and restored: any of
     *  "data", "auth", "smtp". */
    fun restoreFromJson(context: Context, json: JSONObject): List<String> {
        val restored = mutableListOf<String>()

        if (json.has("data")) {
            val dataPrefs = context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)
            val data = json.getJSONObject("data")
            val editor = dataPrefs.edit()
            for (key in DATA_KEYS) if (data.has(key)) editor.putString(key, data.getJSONArray(key).toString())
            editor.apply()
            restored += "data"
        } else {
            // Backward compatibility with version-1 backups, which had the
            // data keys at the top level instead of nested under "data".
            val dataPrefs = context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE)
            val editor = dataPrefs.edit()
            var any = false
            for (key in DATA_KEYS) if (json.has(key)) { editor.putString(key, json.getJSONArray(key).toString()); any = true }
            editor.apply()
            if (any) restored += "data"
        }

        if (json.has("auth")) {
            val authPrefs = context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)
            restorePrefsFromJson(authPrefs, json.getJSONObject("auth"))
            restored += "auth"
        }

        if (json.has("smtp")) {
            val serverPrefs = context.getSharedPreferences(SERVER_PREFS, Context.MODE_PRIVATE)
            restorePrefsFromJson(serverPrefs, json.getJSONObject("smtp"))
            restored += "smtp"
        }

        return restored
    }

    fun lastExportTimestamp(context: Context): Long =
        context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE).getLong("last_backup_export_at", 0L)

    fun markExported(context: Context) {
        context.getSharedPreferences(DATA_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("last_backup_export_at", System.currentTimeMillis()).apply()
    }

    private fun prefsValue(prefs: SharedPreferences, key: String): Any? = prefs.all[key]

    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((key, value) in prefs.all) {
            when (value) {
                is String -> out.put(key, value)
                is Boolean -> out.put(key, value)
                is Int -> out.put(key, value)
                is Long -> out.put(key, value)
                is Float -> out.put(key, value.toDouble())
                else -> {}
            }
        }
        return out
    }

    private fun restorePrefsFromJson(prefs: SharedPreferences, json: JSONObject) {
        val editor = prefs.edit()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                else -> {}
            }
        }
        editor.apply()
    }
}

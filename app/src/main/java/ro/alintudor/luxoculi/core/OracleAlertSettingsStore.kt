package ro.alintudor.luxoculi.core

import android.content.Context

/** Stores the single email address the person wants critical alerts sent to. */
class OracleAlertSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("oracle_alert_settings", Context.MODE_PRIVATE)

    fun email(): String = prefs.getString("email", "") ?: ""
    fun setEmail(value: String) { prefs.edit().putString("email", value.trim()).apply() }

    /** Tracks which critical alerts (ticker+kind+day) have already been pushed/emailed,
     *  so the same still-active condition doesn't re-notify on every refresh. */
    fun alreadyNotified(key: String): Boolean = prefs.getStringSet("notified", emptySet())?.contains(key) == true
    fun markNotified(key: String) {
        val current = (prefs.getStringSet("notified", emptySet()) ?: emptySet()).toMutableSet()
        current.add(key)
        // Keep this bounded — old keys fall off once there are too many.
        val trimmed = if (current.size > 500) current.toList().takeLast(500).toMutableSet() else current
        prefs.edit().putStringSet("notified", trimmed).apply()
    }
}

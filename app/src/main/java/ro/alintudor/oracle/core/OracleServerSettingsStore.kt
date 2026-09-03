package ro.alintudor.oracle.core

import android.content.Context

/**
 * Local settings for the optional alintudor.ro integration: SMTP (real,
 * silent email sending instead of tap-to-send drafts) and a remote backup
 * endpoint hosted on the same WordPress site. Both are off by default and
 * only apply once the person fills them in — nothing here is required for
 * the app to work.
 */
class OracleServerSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_server_settings", Context.MODE_PRIVATE)

    // --- SMTP ---
    fun smtpEnabled(): Boolean = prefs.getBoolean("smtp_enabled", false)
    fun smtpHost(): String = prefs.getString("smtp_host", "") ?: ""
    fun smtpPort(): Int = prefs.getInt("smtp_port", 465)
    fun smtpUsername(): String = prefs.getString("smtp_username", "") ?: ""
    fun smtpPassword(): String = prefs.getString("smtp_password", "") ?: ""

    fun saveSmtp(enabled: Boolean, host: String, port: Int, username: String, password: String) {
        prefs.edit()
            .putBoolean("smtp_enabled", enabled)
            .putString("smtp_host", host.trim())
            .putInt("smtp_port", port)
            .putString("smtp_username", username.trim())
            .putString("smtp_password", password)
            .apply()
    }

    fun smtpConfigured(): Boolean = smtpEnabled() && smtpHost().isNotBlank() && smtpUsername().isNotBlank() && smtpPassword().isNotBlank()

    // --- Remote backup ---
    fun remoteBackupEnabled(): Boolean = prefs.getBoolean("remote_backup_enabled", false)
    fun remoteBackupUrl(): String = prefs.getString("remote_backup_url", "https://alintudor.ro") ?: "https://alintudor.ro"
    fun remoteBackupToken(): String = prefs.getString("remote_backup_token", "") ?: ""

    fun saveRemoteBackup(enabled: Boolean, url: String, token: String) {
        prefs.edit()
            .putBoolean("remote_backup_enabled", enabled)
            .putString("remote_backup_url", url.trim().trimEnd('/'))
            .putString("remote_backup_token", token.trim())
            .apply()
    }

    fun remoteBackupConfigured(): Boolean = remoteBackupEnabled() && remoteBackupUrl().isNotBlank() && remoteBackupToken().isNotBlank()
}

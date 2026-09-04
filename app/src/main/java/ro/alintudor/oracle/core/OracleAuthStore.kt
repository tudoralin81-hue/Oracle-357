package ro.alintudor.oracle.core

import android.content.Context

/**
 * Local session cache for the server-backed account on alintudor.ro.
 * Authentication itself (password hashing/verification, security
 * questions, backup code) now happens server-side — this class only holds
 * the API token and username once a login/register call succeeds, plus the
 * local biometric-unlock preference (biometric stays local: it's a
 * shortcut for re-entering the password on this device, not a separate
 * account system).
 */
class OracleAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_auth", Context.MODE_PRIVATE)

    companion object {
        val SECURITY_QUESTIONS = listOf(
            "What was the name of your first pet?",
            "What city were you born in?",
            "What was your childhood nickname?",
            "What is your mother's maiden name?",
            "What was the name of your first school?"
        )
        const val REQUIRED_SECURITY_ANSWERS = 3
    }

    fun hasSession(): Boolean = token().isNotBlank()
    fun token(): String = prefs.getString("api_token", "") ?: ""
    fun username(): String = prefs.getString("username", "") ?: ""

    fun saveSession(username: String, token: String) {
        prefs.edit().putString("username", username).putString("api_token", token).apply()
    }

    fun clearSession() {
        prefs.edit().remove("api_token").apply()
    }

    fun biometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", false)
    fun setBiometricEnabled(value: Boolean) { prefs.edit().putBoolean("biometric_enabled", value).apply() }

    // Whether the one-time "enable fingerprint unlock?" prompt has already
    // been shown after a login — so declining it once doesn't mean asking
    // again on every future login.
    fun biometricOffered(): Boolean = prefs.getBoolean("biometric_offered", false)
    fun setBiometricOffered(value: Boolean) { prefs.edit().putBoolean("biometric_offered", value).apply() }

    fun notificationEmail(): String = prefs.getString("notification_email", "") ?: ""
    fun setNotificationEmail(value: String) { prefs.edit().putString("notification_email", value.trim()).apply() }
}

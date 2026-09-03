package ro.alintudor.oracle.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local-only account gate: a username + password (hashed and salted) kept on
 * this device, with a security-question recovery path and an optional
 * biometric-unlock flag.
 *
 * There is no backend server here — this is a local screen lock for the app,
 * not a real multi-device account system. "Forgot password" can only work
 * through the security question set at registration, since Oracle has
 * nowhere to send a password-reset email from.
 */
class OracleAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_auth", Context.MODE_PRIVATE)

    fun hasAccount(): Boolean = prefs.contains("username")

    fun username(): String = prefs.getString("username", "") ?: ""

    fun securityQuestion(): String = prefs.getString("security_question", "") ?: ""

    fun biometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", false)
    fun setBiometricEnabled(value: Boolean) { prefs.edit().putBoolean("biometric_enabled", value).apply() }

    fun register(username: String, password: String, securityQuestion: String, securityAnswer: String) {
        val passwordSalt = randomSalt()
        val answerSalt = randomSalt()
        prefs.edit()
            .putString("username", username.trim())
            .putString("password_salt", passwordSalt)
            .putString("password_hash", hash(password, passwordSalt))
            .putString("security_question", securityQuestion.trim())
            .putString("answer_salt", answerSalt)
            .putString("answer_hash", hash(normalizeAnswer(securityAnswer), answerSalt))
            .apply()
    }

    fun verifyPassword(password: String): Boolean {
        val salt = prefs.getString("password_salt", null) ?: return false
        val stored = prefs.getString("password_hash", null) ?: return false
        return hash(password, salt) == stored
    }

    fun verifySecurityAnswer(answer: String): Boolean {
        val salt = prefs.getString("answer_salt", null) ?: return false
        val stored = prefs.getString("answer_hash", null) ?: return false
        return hash(normalizeAnswer(answer), salt) == stored
    }

    fun resetPassword(newPassword: String) {
        val salt = randomSalt()
        prefs.edit().putString("password_salt", salt).putString("password_hash", hash(newPassword, salt)).apply()
    }

    private fun normalizeAnswer(answer: String) = answer.trim().lowercase()

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hash(value: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

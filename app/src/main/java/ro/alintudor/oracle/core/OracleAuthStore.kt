package ro.alintudor.oracle.core

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local-only account gate: a username + password (hashed and salted) kept on
 * this device, with a security-question recovery path (3 of 5 preset
 * questions, answered at registration) and an optional biometric-unlock
 * flag.
 *
 * There is no backend server here — this is a local screen lock for the app,
 * not a real multi-device account system. "Forgot password" can only work
 * through the security questions or the one-time backup code, since Oracle
 * has nowhere to send a password-reset email from.
 */
class OracleAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oracle_auth", Context.MODE_PRIVATE)

    companion object {
        /** Fixed set of 5 preset questions; exactly 3 must be answered at registration. */
        val SECURITY_QUESTIONS = listOf(
            "What was the name of your first pet?",
            "What city were you born in?",
            "What was your childhood nickname?",
            "What is your mother's maiden name?",
            "What was the name of your first school?"
        )
        const val REQUIRED_SECURITY_ANSWERS = 3
    }

    fun hasAccount(): Boolean = prefs.contains("username")

    fun username(): String = prefs.getString("username", "") ?: ""

    fun biometricEnabled(): Boolean = prefs.getBoolean("biometric_enabled", false)
    fun setBiometricEnabled(value: Boolean) { prefs.edit().putBoolean("biometric_enabled", value).apply() }

    fun notificationEmail(): String = prefs.getString("notification_email", "") ?: ""
    fun setNotificationEmail(value: String) { prefs.edit().putString("notification_email", value.trim()).apply() }

    fun register(username: String, password: String) {
        val salt = randomSalt()
        prefs.edit()
            .putString("username", username.trim())
            .putString("password_salt", salt)
            .putString("password_hash", hash(password, salt))
            .apply()
    }

    fun verifyPassword(password: String): Boolean {
        val salt = prefs.getString("password_salt", null) ?: return false
        val stored = prefs.getString("password_hash", null) ?: return false
        return hash(password, salt) == stored
    }

    fun resetPassword(newPassword: String) {
        val salt = randomSalt()
        prefs.edit().putString("password_salt", salt).putString("password_hash", hash(newPassword, salt)).apply()
    }

    /** Stores answers for exactly the questions present in [answers] (index into
     *  SECURITY_QUESTIONS -> plain answer). Call once, at registration, with
     *  exactly REQUIRED_SECURITY_ANSWERS entries. */
    fun setSecurityAnswers(answers: Map<Int, String>) {
        val editor = prefs.edit()
        for (i in SECURITY_QUESTIONS.indices) {
            val answer = answers[i]
            if (!answer.isNullOrBlank()) {
                val salt = randomSalt()
                editor.putString("sec_answer_salt_$i", salt)
                editor.putString("sec_answer_hash_$i", hash(normalizeAnswer(answer), salt))
            }
        }
        editor.apply()
    }

    /** Which of the 5 preset questions actually have a stored answer — this is
     *  what the recovery screen should show, so the person isn't guessing
     *  which 3 out of 5 they originally picked. */
    fun answeredSecurityIndices(): List<Int> = SECURITY_QUESTIONS.indices.filter { prefs.contains("sec_answer_hash_$it") }

    /** All of the previously-answered questions must be answered correctly now. */
    fun verifySecurityAnswers(answers: Map<Int, String>): Boolean {
        val required = answeredSecurityIndices()
        if (required.isEmpty()) return false
        return required.all { i ->
            val salt = prefs.getString("sec_answer_salt_$i", null) ?: return false
            val stored = prefs.getString("sec_answer_hash_$i", null) ?: return false
            val given = answers[i] ?: return false
            hash(normalizeAnswer(given), salt) == stored
        }
    }

    /** One-time backup recovery code, shown exactly once at registration.
     *  Only its hash is ever stored — the plain code cannot be recovered
     *  again from the app once the reveal screen is dismissed. */
    fun generateAndStoreBackupCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no O/0 or I/1 mix-ups
        val random = SecureRandom()
        fun segment() = (1..4).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val code = "${segment()}-${segment()}-${segment()}"
        val salt = randomSalt()
        prefs.edit().putString("backup_salt", salt).putString("backup_hash", hash(normalizeBackupCode(code), salt)).apply()
        return code
    }

    fun verifyBackupCode(code: String): Boolean {
        val salt = prefs.getString("backup_salt", null) ?: return false
        val stored = prefs.getString("backup_hash", null) ?: return false
        return hash(normalizeBackupCode(code), salt) == stored
    }

    private fun normalizeBackupCode(code: String) = code.trim().uppercase().replace("-", "").replace(" ", "")
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

package ro.alintudor.oracle.core

import android.content.Context
import java.security.MessageDigest

/**
 * Local gate for the ADMIN ONLY screen (Growth engine log, Growth history,
 * server communication log, GrowthLocal-emergency loader + force-local
 * toggle). Two layers, both required:
 *  1. The logged-in account's username must match the owner's — checked
 *     against OracleAuthStore, nothing new to store for this part.
 *  2. A PIN, set once by the owner on this device and never transmitted
 *     anywhere — hashed (SHA-256) before it ever touches storage, so even
 *     reading this device's private prefs directly doesn't recover it.
 * This is a screen-visibility gate for a single-owner app, not a real
 * multi-user permission system — proportionate to what it protects.
 */
object OracleAdminAccess {
    const val OWNER_USERNAME = "AlinTudor"
    private const val PREFS = "oracle_admin"
    private const val KEY_HASH = "pin_hash"

    @Volatile private var unlockedThisProcess = false

    fun isOwnerAccount(context: Context): Boolean =
        OracleAuthStore(context).username().equals(OWNER_USERNAME, ignoreCase = true)

    fun hasPin(context: Context): Boolean = prefs(context).getString(KEY_HASH, null) != null

    fun setPin(context: Context, pin: String) {
        prefs(context).edit().putString(KEY_HASH, hash(pin)).apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean =
        prefs(context).getString(KEY_HASH, null) == hash(pin)

    fun isUnlockedThisProcess(): Boolean = unlockedThisProcess
    fun markUnlocked() { unlockedThisProcess = true }

    /** Locked again on logout — same idea as the main login gate resetting
     *  on a fresh process, except this also resets on an explicit sign-out
     *  within the same process, since a different account could log in next. */
    fun lock() { unlockedThisProcess = false }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

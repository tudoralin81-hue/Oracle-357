package ro.alintudor.oracle.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pre-filled email draft notifying that a new Oracle account was created on
 * this device. Same reasoning as OracleAlertMailer: no backend/SMTP, so this
 * cannot send silently — it opens a ready-to-send draft and the person taps
 * Send once in their own mail app.
 */
object OracleAccountMailer {
    fun open(context: Context, email: String, username: String) {
        if (email.isBlank()) return
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, "Oracle — new account created")
            putExtra(Intent.EXTRA_TEXT, "A new Oracle account was just created on this device.\n\nUsername: $username\nWhen: $stamp\n\nIf this wasn't you, this device's Oracle app may be accessible to someone else.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

package ro.alintudor.oracle.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notifies that a new Oracle account was created. Sent from the server
 * (wp_mail() via the /notify endpoint) using the session token from the
 * registration call that just succeeded — the phone never handles an email
 * password. Falls back to a tap-to-send draft only if the server call
 * itself fails (e.g. no connectivity right at that moment).
 */
object OracleAccountMailer {
    fun open(context: Context, email: String, username: String, token: String) {
        if (email.isBlank()) return
        val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date())
        val subject = "Lux Oculi — new account created"
        val body = "A new Lux Oculi account was just created.\n\nUsername: $username\nWhen: $stamp\n\nIf this wasn't you, someone else may have access to this account."
        Thread {
            OracleApiClient.notify(token, subject, body).onFailure {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        }.start()
    }
}

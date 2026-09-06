package ro.alintudor.oracle.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Builds a pre-filled email draft for a critical alert, addressed to the
 * email registered in Alerts settings.
 *
 * Oracle has no backend/SMTP server, so it cannot silently send mail in the
 * background — Android does not allow that without real mail-account
 * credentials. This builds a ready-to-send draft (recipient, subject, and
 * body already filled in); the person taps it once — from the push
 * notification, or from the "Email this alert" button on the alert card —
 * and just presses Send in their own mail app.
 */
object OracleAlertMailer {
    fun buildIntent(email: String, alert: OracleAlert): Intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
        putExtra(Intent.EXTRA_SUBJECT, "Lux Oculi alert — ${alert.ticker}: ${alert.title}")
        putExtra(Intent.EXTRA_TEXT, "${alert.message}\n\nSent by Lux Oculi. Informational only — not investment advice.")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, email: String, alert: OracleAlert) {
        if (email.isBlank()) return
        runCatching { context.startActivity(buildIntent(email, alert)) }
    }
}

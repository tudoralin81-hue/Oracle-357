package ro.alintudor.oracle.core

import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends email directly over SMTP using the account configured in
 * OracleServerSettingsStore — no tap, no draft, no mail app involved.
 *
 * Must run off the main thread (this does blocking network I/O). Every
 * caller in this app wraps it in a background Thread.
 */
object OracleSmtpMailer {
    fun send(settings: OracleServerSettingsStore, toEmail: String, subject: String, body: String): Result<Unit> = runCatching {
        val host = settings.smtpHost()
        val port = settings.smtpPort()
        val username = settings.smtpUsername()
        val password = settings.smtpPassword()

        val props = Properties().apply {
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "10000")
            if (port == 465) {
                put("mail.smtp.socketFactory.port", port.toString())
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.socketFactory.fallback", "false")
            } else {
                put("mail.smtp.starttls.enable", "true")
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(username, password)
        })
        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(username))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
            setSubject(subject)
            setText(body)
        }
        Transport.send(message)
    }

    /** Sends via SMTP if configured; otherwise falls back to the tap-to-send
     *  draft Intent, so email always works regardless of setup. */
    fun sendOrFallback(context: android.content.Context, settings: OracleServerSettingsStore, toEmail: String, subject: String, body: String, fallback: () -> Unit) {
        if (toEmail.isBlank()) return
        if (settings.smtpConfigured()) {
            Thread { send(settings, toEmail, subject, body) }.start()
        } else {
            fallback()
        }
    }
}

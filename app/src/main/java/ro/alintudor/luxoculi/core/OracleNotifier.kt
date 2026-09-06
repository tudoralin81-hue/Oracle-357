package ro.alintudor.luxoculi.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Posts local push notifications for critical Portfolio alerts (urgent sell,
 *  fading growth, high volatility). No server involved — this runs entirely
 *  on the device, only while the app itself is open and refreshing data.
 *  Pure Android SDK, no AndroidX, matching the rest of Oracle.
 *
 *  If an email is registered, tapping the notification opens a pre-filled
 *  draft for that alert (one tap to send) rather than the app itself — a
 *  silent background auto-send isn't possible without real SMTP credentials,
 *  and jumping straight to the mail app unprompted mid-navigation would be
 *  jarring, so the notification is the trigger and email is one tap away. */
object OracleNotifier {
    private const val CHANNEL_ID = "oracle_critical_alerts"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Critical portfolio alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Urgent sell signals, fading rallies, and high-volatility moves in your Portfolio."
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun notify(context: Context, alert: OracleAlert, email: String) {
        runCatching {
            ensureChannel(context)
            if (!hasPermission(context)) return
            val id = (alert.ticker + alert.kind).hashCode()
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("${alert.ticker} — ${alert.title}")
                .setContentText(alert.message)
                .setStyle(Notification.BigTextStyle().bigText(alert.message))
                .setAutoCancel(true)
            if (email.isNotBlank()) {
                val mailIntent = OracleAlertMailer.buildIntent(email, alert)
                val pendingIntent = PendingIntent.getActivity(context, id, mailIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(pendingIntent)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(id, builder.build())
        }
    }
}

package ro.alintudor.oracle.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ro.alintudor.oracle.OracleMysticActivity
import ro.alintudor.oracle.R

/**
 * Receives real push notifications sent by the server (alintudor.ro, via
 * Firebase Cloud Messaging) and keeps the device's FCM token registered
 * with the server so it knows where to push to.
 *
 * Pure Android SDK for the notification itself (Notification.Builder, no
 * AndroidX), matching OracleNotifier — only the FCM plumbing itself needs
 * the Firebase library.
 */
class OracleFirebaseMessagingService : FirebaseMessagingService() {
    companion object {
        private const val CHANNEL_ID = "oracle_push"

        /** Called after a successful login/register, once we have both a
         *  session token and (usually already cached) an FCM token. */
        fun registerCurrentToken(context: Context) {
            val auth = OracleAuthStore(context)
            if (!auth.hasSession()) return
            runCatching {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
                    Thread { OracleApiClient.registerDevice(auth.token(), fcmToken) }.start()
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val auth = OracleAuthStore(this)
        if (auth.hasSession()) {
            Thread { OracleApiClient.registerDevice(auth.token(), token) }.start()
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "Oracle"
        val body = message.notification?.body ?: message.data["body"] ?: return
        showNotification(title, body)
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "Oracle", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts and notifications sent from the Oracle server."
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun showNotification(title: String, body: String) {
        runCatching {
            ensureChannel()
            if (!hasPermission()) return
            val launchIntent = Intent(this, OracleMysticActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_oracle)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}

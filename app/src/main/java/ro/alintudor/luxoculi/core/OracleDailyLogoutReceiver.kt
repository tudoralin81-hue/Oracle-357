package ro.alintudor.luxoculi.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Forces a fresh login once a day, at 15:00 device-local time — before the
 * 16:00 Growth snapshot — as a routine security refresh rather than letting
 * a session stay valid indefinitely. Fires whether or not the app is open:
 * a background hit just clears the stored session so the next open shows
 * login; a foreground session gets caught by the same check in
 * OracleMysticActivity's own resume path (see checkDailyLogoutIfDue()),
 * since a BroadcastReceiver has no direct way to redirect an already-open
 * screen.
 */
class OracleDailyLogoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) { schedule(app); return }
        runCatching { applyIfDue(app) }
        schedule(app)
    }

    companion object {
        private const val REQUEST_CODE = 7115
        private const val PREFS = "oracle_daily_logout"
        private val dayStamp = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        fun schedule(context: Context) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                app, REQUEST_CODE, Intent(app, OracleDailyLogoutReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pendingIntent)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 15); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= 23)
                alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            else alarm.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        }

        /** Idempotent per calendar day — safe to call from both the alarm
         *  and the Activity's own resume check without double-firing.
         *  Returns true if a session was actually cleared just now (so the
         *  caller can react immediately if it's showing a live screen). */
        fun applyIfDue(context: Context): Boolean {
            val app = context.applicationContext
            val now = Calendar.getInstance()
            if (now.get(Calendar.HOUR_OF_DAY) < 15) return false
            val today = dayStamp.format(Date())
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString("last_applied_day", "") == today) return false
            prefs.edit().putString("last_applied_day", today).apply()
            val store = OracleAuthStore(app)
            if (!store.hasSession() || OracleDemo.active(app)) return false
            store.clearSession()
            OracleAdminAccess.lock()
            ro.alintudor.luxoculi.widget.OracleGrowthWidgetProvider.updateAll(app)
            OracleGrowthLog.log(app, "AUTH", "Daily 15:00 security logout applied")
            return true
        }
    }
}

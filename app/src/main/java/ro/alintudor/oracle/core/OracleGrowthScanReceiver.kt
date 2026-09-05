package ro.alintudor.oracle.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar

/**
 * Runs the full-universe Growth scan in the background, once per trading day,
 * shortly after the US close — the moment the day's candles are final and
 * nobody is waiting on the screen.
 *
 * This is what makes covering ~1,400 names possible without a server: the
 * on-screen run has a 20-second budget, this one has minutes. When the user
 * opens Growth afterwards, the engine finds a complete scan already cached
 * for the current trading anchor and only has to rank it.
 *
 * If a wake-up gets cut short by the OS, the scan resumes where it left off
 * (results are written incrementally and keyed by ticker), so several partial
 * wake-ups still converge on full coverage.
 */
class OracleGrowthScanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) { schedule(app); return }
        val pending = goAsync()
        Thread {
            try {
                runCatching { OracleGrowthEngine.scanFullUniverse(app) }
            } finally {
                schedule(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE = 7109

        /** Next run: 23:30 Bucharest (≈30 min after the 16:00 ET close settles). */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(app, OracleGrowthScanReceiver::class.java)
            val pending = android.app.PendingIntent.getBroadcast(app, REQUEST_CODE, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 30)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
            }
            // Weekends produce no new closes — skip straight to Monday night.
            while (cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            if (android.os.Build.VERSION.SDK_INT >= 23)
                alarm.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
            else alarm.set(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pending)
        }

        /**
         * Kicks off a catch-up scan right now if today's trading day has no
         * cached scan yet — e.g. the app was installed today, or the device was
         * off overnight. Fire-and-forget, never blocks the caller.
         */
        fun scanNowIfMissing(context: Context) {
            val app = context.applicationContext
            if (OracleGrowthEngine.fullScanState.running) return
            Thread {
                if (!OracleGrowthEngine.hasFreshFullScan(app)) {
                    runCatching { OracleGrowthEngine.scanFullUniverse(app) }
                }
            }.start()
        }
    }
}

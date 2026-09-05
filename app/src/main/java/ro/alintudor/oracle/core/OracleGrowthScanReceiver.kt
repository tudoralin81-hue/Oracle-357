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
        val isRetry = intent?.action == ACTION_MORNING_RETRY
        val pending = goAsync()
        Thread {
            try {
                // The morning slot is a safety net, not a second scan: if the
                // night run already covered this trading day, it does nothing.
                if (!isRetry || !OracleGrowthEngine.hasFreshFullScan(app)) {
                    runCatching { OracleGrowthEngine.scanFullUniverse(app) }
                }
            } finally {
                schedule(app)
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val REQUEST_CODE_NIGHT = 7109
        private const val REQUEST_CODE_MORNING = 7110
        const val ACTION_MORNING_RETRY = "ro.alintudor.oracle.GROWTH_SCAN_MORNING_RETRY"

        /**
         * Two daily slots, because one is not reliable enough:
         *  - 23:30, the primary run, right after the day's closes settle;
         *  - 08:15, a retry that only does anything if the night slot did not
         *    complete. Doze and battery optimisation can defer or drop a
         *    night-time wake-up entirely (that is exactly what they are for),
         *    and the morning slot lands when the phone is typically unlocked,
         *    charging or on Wi-Fi. Both target the same trading anchor, so the
         *    morning run fills in the same day the night run was meant to.
         */
        fun schedule(context: Context) {
            scheduleSlot(context, REQUEST_CODE_NIGHT, 23, 30, null)
            scheduleSlot(context, REQUEST_CODE_MORNING, 8, 15, ACTION_MORNING_RETRY)
        }

        private fun scheduleSlot(context: Context, requestCode: Int, hour: Int, minute: Int, action: String?) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(app, OracleGrowthScanReceiver::class.java).apply { if (action != null) setAction(action) }
            val pending = android.app.PendingIntent.getBroadcast(app, requestCode, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            alarm.cancel(pending)
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
            }
            // Saturday/Sunday bring no new closes. A Saturday-morning retry is
            // still allowed: it can finish Friday's scan if Friday night failed.
            while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY ||
                (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY && action == null)) {
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

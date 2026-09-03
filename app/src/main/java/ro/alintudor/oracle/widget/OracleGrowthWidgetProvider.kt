package ro.alintudor.oracle.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.widget.RemoteViews
import ro.alintudor.oracle.OracleMysticActivity
import ro.alintudor.oracle.R
import ro.alintudor.oracle.core.OracleGrowthRecommendation
import ro.alintudor.oracle.core.OracleRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home-screen widget: the 3 current Growth recommendations (short/medium/
 * long horizon). Tapping it opens the app directly at Growth.
 *
 * On refresh cadence: the requested "every 3 minutes" is tighter than what
 * Android's widget platform actually supports — updatePeriodMillis is
 * silently clamped to a 30-minute floor by the OS no matter what's declared.
 * To get closer to 3 minutes, this widget schedules its own AlarmManager
 * alarm and reschedules itself every time it fires, rather than relying on
 * updatePeriodMillis at all. Android's Doze / battery optimization can still
 * delay that while the phone sits idle with the screen off — no regular app
 * can fully override that. While the phone is actively in use, this stays
 * close to a 3-minute cadence.
 */
class OracleGrowthWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val ACTION_REFRESH = "ro.alintudor.oracle.widget.ACTION_REFRESH_GROWTH"
        private const val ALARM_REQUEST_CODE = 35701
        private const val LAUNCH_REQUEST_CODE = 35702
        private const val REFRESH_INTERVAL_MS = 3L * 60L * 1000L

        private data class Slot(val horizon: String, val tickerId: Int, val signalId: Int, val riskId: Int, val potentialId: Int, val accent: Int)

        fun scheduleNext(context: Context) {
            val app = context.applicationContext
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(app, OracleGrowthWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val pending = PendingIntent.getBroadcast(app, ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.cancel(pending)
            val next = System.currentTimeMillis() + REFRESH_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= 23) alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
            else alarmManager.set(AlarmManager.RTC_WAKEUP, next, pending)
        }

        private fun cancelSchedule(context: Context) {
            val app = context.applicationContext
            val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(app, OracleGrowthWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val pending = PendingIntent.getBroadcast(app, ALARM_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.cancel(pending)
        }

        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, OracleGrowthWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val items = runCatching { OracleRepository(appContext).cachedGrowth() }.getOrDefault(emptyList())
            val views = buildViews(appContext, items)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun buildViews(context: Context, items: List<OracleGrowthRecommendation>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.oracle_growth_widget)
            val cyan = Color.rgb(75, 225, 255)
            val orange = Color.rgb(255, 160, 25)
            val green = Color.rgb(105, 245, 35)
            val slots = listOf(
                Slot("SHORT", R.id.short_ticker, R.id.short_signal, R.id.short_risk, R.id.short_potential, cyan),
                Slot("MEDIUM", R.id.medium_ticker, R.id.medium_signal, R.id.medium_risk, R.id.medium_potential, orange),
                Slot("LONG", R.id.long_ticker, R.id.long_signal, R.id.long_risk, R.id.long_potential, green)
            )
            val red = Color.rgb(255, 90, 90)
            for (slot in slots) {
                val item = items.firstOrNull { it.horizon.equals(slot.horizon, true) }
                if (item == null) {
                    views.setTextViewText(slot.tickerId, "—")
                    views.setTextViewText(slot.signalId, "no data")
                    views.setTextViewText(slot.riskId, "")
                    views.setTextViewText(slot.potentialId, "")
                } else {
                    views.setTextViewText(slot.tickerId, item.ticker.uppercase(Locale.US))
                    views.setTextViewText(slot.signalId, "${item.signal} · ${item.score}/100")
                    val riskColor = when {
                        item.risk.contains("HIGH", true) -> red
                        item.risk.contains("LOW", true) -> green
                        else -> orange
                    }
                    views.setTextViewText(slot.riskId, "${item.risk.uppercase(Locale.US)} risk")
                    views.setTextColor(slot.riskId, riskColor)
                    val sign = if (item.forecastPct >= 0) "+" else ""
                    val potentialColor = if (item.forecastPct >= 0) green else red
                    val label = "Potential "
                    val value = "$sign${"%.1f".format(Locale.US, item.forecastPct)}%"
                    val spanned = android.text.SpannableString(label + value).apply {
                        setSpan(android.text.style.ForegroundColorSpan(orange), 0, label.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(android.text.style.ForegroundColorSpan(potentialColor), label.length, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    views.setTextViewText(slot.potentialId, spanned)
                }
                views.setTextColor(slot.tickerId, Color.WHITE)
                views.setTextColor(slot.signalId, slot.accent)
            }
            val newestTimestamp = items.maxOfOrNull { it.referenceTimestamp } ?: 0L
            val stamp = if (newestTimestamp > 0L) {
                val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
                "Snapshot ${fmt.format(Date(newestTimestamp))}"
            } else "No snapshot yet — open Oracle"
            views.setTextViewText(R.id.widget_updated, stamp)

            val marketStatus = runCatching { ro.alintudor.oracle.core.OracleMarketCalendar.status() }.getOrNull()
            if (marketStatus != null) {
                views.setTextViewText(R.id.widget_market_status, if (marketStatus.open) "● MARKET OPEN" else "● MARKET CLOSED")
                views.setTextColor(R.id.widget_market_status, if (marketStatus.open) green else red)
                views.setInt(
                    R.id.widget_market_status, "setBackgroundResource",
                    if (marketStatus.open) R.drawable.widget_status_open_bg else R.drawable.widget_status_closed_bg
                )
            }

            val launchIntent = Intent(context, OracleMysticActivity::class.java).apply {
                putExtra("open_module", "growth")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val launchPending = PendingIntent.getActivity(context, LAUNCH_REQUEST_CODE, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, launchPending)
            return views
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
        scheduleNext(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context)
            scheduleNext(context)
        }
    }

    override fun onEnabled(context: Context) {
        scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        cancelSchedule(context)
    }
}

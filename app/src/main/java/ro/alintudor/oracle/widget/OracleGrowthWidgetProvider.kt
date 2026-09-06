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
        private const val SETTINGS_REQUEST_CODE = 35703
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
            // Re-checked on every single update, not just once: a home-screen
            // widget is visible to anyone who can see the phone's home screen,
            // without ever opening or unlocking the app itself — it must never
            // keep showing real recommendations after a logout, and must never
            // show them at all during a demo session (which is meant to be
            // locked/sample data everywhere else in the app). The cached data
            // itself isn't touched or cleared here — only what the widget is
            // willing to display right now.
            val authorized = runCatching {
                ro.alintudor.oracle.core.OracleAuthStore(appContext).hasSession() && !ro.alintudor.oracle.core.OracleDemo.active(appContext)
            }.getOrDefault(false)
            val items = if (authorized) runCatching { OracleRepository(appContext).cachedGrowth() }.getOrDefault(emptyList()) else emptyList()
            val views = buildViews(appContext, items)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        /** The check above only ever looks at the LOCAL cached token — a
         *  revoked user's widget would otherwise keep serving real, stale
         *  data forever, since nothing else touches the widget between app
         *  opens. Called once per alarm tick alongside the synchronous
         *  redraw above; on a real 401 it clears the session and forces an
         *  immediate re-render into the logged-out state. Any other failure
         *  (no network, timeout, server hiccup) does nothing — only an
         *  actual "this token is no longer valid" answer should ever log
         *  someone out from here. Blocking — the caller (onReceive, via its
         *  own background thread) is responsible for staying off the main
         *  thread; this must NOT spawn its own thread, or the caller's
         *  goAsync() would finish() before the network call ever completes. */
        fun validateSessionAsync(context: Context) {
            val appContext = context.applicationContext
            val store = ro.alintudor.oracle.core.OracleAuthStore(appContext)
            if (!store.hasSession() || ro.alintudor.oracle.core.OracleDemo.active(appContext)) return
            val token = store.token()
            if (token.isBlank()) return
            var e: Throwable? = ro.alintudor.oracle.core.OracleApiClient.checkSession(token).exceptionOrNull()
            var revoked = false
            while (e != null) {
                if (e is ro.alintudor.oracle.core.OracleUnauthorizedException) { revoked = true; break }
                e = e.cause
            }
            if (revoked) {
                store.clearSession()
                ro.alintudor.oracle.core.OracleAdminAccess.lock()
                updateAll(appContext)
            }
        }

        private fun generateBackgroundBitmap(baseColor: Int): android.graphics.Bitmap {
            val width = 320; val height = 200
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val corner = 28f
            val roundRect = android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = baseColor }
            canvas.drawRoundRect(roundRect, corner, corner, bgPaint)

            val clipPath = android.graphics.Path().apply { addRoundRect(roundRect, corner, corner, android.graphics.Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)

            val linePaint = android.graphics.Paint().apply { color = Color.argb(70, 255, 255, 255); strokeWidth = 1f }
            var x = 0
            while (x <= width) { canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), linePaint); x += 20 }
            var y = 0
            while (y <= height) { canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), linePaint); y += 20 }

            // Rising-trend arrow watermark, bottom-left corner to top-right corner —
            // a subtle backdrop element, sits under the text content drawn on top.
            val arrowColor = Color.argb(85, 105, 245, 35)
            val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = arrowColor; style = android.graphics.Paint.Style.STROKE
                strokeWidth = 7f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val pts = listOf(14f to 188f, 68f to 138f, 98f to 163f, 158f to 96f, 188f to 122f, 262f to 40f, 300f to 16f)
            val arrowPath = android.graphics.Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
            }
            canvas.drawPath(arrowPath, arrowPaint)
            val (tipX, tipY) = pts.last()
            val (prevX, prevY) = pts[pts.size - 2]
            val dx = tipX - prevX; val dy = tipY - prevY
            val len = kotlin.math.sqrt(dx * dx + dy * dy).takeIf { it > 0f } ?: 1f
            val ux = dx / len; val uy = dy / len
            val headLen = 22f; val headWidth = 13f
            val baseX = tipX - ux * headLen; val baseY = tipY - uy * headLen
            val perpX = -uy; val perpY = ux
            val headFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = arrowColor; style = android.graphics.Paint.Style.FILL }
            val headPath = android.graphics.Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX + perpX * headWidth, baseY + perpY * headWidth)
                lineTo(baseX - perpX * headWidth, baseY - perpY * headWidth)
                close()
            }
            canvas.drawPath(headPath, headFill)

            canvas.restore()
            return bitmap
        }

        private fun buildViews(context: Context, items: List<OracleGrowthRecommendation>): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.oracle_growth_widget)
            views.setImageViewBitmap(R.id.widget_bg_image, generateBackgroundBitmap(OracleWidgetSettingsStore.color(context)))
            val gold = Color.rgb(255, 205, 55) // matches the START screen's brand title color
            val titleGreen = Color.rgb(105, 245, 35)
            val brandName = "LUX OCULI"
            val titleText = "$brandName GROWTH"
            val titleSpanned = android.text.SpannableString(titleText).apply {
                setSpan(android.text.style.ForegroundColorSpan(gold), 0, brandName.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(titleGreen), brandName.length, titleText.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            views.setTextViewText(R.id.widget_title, titleSpanned)
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
                    val neutral = Color.rgb(180, 190, 205)
                    val label = "Growth Potential "
                    val value = "$sign${"%.1f".format(Locale.US, item.forecastPct)}%"
                    val spanned = android.text.SpannableString(label + value).apply {
                        setSpan(android.text.style.ForegroundColorSpan(neutral), 0, label.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            } else "No snapshot yet — open Lux Oculi"
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

            val settingsIntent = Intent(context, OracleWidgetConfigActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val settingsPending = PendingIntent.getActivity(context, SETTINGS_REQUEST_CODE, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_settings_button, settingsPending)
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
            // goAsync(): a BroadcastReceiver's onReceive must return quickly,
            // but validating the session needs a real network round-trip.
            // This extends the execution window; finish() is guaranteed to
            // run exactly once, either when the check completes or after
            // the timeout, whichever comes first — never left dangling.
            val pending = goAsync()
            val appContext = context.applicationContext
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)
            val finish = { if (finished.compareAndSet(false, true)) runCatching { pending.finish() } }
            handler.postDelayed({ finish() }, 9_000L)
            Thread {
                runCatching { validateSessionAsync(appContext) }
                finish()
            }.start()
        }
    }

    override fun onEnabled(context: Context) {
        scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        cancelSchedule(context)
    }
}

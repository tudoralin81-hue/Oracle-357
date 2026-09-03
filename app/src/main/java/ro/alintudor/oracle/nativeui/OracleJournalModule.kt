package ro.alintudor.oracle.nativeui

import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import ro.alintudor.oracle.core.*
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rich offline activity journal and history timeline, with real file export. */
class OracleJournalModule(private val host: OracleNativeModule) {
    private val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    private val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun render(journal: List<OracleJournalEntry>, history: List<OracleHistoryPoint>, alerts: List<OracleAlert>) {
        host.content.removeAllViews()
        host.addCard("ACTIVITY JOURNAL", "Complete history of Oracle actions, alerts and movements")
        val actions = journal.map { OracleAction(it.ticker, it.action, it.score, it.reason, it.timestamp) }
        val timeline = OracleLocalTimeline.build(history, actions, alerts)
        addSummary(timeline.size, actions.size, alerts.count { it.active })
        addDownloadButton(journal, timeline)
        if (timeline.isEmpty()) {
            host.addCard("NO ACTIVITY", "There are no local events yet.")
            return
        }
        timeline.take(150).forEachIndexed { i, item -> addItem(i + 1, item) }
    }

    private fun addSummary(events: Int, actions: Int, activeAlerts: Int) {
        val row = LinearLayout(host.root.context).apply { orientation = LinearLayout.HORIZONTAL }
        stat(row, "EVENTS", events.toString(), Color.rgb(70, 185, 255))
        stat(row, "ACTIONS", actions.toString(), Color.rgb(255, 205, 45))
        stat(row, "ACTIVE ALERTS", activeAlerts.toString(), Color.rgb(255, 75, 60))
        host.content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun addDownloadButton(journal: List<OracleJournalEntry>, timeline: List<OracleTimelineItem>) {
        val button = TextView(host.root.context).apply {
            text = "DOWNLOAD ACTIVITY JOURNAL"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70, 215, 255))
            background = OracleNativeModule.rounded(Color.rgb(8, 12, 25), host.dp(11), Color.rgb(70, 215, 255), host.dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { exportJournal(journal, timeline) }
        }
        host.content.addView(button, LinearLayout.LayoutParams(-1, host.dp(48)).apply { setMargins(0, 0, 0, host.dp(10)) })
    }

    private fun exportJournal(journal: List<OracleJournalEntry>, timeline: List<OracleTimelineItem>) {
        val stamp = fileDate.format(Date())
        val filename = "oracle_activity_journal_$stamp.csv"
        val csv = buildString {
            append("Date/Time,Ticker,Type,Severity,Title,Details\n")
            timeline.take(250).forEach { item ->
                append(csvField(date.format(Date(item.timestamp))))
                append(',').append(csvField(item.ticker))
                append(',').append(csvField(item.type))
                append(',').append(csvField(item.severity))
                append(',').append(csvField(item.title))
                append(',').append(csvField(item.detail)).append('\n')
            }
            if (timeline.isEmpty()) {
                journal.sortedByDescending { it.timestamp }.take(250).forEach { e ->
                    append(csvField(date.format(Date(e.timestamp))))
                    append(',').append(csvField(e.ticker))
                    append(',').append(csvField(e.action))
                    append(',').append(csvField(e.status))
                    append(',').append(csvField("Score ${String.format(Locale.US, "%.1f", e.score)}"))
                    append(',').append(csvField(e.reason)).append('\n')
                }
            }
        }
        saveDownload(filename, "text/csv") { it.write(csv.toByteArray(Charsets.UTF_8)) }
    }

    private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")}\""

    private fun saveDownload(fileName: String, mime: String, writer: (OutputStream) -> Unit) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Oracle")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = host.root.context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create the file in Downloads/Oracle")
                try {
                    host.root.context.contentResolver.openOutputStream(uri)?.use(writer)
                        ?: error("Could not write the file")
                    host.root.context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    host.root.context.contentResolver.delete(uri, null, null)
                    throw e
                }
            } else {
                val dir = host.root.context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: host.root.context.filesDir
                dir.mkdirs()
                File(dir, fileName).outputStream().use(writer)
            }
            Toast.makeText(host.root.context, "Journal downloaded: Downloads/Oracle/$fileName", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(host.root.context, "Journal export failed: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stat(row: LinearLayout, label: String, value: String, color: Int) {
        val box = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(host.dp(8), host.dp(10), host.dp(8), host.dp(10))
            background = OracleNativeModule.rounded(Color.rgb(7, 11, 22), host.dp(11), Color.rgb(35, 44, 66), host.dp(1))
        }
        box.addView(TextView(host.root.context).apply { text = label; textSize = 9f; setTextColor(Color.rgb(145, 155, 176)); gravity = Gravity.CENTER })
        box.addView(TextView(host.root.context).apply { text = value; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color); gravity = Gravity.CENTER })
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(host.dp(3), 0, host.dp(3), 0) })
    }

    private fun addItem(rank: Int, item: OracleTimelineItem) {
        val accent = when (item.severity.uppercase(Locale.getDefault())) {
            "HIGH" -> Color.rgb(255, 75, 60)
            "MEDIUM" -> Color.rgb(255, 205, 45)
            else -> Color.rgb(70, 185, 255)
        }
        val card = LinearLayout(host.root.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(14), host.dp(12), host.dp(14), host.dp(12))
            background = OracleNativeModule.rounded(Color.rgb(6, 10, 20), host.dp(13), Color.rgb(38, 47, 68), host.dp(1))
        }
        val top = LinearLayout(host.root.context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(host.root.context).apply { text = "%02d".format(rank); textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(host.dp(30), host.dp(25)))
        top.addView(TextView(host.root.context).apply { text = item.ticker; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(TextView(host.root.context).apply { text = item.type.uppercase(Locale.getDefault()); textSize = 9f; typeface = Typeface.DEFAULT_BOLD; setTextColor(accent); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(host.dp(80), host.dp(25)))
        card.addView(top)
        card.addView(TextView(host.root.context).apply { text = date.format(Date(item.timestamp)); textSize = 10f; setTextColor(Color.rgb(125, 137, 158)); setPadding(host.dp(30), host.dp(3), 0, 0) })
        card.addView(TextView(host.root.context).apply { text = item.title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(host.dp(30), host.dp(5), 0, 0) })
        card.addView(TextView(host.root.context).apply { text = item.detail; textSize = 12f; setTextColor(Color.rgb(175, 183, 201)); setPadding(host.dp(30), host.dp(3), 0, 0) })
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, host.dp(8)) })
    }
}

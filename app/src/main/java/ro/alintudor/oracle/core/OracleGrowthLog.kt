package ro.alintudor.oracle.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Operational log of everything the Growth engine does: every run, every
 * universe resolution, every scan (foreground and background), what was
 * fetched, what was enriched, what was picked and why, and every failure.
 *
 * This is the diagnostic counterpart to the Growth journal: the journal
 * records the *recommendations*, this records the *work* — so when a run
 * produces a surprising result, or nothing at all, there is a written trail
 * instead of guesswork.
 *
 * Append-only, capped, written to the app's private files directory and
 * exportable as plain text from TOOLS. Every call is safe from any thread and
 * can never throw into the caller: logging must not be able to break a run.
 */
object OracleGrowthLog {
    private const val FILE_NAME = "oracle_growth_log.txt"
    private const val MAX_LINES = 4000
    private const val TRIM_TO = 3000

    private val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** @param tag short area, e.g. RUN / SCAN / UNIVERSE / ENRICH / RANK / CACHE / ERROR */
    fun log(context: Context?, tag: String, message: String) {
        if (context == null) return
        runCatching {
            synchronized(lock) {
                val f = file(context)
                f.appendText("${stamp.format(Date())}  [$tag] $message\n")
                // Trim only when it actually grows past the cap, so the common
                // path is a plain append and never a full rewrite.
                if (f.length() > 400_000L) {
                    val lines = f.readLines()
                    if (lines.size > MAX_LINES) f.writeText(lines.takeLast(TRIM_TO).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun read(context: Context, lastLines: Int = 400): List<String> = runCatching {
        val f = file(context)
        if (!f.exists()) emptyList() else f.readLines().takeLast(lastLines)
    }.getOrDefault(emptyList())

    fun lineCount(context: Context): Int = runCatching {
        val f = file(context); if (!f.exists()) 0 else f.readLines().size
    }.getOrDefault(0)

    fun clear(context: Context) {
        runCatching { synchronized(lock) { file(context).delete() } }
    }

    /** Writes the whole log to Downloads. Returns the path/uri, or null. */
    fun export(context: Context): String? = runCatching {
        val f = file(context)
        val body = if (f.exists()) f.readText() else ""
        if (body.isBlank()) return@runCatching null
        val header = "LUX OCULI — GROWTH ENGINE LOG\nExported ${stamp.format(Date())}\n" +
            "Engine factors: ${OracleGrowthEngine.factorCount()}\n" +
            "".padEnd(60, '-') + "\n\n"
        val filename = "Oracle_Growth_Log_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.txt"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            context.contentResolver.openOutputStream(uri).use { out -> out?.write((header + body).toByteArray(Charsets.UTF_8)) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching null
            val out = File(dir, filename)
            out.writeText(header + body)
            out.absolutePath
        }
    }.getOrNull()
}

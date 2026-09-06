package ro.alintudor.luxoculi.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata-only record of every call OracleApiClient makes to the server:
 * timestamp, method, endpoint, HTTP outcome (or a network-level failure,
 * when there was no response at all), and body sizes — deliberately NEVER
 * the request or response body content itself.
 *
 * This is what TOOLS' "Server Communication" section shows: proof the app
 * is actually talking to the server, without a screenshot of it ever being
 * able to leak a real ticker, score, or signal.
 *
 * Append-only, capped, written to the app's private files directory. Every
 * call is safe from any thread and can never throw into the caller —
 * logging must not be able to break a real request.
 */
object OracleNetworkLog {
    private const val FILE_NAME = "oracle_network_log.txt"
    private const val MAX_LINES = 1000
    private const val TRIM_TO = 700

    private val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
    private val lock = Any()

    private fun file(): File? = runCatching { File(OracleApp.context.filesDir, FILE_NAME) }.getOrNull()

    /** @param code null means the request never got a response at all
     *  (DNS failure, timeout, no connectivity) — distinct from a real HTTP
     *  error code, which still means the server was reachable. */
    fun log(method: String, path: String, code: Int?, responseBytes: Int) {
        runCatching {
            synchronized(lock) {
                val f = file() ?: return
                val outcome = when {
                    code == null -> "NO RESPONSE (network)"
                    code in 200..299 -> "OK $code"
                    else -> "ERROR $code"
                }
                f.appendText("${stamp.format(Date())}  $method $path -> $outcome (${responseBytes}B)\n")
                // Trim only when it actually grows past the cap, so the common
                // path is a plain append and never a full rewrite.
                if (f.length() > 150_000L) {
                    val lines = f.readLines()
                    if (lines.size > MAX_LINES) f.writeText(lines.takeLast(TRIM_TO).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    fun read(lastLines: Int = 200): List<String> = runCatching {
        val f = file() ?: return emptyList()
        if (!f.exists()) emptyList() else f.readLines().takeLast(lastLines)
    }.getOrDefault(emptyList())

    fun lineCount(): Int = runCatching {
        val f = file() ?: return 0
        if (!f.exists()) 0 else f.readLines().size
    }.getOrDefault(0)

    fun clear() {
        runCatching { synchronized(lock) { file()?.delete() } }
    }
}

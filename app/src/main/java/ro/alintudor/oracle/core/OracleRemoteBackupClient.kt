package ro.alintudor.oracle.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a small custom REST route on alintudor.ro (WordPress) that
 * stores the Oracle backup JSON server-side. Must run off the main thread —
 * every caller wraps this in a background Thread.
 */
object OracleRemoteBackupClient {
    private const val TIMEOUT = 15000
    private const val ROUTE = "/wp-json/oracle/v1/backup"

    fun upload(settings: OracleServerSettingsStore, jsonBody: String): Result<Unit> = runCatching {
        val url = URL(settings.remoteBackupUrl() + ROUTE)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Oracle-Token", settings.remoteBackupToken())
        }
        try {
            connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) {
                val err = runCatching { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }.getOrNull()
                throw IllegalStateException("HTTP ${connection.responseCode}${if (!err.isNullOrBlank()) ": $err" else ""}")
            }
        } finally { connection.disconnect() }
    }

    fun download(settings: OracleServerSettingsStore): Result<String> = runCatching {
        val url = URL(settings.remoteBackupUrl() + ROUTE)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            requestMethod = "GET"
            setRequestProperty("X-Oracle-Token", settings.remoteBackupToken())
        }
        try {
            if (connection.responseCode !in 200..299) {
                val err = runCatching { connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() }.getOrNull()
                throw IllegalStateException("HTTP ${connection.responseCode}${if (!err.isNullOrBlank()) ": $err" else ""}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }
}

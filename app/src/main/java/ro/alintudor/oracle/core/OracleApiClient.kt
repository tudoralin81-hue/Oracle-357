package ro.alintudor.oracle.core

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the real Oracle backend on alintudor.ro: server-side accounts,
 * per-user data storage, server-sent email, and FCM device registration.
 *
 * Every call here does blocking network I/O — always run from a background
 * thread, never the main thread.
 */
/** Thrown specifically for HTTP 401 from the real backend — the server's own
 *  oracle_authenticate() returns this whenever the account behind this token
 *  isn't "approved" anymore (the owner revoked access, or it was never
 *  approved). Callers should treat this as "log this session out," not as a
 *  transient network failure to retry. */
class OracleUnauthorizedException(message: String) : Exception(message)

object OracleApiClient {
    private const val TIMEOUT = 15000
    private const val BASE_URL = "https://alintudor.ro/wp-json/oracle/v1"

    private fun connection(path: String, method: String, token: String? = null): HttpURLConnection {
        val url = URL(BASE_URL + path)
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT; readTimeout = TIMEOUT
            requestMethod = method
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (method == "POST") doOutput = true
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val method = connection.requestMethod ?: "GET"
        val path = runCatching { connection.url.path.removePrefix("/wp-json/oracle/v1") }.getOrDefault("?")
        val code: Int
        val text: String
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
        } catch (e: Exception) {
            // Never reached a response at all — logged as such (distinct
            // from a real HTTP error code, which means the server WAS
            // reachable) — then rethrown exactly as before.
            OracleNetworkLog.log(method, path, null, 0)
            throw e
        }
        OracleNetworkLog.log(method, path, code, text.length)
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message", "HTTP $code") }.getOrDefault("HTTP $code")
            if (code == 401) throw OracleUnauthorizedException(message)
            throw IllegalStateException(message)
        }
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    private fun post(path: String, token: String?, body: JSONObject): JSONObject {
        val connection = connection(path, "POST", token)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readResponse(connection)
    }

    fun register(username: String, password: String, securityAnswers: Map<Int, String>, notificationEmail: String): Result<Pair<String, String>> = runCatching {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("security_answers", JSONObject().apply { securityAnswers.forEach { (i, a) -> put(i.toString(), a) } })
            put("notification_email", notificationEmail)
        }
        val response = post("/register", null, body)
        // With owner approval enabled server-side the token is absent until
        // the account is approved; the app then shows the "awaiting approval"
        // message instead of logging the person in.
        response.optString("token", "") to response.optString("backup_code", "")
    }

    fun login(username: String, password: String): Result<String> = runCatching {
        val body = JSONObject().apply { put("username", username); put("password", password) }
        post("/login", null, body).getString("token")
    }

    fun forgotPassword(username: String, securityAnswers: Map<Int, String>, backupCode: String, newPassword: String): Result<Unit> = runCatching {
        val body = JSONObject().apply {
            put("username", username)
            put("security_answers", JSONObject().apply { securityAnswers.forEach { (i, a) -> put(i.toString(), a) } })
            put("backup_code", backupCode)
            put("new_password", newPassword)
        }
        post("/forgot-password", null, body)
        Unit
    }

    fun getAllData(token: String): Result<JSONObject> = runCatching {
        readResponse(connection("/data", "GET", token))
    }

    /** No-auth health check for the "Server Connection" indicator on START —
     *  deliberately unauthenticated so it answers the same yes/no question
     *  whether or not there is a session, including in DEMO mode. */
    fun ping(): Result<Unit> = runCatching {
        readResponse(connection("/ping", "GET", null))
        Unit
    }

    /** Stage 3: today's server-ranked SHORT/MEDIUM/LONG picks — the same
     *  ranking OracleGrowthEngine.run() computes on-device, but over the
     *  server's full universe scan rather than the on-device 700-ticker
     *  budget. See OracleGrowthEngine.tryServerPicks() for how this is used. */
    fun getGrowthPicks(token: String): Result<JSONObject> = runCatching {
        readResponse(connection("/growth-picks", "GET", token))
    }

    /** Stage 1: one ticker's server-side scan for today's anchor, if it's in
     *  the server's ~954-ticker universe and already scanned. Used by
     *  Watchlist so a saved ticker inside that universe gets scored for
     *  free from the server's existing work, instead of the phone fetching
     *  a year of its own candles and recomputing — falls back to that local
     *  path only for tickers outside the universe or not yet scanned. */
    fun getUniverseScan(token: String, ticker: String): Result<JSONObject> = runCatching {
        readResponse(connection("/universe-scan?ticker=${java.net.URLEncoder.encode(ticker, "UTF-8")}", "GET", token))
    }

    fun saveData(token: String, type: String, payload: String): Result<Unit> = runCatching {
        val connection = connection("/data/$type", "POST", token)
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        readResponse(connection)
        Unit
    }

    fun notify(token: String, subject: String, message: String): Result<JSONObject> = runCatching {
        post("/notify", token, JSONObject().apply { put("subject", subject); put("message", message) })
    }

    fun registerDevice(token: String, fcmToken: String): Result<Unit> = runCatching {
        post("/register-device", token, JSONObject().apply { put("fcm_token", fcmToken) })
        Unit
    }
}

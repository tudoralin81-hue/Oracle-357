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
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message", "HTTP $code") }.getOrDefault("HTTP $code")
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
        response.getString("token") to response.getString("backup_code")
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

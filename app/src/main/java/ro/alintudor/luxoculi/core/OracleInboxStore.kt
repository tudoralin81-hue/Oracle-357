package ro.alintudor.luxoculi.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists every push message actually received on this device, so tapping
 * (or dismissing) the system notification never loses it — before this,
 * a message existed only as long as the notification tray held it, with
 * no way to go back and read it inside the app.
 */
data class OracleInboxMessage(
    val id: Long,
    val title: String,
    val body: String,
    val receivedAt: Long,
    val read: Boolean,
)

class OracleInboxStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("oracle_inbox", Context.MODE_PRIVATE)

    /** Newest-first, same rule as every other journal in the app. */
    fun load(): List<OracleInboxMessage> = parse(prefs.getString("messages", "[]") ?: "[]").sortedByDescending { it.receivedAt }

    fun unreadCount(): Int = load().count { !it.read }

    @Synchronized
    fun add(title: String, body: String) {
        val current = load().toMutableList()
        current.add(0, OracleInboxMessage(id = System.currentTimeMillis(), title = title, body = body, receivedAt = System.currentTimeMillis(), read = false))
        save(current.take(200))
    }

    @Synchronized
    fun markAllRead() {
        val current = load()
        if (current.none { !it.read }) return
        save(current.map { it.copy(read = true) })
    }

    private fun save(items: List<OracleInboxMessage>) {
        val arr = JSONArray()
        items.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("title", m.title); put("body", m.body); put("receivedAt", m.receivedAt); put("read", m.read)
            })
        }
        prefs.edit().putString("messages", arr.toString()).apply()
    }

    private fun parse(text: String): List<OracleInboxMessage> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            OracleInboxMessage(
                id = o.optLong("id", 0L), title = o.optString("title", ""), body = o.optString("body", ""),
                receivedAt = o.optLong("receivedAt", 0L), read = o.optBoolean("read", false),
            )
        }
    }.getOrDefault(emptyList())
}

package ro.alintudor.luxoculi.widget

import android.content.Context
import org.json.JSONObject
import ro.alintudor.luxoculi.core.OracleApiClient
import ro.alintudor.luxoculi.core.OracleAuthStore

/** The widget's background color is user-configurable (gear icon on the
 *  widget itself, or Android's own "widget settings" on long-press). Kept
 *  locally, and also synced to the account on alintudor.ro (data type
 *  "widget"), the same way every other setting in this app is — so it
 *  survives a login on a fresh install. */
object OracleWidgetSettingsStore {
    private const val PREFS_NAME = "oracle_widget_settings"
    private const val KEY_COLOR = "background_color"

    // Light gray, fairly transparent by default.
    const val DEFAULT_COLOR = 0x96CDD0D6.toInt() // ARGB: alpha 0x96 (~59%), rgb (205,208,214)

    val PRESETS = listOf(
        0x96CDD0D6.toInt() to "Light gray",
        0x96324F73.toInt() to "Light blue",
        0x96141A2C.toInt() to "Dark navy",
        0x96173B24.toInt() to "Dark green",
        0x963B1616.toInt() to "Dark red",
        0xC0CDD0D6.toInt() to "Light gray (opaque)"
    )

    fun color(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_COLOR, DEFAULT_COLOR)

    /** Saves locally and, if logged in, pushes to the account in the
     *  background — same fire-and-forget pattern as every other setting. */
    fun setColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COLOR, color).apply()
        val auth = OracleAuthStore(context)
        if (auth.hasSession()) {
            val payload = JSONObject().apply { put("background_color", color) }.toString()
            Thread { OracleApiClient.saveData(auth.token(), "widget", payload) }.start()
        }
    }

    /** Called after login/pullAll to restore the color from the account,
     *  if the server has one. Safe to call with no "widget" data present. */
    fun restoreFromServerPayload(context: Context, payload: JSONObject?) {
        val color = payload?.optInt("background_color", Int.MIN_VALUE) ?: return
        if (color == Int.MIN_VALUE) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COLOR, color).apply()
    }
}

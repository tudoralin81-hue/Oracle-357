package ro.alintudor.oracle.widget

import android.content.Context

/** The widget's background color is user-configurable (gear icon on the
 *  widget itself, or Android's own "widget settings" on long-press) — this
 *  just remembers the choice. Default is a light, fairly transparent gray. */
object OracleWidgetSettingsStore {
    private const val PREFS_NAME = "oracle_widget_settings"
    private const val KEY_COLOR = "background_color"

    // Light gray, more transparent than before by default.
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

    fun setColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_COLOR, color).apply()
    }
}

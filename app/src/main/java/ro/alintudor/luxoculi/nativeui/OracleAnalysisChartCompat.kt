package ro.alintudor.luxoculi.nativeui

import android.graphics.Color

/** Small compatibility helpers used by the native Analysis chart renderer. */
fun Int.red(): Int = Color.red(this)
fun Int.green(): Int = Color.green(this)
fun Int.blue(): Int = Color.blue(this)

/**
 * Compatibility facade for the existing Typeface references in the project.
 *
 * Note this object SHADOWS android.graphics.Typeface in every file that does
 * `import ro.alintudor.luxoculi.nativeui.*` — so a member missing here is
 * "unresolved" even though the platform class has it. Anything the app needs
 * must therefore be mirrored below rather than assumed available.
 */
object Typeface {
    val DEFAULT: android.graphics.Typeface = android.graphics.Typeface.DEFAULT
    val DEFAULT_BOLD: android.graphics.Typeface = android.graphics.Typeface.DEFAULT_BOLD
    val SERIF: android.graphics.Typeface = android.graphics.Typeface.SERIF
    val SANS_SERIF: android.graphics.Typeface = android.graphics.Typeface.SANS_SERIF
    val MONOSPACE: android.graphics.Typeface = android.graphics.Typeface.MONOSPACE
    const val NORMAL: Int = android.graphics.Typeface.NORMAL
    const val BOLD: Int = android.graphics.Typeface.BOLD
    const val ITALIC: Int = android.graphics.Typeface.ITALIC
    const val BOLD_ITALIC: Int = android.graphics.Typeface.BOLD_ITALIC

    fun create(family: android.graphics.Typeface?, style: Int): android.graphics.Typeface =
        android.graphics.Typeface.create(family, style)

    fun create(familyName: String, style: Int): android.graphics.Typeface =
        android.graphics.Typeface.create(familyName, style)
}

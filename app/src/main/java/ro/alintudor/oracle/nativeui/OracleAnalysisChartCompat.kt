package ro.alintudor.oracle.nativeui

import android.graphics.Color

/** Small compatibility helpers used by the native Analysis chart renderer. */
fun Int.red(): Int = Color.red(this)
fun Int.green(): Int = Color.green(this)
fun Int.blue(): Int = Color.blue(this)

/** Compatibility facade for the existing Typeface references in the project. */
object Typeface {
    val DEFAULT_BOLD: android.graphics.Typeface = android.graphics.Typeface.DEFAULT_BOLD
    val SERIF: android.graphics.Typeface = android.graphics.Typeface.SERIF
    const val BOLD: Int = android.graphics.Typeface.BOLD

    fun create(family: android.graphics.Typeface?, style: Int): android.graphics.Typeface =
        android.graphics.Typeface.create(family, style)
}

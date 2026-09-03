package ro.alintudor.oracle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup

/** Compatibility shim; B515 Start is implemented by OracleMysticStartViewV2. */
class OracleMysticHeroView(context: Context, private val onModule: (String) -> Unit) : View(context) {
    private val start = OracleMysticStartViewV2(context, onModule)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The old host status/footer is outside the Start artwork. Remove that
        // sibling once the hero is attached, leaving the approved Start screen
        // as the complete page. No module code is touched.
        post {
            val host = parent as? ViewGroup ?: return@post
            val index = host.indexOfChild(this)
            if (index >= 0 && host.childCount > index + 1) {
                while (host.childCount > index + 1) {
                    host.removeViewAt(index + 1)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(2, 3, 5))
        start.layout(0, 0, width, height)
        start.draw(canvas)
    }
}

// B516 FINAL START: remove legacy host footer/status without changing protected modules.

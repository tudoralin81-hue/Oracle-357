package ro.alintudor.oracle.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the person pick any widget background color via four sliders (hue,
 * saturation, lightness, transparency) with a live preview, plus a few
 * quick presets. The chosen color is saved locally and synced to the
 * account on alintudor.ro.
 *
 * Reachable two ways — the gear icon drawn on the widget itself (works any
 * time, including for a widget already on the home screen), and Android's
 * normal "configure widget" flow when a new one is first added.
 */
class OracleWidgetConfigActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var hue = 220f
    private var saturation = 0.35f
    private var lightness = 0.55f
    private var alpha = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35)

        // Start the sliders from whatever color is already set.
        val current = OracleWidgetSettingsStore.color(this)
        val hsl = FloatArray(3)
        colorToHsl(current, hsl)
        hue = hsl[0]; saturation = hsl[1]; lightness = hsl[2]; alpha = (Color.alpha(current) * 100f / 255f).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(root)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply {
            text = "WIDGET BACKGROUND"; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold)
        })
        card.addView(TextView(this).apply {
            text = "Pick any color for the Growth widget."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(8), 0, dp(20))
        })

        val preview = TextView(this).apply {
            background = GradientDrawable().apply { setColor(current); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border) }
        }
        card.addView(preview, LinearLayout.LayoutParams(-1, dp(70)).apply { bottomMargin = dp(20) })

        fun updatePreview() {
            val color = buildColor()
            preview.background = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border) }
        }

        fun sliderRow(label: String, max: Int, initial: Int, trackColors: IntArray?, onChange: (Int) -> Unit) {
            card.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(10), 0, dp(4)) })
            val seek = SeekBar(this).apply {
                this.max = max
                progress = initial
                if (trackColors != null) {
                    progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, trackColors).apply { cornerRadius = dp(6).toFloat() }
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) { onChange(value); updatePreview() }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            card.addView(seek, LinearLayout.LayoutParams(-1, -2))
        }

        sliderRow("HUE", 360, hue.toInt(), intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)) { hue = it.toFloat() }
        sliderRow("SATURATION", 100, (saturation * 100).toInt(), intArrayOf(Color.rgb(140, 140, 140), Color.HSVToColor(floatArrayOf(hue, 1f, 0.8f)))) { saturation = it / 100f }
        sliderRow("LIGHTNESS", 100, (lightness * 100).toInt(), intArrayOf(Color.BLACK, Color.rgb(140, 140, 140), Color.WHITE)) { lightness = it / 100f }
        sliderRow("TRANSPARENCY", 100, alpha, intArrayOf(Color.TRANSPARENT, Color.WHITE)) { alpha = it }

        card.addView(TextView(this).apply {
            text = "APPLY"; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(15), 0, dp(15))
            isClickable = true; isFocusable = true
            setOnClickListener { applyColor(buildColor()) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        card.addView(TextView(this).apply {
            text = "QUICK PRESETS"; textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(20), 0, dp(8))
        })
        for ((color, label) in OracleWidgetSettingsStore.PRESETS) {
            card.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), border) }
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true; isFocusable = true
                setOnClickListener { applyColor(color) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        card.addView(TextView(this).apply {
            text = "Cancel"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(20), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildColor(): Int {
        val rgb = Color.HSVToColor(floatArrayOf(hue, saturation, lightness))
        val a = (alpha * 255 / 100).coerceIn(0, 255)
        return Color.argb(a, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    private fun colorToHsl(color: Int, out: FloatArray) {
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), out)
    }

    private fun applyColor(color: Int) {
        OracleWidgetSettingsStore.setColor(this, color)
        OracleGrowthWidgetProvider.updateAll(this)
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val resultValue = Intent().apply { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) }
            setResult(Activity.RESULT_OK, resultValue)
        } else {
            setResult(Activity.RESULT_OK)
        }
        Toast.makeText(this, "Widget updated.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

package ro.alintudor.oracle.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lets the person pick the widget's background color: a few presets, or a
 * custom hex value. Reachable two ways — the gear icon drawn on the widget
 * itself (works any time, including for a widget already on the home
 * screen), and Android's normal "configure widget" flow when a new one is
 * first added (since this Activity is registered as the widget's
 * android:configure target).
 */
class OracleWidgetConfigActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Standard widget-configure contract: if the system cancels before
        // an explicit RESULT_OK, the widget placement itself is cancelled.
        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID

        val bg = Color.rgb(3, 4, 12); val panel = Color.rgb(7, 14, 28); val border = Color.rgb(49, 82, 125)
        val muted = Color.rgb(165, 174, 195); val gold = Color.rgb(255, 205, 55); val green = Color.rgb(105, 245, 35)

        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(root)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(40), dp(28), dp(40)) }

        card.addView(TextView(this).apply {
            text = "WIDGET BACKGROUND"; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(gold)
        })
        card.addView(TextView(this).apply {
            text = "Pick a color for the Growth widget on your home screen."
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(8), 0, dp(26))
        })

        val currentColor = OracleWidgetSettingsStore.color(this)

        for ((color, label) in OracleWidgetSettingsStore.PRESETS) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(13), dp(14), dp(13))
                background = GradientDrawable().apply {
                    setColor(panel); cornerRadius = dp(12).toFloat()
                    setStroke(dp(if (color == currentColor) 2 else 1), if (color == currentColor) green else border)
                }
                isClickable = true; isFocusable = true
                setOnClickListener { applyColor(color) }
            }
            row.addView(TextView(this).apply {
                text = "  "; background = GradientDrawable().apply { setColor(Color.rgb(20, 22, 30)); cornerRadius = dp(8).toFloat(); setStroke(dp(1), border) }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
            row.addView(TextView(this).apply { text = label; textSize = 14f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (color == currentColor) row.addView(TextView(this).apply { text = "✓"; textSize = 16f; setTextColor(green); typeface = android.graphics.Typeface.DEFAULT_BOLD })
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        card.addView(TextView(this).apply {
            text = "CUSTOM COLOR (hex, e.g. 96324F73 — alpha+RGB)"
            textSize = 11f; setTextColor(muted); setPadding(dp(2), dp(24), 0, dp(6))
        })
        val hexField = EditText(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; setSingleLine(true)
            setText(String.format("%08X", currentColor))
            background = GradientDrawable().apply { setColor(panel); cornerRadius = dp(10).toFloat(); setStroke(dp(1), border) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        card.addView(hexField, LinearLayout.LayoutParams(-1, -2))

        card.addView(TextView(this).apply {
            text = "APPLY CUSTOM COLOR"; textSize = 13f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(Color.rgb(20, 90, 60)); cornerRadius = dp(12).toFloat() }
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener {
                val hex = hexField.text.toString().trim().removePrefix("#")
                val parsed = runCatching { hex.toLong(16).toInt() }.getOrNull()
                if (parsed == null || (hex.length != 6 && hex.length != 8)) {
                    Toast.makeText(this@OracleWidgetConfigActivity, "Enter a valid hex color (6 or 8 characters).", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val color = if (hex.length == 6) (0xFF shl 24) or parsed else parsed
                applyColor(color)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        card.addView(TextView(this).apply {
            text = "Cancel"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(muted); setPadding(0, dp(20), 0, 0)
            isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        })

        scroll.addView(card)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
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

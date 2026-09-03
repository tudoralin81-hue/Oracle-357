from pathlib import Path

P = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt")
s = P.read_text(encoding="utf-8")

# Use the real Oracle icon asset as the Growth loading spinner.
s = s.replace("import android.graphics.drawable.GradientDrawable\n", "import android.graphics.drawable.GradientDrawable\nimport android.animation.ObjectAnimator\nimport android.view.animation.LinearInterpolator\nimport android.util.Base64\nimport android.graphics.BitmapFactory\nimport android.widget.ImageView\n")
s = s.replace("import android.widget.ProgressBar\n", "")

old = '''        val spinner = ProgressBar(host.root.context).apply { isIndeterminate = true }\n        card.addView(spinner, LinearLayout.LayoutParams(host.dp(54), host.dp(54)).apply { gravity = Gravity.CENTER })\n'''
new = '''        val spinner = ImageView(host.root.context).apply {\n            val encoded = host.root.context.assets.open("oracle_icon.b64").bufferedReader().use { it.readText() }\n            val bytes = Base64.decode(encoded, Base64.DEFAULT)\n            setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))\n            scaleType = ImageView.ScaleType.CENTER_INSIDE\n            rotation = 0f\n        }\n        card.addView(spinner, LinearLayout.LayoutParams(host.dp(58), host.dp(58)).apply { gravity = Gravity.CENTER })\n        ObjectAnimator.ofFloat(spinner, View.ROTATION, 0f, 360f).apply {\n            duration = 1150L\n            repeatCount = ObjectAnimator.INFINITE\n            interpolator = LinearInterpolator()\n            start()\n        }\n'''
if old not in s:
    raise SystemExit("Growth loader anchor not found")
s = s.replace(old, new, 1)
P.write_text(s, encoding="utf-8")
print("Oracle icon loader applied")

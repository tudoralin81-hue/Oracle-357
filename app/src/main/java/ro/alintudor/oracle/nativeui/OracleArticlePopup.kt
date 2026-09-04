package ro.alintudor.oracle.nativeui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import ro.alintudor.oracle.core.OracleKnowledgeArticle
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows an article as a clean, native in-app popup — just its title, image,
 *  and preview text, nothing else. Deliberately does NOT load the live web
 *  page: the site gates full article bodies behind a login wall, so
 *  rendering the real page would just show that wall front and center — and
 *  even inside the app, a WebView still reads as "the browser" to the
 *  person. This is the reader-preview version instead. */
fun showArticlePopup(context: Context, article: OracleKnowledgeArticle) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.rgb(1, 3, 8)))

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(1, 3, 8))
    }

    val header = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }
    val closeBtn = TextView(context).apply {
        text = "✕"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = OracleNativeModule.rounded(Color.rgb(5, 8, 17), dp(12), Color.rgb(255, 205, 45), dp(1))
        isClickable = true; isFocusable = true
        setOnClickListener { dialog.dismiss() }
    }
    header.addView(closeBtn, LinearLayout.LayoutParams(dp(42), dp(42)))
    header.addView(TextView(context).apply {
        text = article.title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header)
    root.addView(View(context).apply { setBackgroundColor(Color.rgb(255, 205, 45)) }, LinearLayout.LayoutParams(-1, dp(1)))

    val scroll = ScrollView(context).apply { isFillViewport = true }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)) }

    if (article.imageUrl.isNotBlank()) {
        val imageHeight = dp(200)
        val imageFrame = FrameLayout(context)
        val imageView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val loader = ProgressBar(context).apply { isIndeterminate = true }
        imageFrame.addView(imageView, FrameLayout.LayoutParams(-1, -1))
        imageFrame.addView(loader, FrameLayout.LayoutParams(dp(32), dp(32)).apply { gravity = Gravity.CENTER })
        content.addView(imageFrame, LinearLayout.LayoutParams(-1, imageHeight).apply { bottomMargin = dp(16) })
        loadBitmapAsync(article.imageUrl) { bitmap ->
            loader.visibility = View.GONE
            if (bitmap != null) imageView.setImageBitmap(bitmap) else imageFrame.visibility = View.GONE
        }
    }

    content.addView(TextView(context).apply {
        text = article.title; textSize = 21f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        setLineSpacing(dp(2).toFloat(), 1f)
    })
    if (article.publishedAt > 0L) content.addView(TextView(context).apply {
        text = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(article.publishedAt))
        textSize = 12f; setTextColor(Color.rgb(255, 205, 45)); setPadding(0, dp(6), 0, dp(14))
    })
    content.addView(TextView(context).apply {
        text = article.excerpt; textSize = 15f; setTextColor(Color.rgb(200, 207, 222)); setLineSpacing(dp(4).toFloat(), 1f)
    })

    scroll.addView(content)
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

    dialog.setContentView(root)
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    dialog.show()
}

private fun loadBitmapAsync(url: String, onResult: (Bitmap?) -> Unit) {
    Thread {
        val bitmap = runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 15000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            }
            try {
                if (c.responseCode !in 200..299) null else c.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally { c.disconnect() }
        }.getOrNull()
        Handler(Looper.getMainLooper()).post { onResult(bitmap) }
    }.start()
}

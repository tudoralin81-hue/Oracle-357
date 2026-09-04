package ro.alintudor.oracle.nativeui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Opens a URL in a full-screen in-app popup instead of the external browser
 *  — no new browser tab, and the popup can be dismissed with one tap. Cookies
 *  are persisted (CookieManager's default Android store), so a login done
 *  once inside this popup (e.g. for a membership-gated article) carries over
 *  to the next article opened, without needing to log in again each time. */
fun showArticleWebView(context: Context, url: String, title: String) {
    fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(1, 3, 8)))

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
        text = title; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), 0, 0, 0)
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(header)
    root.addView(View(context).apply { setBackgroundColor(Color.rgb(255, 205, 45)) }, LinearLayout.LayoutParams(-1, dp(1)))

    val progress = ProgressBar(context).apply { isIndeterminate = true }
    root.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER; topMargin = dp(20) })

    val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                progress.visibility = View.GONE
            }
        }
        loadUrl(url)
    }
    root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

    dialog.setContentView(root)
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    dialog.setOnDismissListener { webView.destroy() }
    dialog.show()
}

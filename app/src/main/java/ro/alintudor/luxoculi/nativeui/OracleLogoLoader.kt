package ro.alintudor.luxoculi.nativeui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Company logo next to a ticker. Loads once per ticker from a ticker-keyed
 * public logo CDN, caches the PNG on disk (cacheDir) and in memory, and
 * simply leaves the ImageView hidden if nothing usable comes back — a
 * missing logo must never delay or break a card. Never touches the UI
 * thread for network or decoding.
 */
object OracleLogoLoader {
    private const val MAX_BYTES = 400_000
    private val memory = LruCache<String, Bitmap>(64)
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())
    private val sources = listOf(
        "https://assets.parqet.com/logos/symbol/%s?format=png",
        "https://financialmodelingprep.com/image-stock/%s.png",
    )

    fun load(context: Context, ticker: String, target: ImageView) {
        val key = ticker.trim().uppercase(Locale.US)
        if (key.isBlank()) { target.visibility = android.view.View.GONE; return }
        target.tag = key
        memory.get(key)?.let { target.setImageBitmap(it); target.visibility = android.view.View.VISIBLE; return }
        target.visibility = android.view.View.INVISIBLE
        if (!inFlight.add(key)) return
        val app = context.applicationContext
        Thread {
            val bmp = readDisk(app, key) ?: fetch(key)?.also { writeDisk(app, key, it) }
            inFlight.remove(key)
            if (bmp != null) memory.put(key, bmp)
            Handler(Looper.getMainLooper()).post {
                // The view may have been recycled for another ticker by now.
                if (target.tag != key) return@post
                if (bmp != null) { target.setImageBitmap(bmp); target.visibility = android.view.View.VISIBLE }
                else target.visibility = android.view.View.GONE
            }
        }.start()
    }

    private fun diskFile(app: Context, key: String) = File(File(app.cacheDir, "oracle_logos").apply { mkdirs() }, "$key.png")

    private fun readDisk(app: Context, key: String): Bitmap? = runCatching {
        val f = diskFile(app, key)
        if (f.exists() && f.length() in 1..MAX_BYTES.toLong()) BitmapFactory.decodeFile(f.absolutePath) else null
    }.getOrNull()

    private fun writeDisk(app: Context, key: String, bmp: Bitmap) {
        runCatching { diskFile(app, key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun fetch(key: String): Bitmap? {
        for (template in sources) {
            val bmp = runCatching {
                val c = (URL(String.format(template, key)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 6000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) OracleApp")
                }
                try {
                    if (c.responseCode !in 200..299) null
                    else {
                        val bytes = c.inputStream.use { it.readBytes() }
                        if (bytes.isEmpty() || bytes.size > MAX_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } finally { c.disconnect() }
            }.getOrNull()
            // Some CDNs answer a generic placeholder for unknown tickers; a
            // tiny image is a placeholder, not a logo.
            if (bmp != null && bmp.width >= 24 && bmp.height >= 24) return bmp
        }
        return null
    }
}
